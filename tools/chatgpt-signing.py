#!/usr/bin/env python3
"""Provision once and restore the durable personal APK signer without logging secrets."""

import argparse
import base64
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request


REPO = "0425-sketch/rikkahub"
PACKAGE = "me.rerere.rikkahub.personal"
SECRET_NAMES = {
    "primary": "RIKKAHUB_PERSONAL_SIGNING",
    "backup": "RIKKAHUB_PERSONAL_SIGNING_BACKUP",
}
SETUP_TOKEN_NAME = "RIKKAHUB_SIGNING_SETUP_TOKEN"
MAX_SECRET_BYTES = 48 * 1024
HEX_DIGEST = re.compile(r"[a-f0-9]{64}")
PRIVATE_KEY_PROOF_SOURCE = b'''import java.nio.file.*;
import java.security.*;
import java.nio.charset.StandardCharsets;

class SigningKeyProof {
    public static void main(String[] args) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (var stream = Files.newInputStream(Path.of(args[0]))) {
            store.load(stream, System.getenv("SIGNING_STORE_PASSWORD").toCharArray());
        }
        Key key = store.getKey(args[1], System.getenv("SIGNING_KEY_PASSWORD").toCharArray());
        if (!(key instanceof PrivateKey)) throw new GeneralSecurityException("Not a private-key entry");
        var certificate = store.getCertificate(args[1]);
        if (certificate == null) throw new GeneralSecurityException("Missing certificate");
        byte[] challenge = "RikkaHub local signing restoration proof v1".getBytes(StandardCharsets.UTF_8);
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign((PrivateKey) key);
        signer.update(challenge);
        byte[] proof = signer.sign();
        signer.initVerify(certificate.getPublicKey());
        signer.update(challenge);
        if (!signer.verify(proof)) throw new GeneralSecurityException("Private key does not match certificate");
    }
}
'''


class SigningError(Exception):
    """Only static, non-sensitive messages may be passed to this exception."""


def fail(code, message):
    raise SigningError(f"{code}: {message}")


def mask(value):
    # Outside Actions do not emit secret-bearing control commands at all.
    if value and os.environ.get("GITHUB_ACTIONS") == "true":
        escaped = str(value).replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
        print(f"::add-mask::{escaped}", flush=True)


def read_identity(path, require_certificate=False):
    try:
        identity = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        fail("IDENTITY", "The public signing identity file is missing or invalid.")
    if not isinstance(identity, dict) or identity.get("schema") != 1 or identity.get("application_id") != PACKAGE:
        fail("IDENTITY", "The public signing identity has an unexpected schema or package.")
    certificate = identity.get("certificate_sha256")
    if certificate is not None and (not isinstance(certificate, str) or not HEX_DIGEST.fullmatch(certificate)):
        fail("IDENTITY", "The public certificate fingerprint is invalid.")
    if require_certificate and certificate is None:
        fail("IDENTITY_UNPINNED", "Commit the provisioned public certificate before restoring a release signer.")
    return identity


def encode_bundle(bundle):
    encoded = base64.b64encode(json.dumps(bundle, separators=(",", ":"), sort_keys=True).encode()).decode("ascii")
    if len(encoded.encode()) > MAX_SECRET_BYTES:
        fail("SECRET_SIZE", "The signing bundle exceeds GitHub's secret size limit.")
    return encoded


def decode_bundle(encoded):
    mask(encoded)
    if not isinstance(encoded, str) or not encoded or len(encoded.encode()) > MAX_SECRET_BYTES:
        fail("BUNDLE", "The persisted signing bundle is missing or exceeds its size limit.")
    try:
        raw = base64.b64decode(encoded, validate=True)
        bundle = json.loads(raw)
    except (ValueError, UnicodeError):
        fail("BUNDLE", "The persisted signing bundle is not valid encoded JSON.")
    if not isinstance(bundle, dict):
        fail("BUNDLE", "The persisted signing bundle must be an object.")
    # Register decoded components before validation or any subprocess can report errors.
    for field in ("store_password", "key_password", "keystore_pkcs12_b64"):
        value = bundle.get(field)
        if isinstance(value, str):
            mask(value)
    if bundle.get("schema") != 1 or bundle.get("application_id") != PACKAGE:
        fail("BUNDLE_IDENTITY", "The signing bundle has an unexpected schema or package.")
    certificate = bundle.get("certificate_sha256")
    if not isinstance(certificate, str) or not HEX_DIGEST.fullmatch(certificate):
        fail("BUNDLE_IDENTITY", "The signing bundle's public certificate fingerprint is invalid.")
    alias = bundle.get("key_alias")
    if not isinstance(alias, str) or not re.fullmatch(r"[A-Za-z0-9_.-]{1,80}", alias):
        fail("BUNDLE", "The signing alias is invalid.")
    for field in ("store_password", "key_password"):
        value = bundle.get(field)
        if not isinstance(value, str) or not 24 <= len(value) <= 1024 or any(ord(c) < 33 or ord(c) > 126 for c in value):
            fail("BUNDLE", "A signing password is missing or invalid.")
    try:
        key_bytes = base64.b64decode(bundle.get("keystore_pkcs12_b64", ""), validate=True)
    except (ValueError, TypeError):
        fail("BUNDLE", "The persisted PKCS12 encoding is invalid.")
    if not 512 <= len(key_bytes) <= 32768:
        fail("BUNDLE", "The persisted PKCS12 has an unexpected size.")
    return bundle, key_bytes


def public_identity(bundle):
    return {"schema": 1, "application_id": PACKAGE, "certificate_sha256": bundle["certificate_sha256"]}


def write_private(path, data):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "wb") as stream:
        stream.write(data)


def run_private(command, env):
    try:
        return subprocess.run(command, env=env, stdin=subprocess.DEVNULL, timeout=60, check=True, capture_output=True).stdout
    except (OSError, subprocess.SubprocessError):
        # Never include subprocess output, command arguments, or exception repr.
        fail("KEYSTORE", "The signing material could not be opened or its private key could not be verified.")


def keytool_environment(**passwords):
    # Keytool needs only its own passwords, not bootstrap authorization or both
    # persisted secret copies inherited by the restore/provision step.
    excluded = {SETUP_TOKEN_NAME, *SECRET_NAMES.values()}
    return {**{key: value for key, value in os.environ.items() if key not in excluded}, **passwords}


def validate_bundle(bundle, key_bytes, identity, directory):
    expected = identity.get("certificate_sha256")
    if expected is not None and bundle["certificate_sha256"] != expected:
        fail("SIGNER_MISMATCH", "The persisted signer differs from the committed public certificate.")
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    keystore = directory / "signer.p12"
    write_private(keystore, key_bytes)
    env = keytool_environment(SIGNING_STORE_PASSWORD=bundle["store_password"], SIGNING_KEY_PASSWORD=bundle["key_password"])
    common = ["-keystore", str(keystore), "-storetype", "PKCS12", "-storepass:env", "SIGNING_STORE_PASSWORD", "-alias", bundle["key_alias"]]
    certificate = run_private(["keytool", "-exportcert", *common], env)
    if hashlib.sha256(certificate).hexdigest() != bundle["certificate_sha256"]:
        fail("SIGNER_MISMATCH", "The PKCS12 certificate does not match its recorded fingerprint.")
    # keytool -certreq can fall back to the store password for PKCS12, so merely
    # creating a CSR does not validate the configured key password. Load that key
    # explicitly and prove it can sign against this certificate's public key.
    proof_source = directory / "SigningKeyProof.java"
    write_private(proof_source, PRIVATE_KEY_PROOF_SOURCE)
    run_private(["java", str(proof_source), str(keystore), bundle["key_alias"]], env)
    proof_source.unlink(missing_ok=True)
    return keystore


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class GitHubSecrets:
    def __init__(self, token):
        if not token:
            fail("SETUP_PERMISSION", "Configure the repository signing setup token with Secrets write permission.")
        mask(token)
        self.token = token
        self.opener = urllib.request.build_opener(NoRedirect())
        self.public_key = None

    def request(self, method, suffix, body=None, missing_ok=False):
        data = None if body is None else json.dumps(body).encode()
        request = urllib.request.Request(
            f"https://api.github.com/repos/{REPO}/actions/secrets{suffix}", data=data, method=method,
            headers={"Authorization": f"Bearer {self.token}", "Accept": "application/vnd.github+json", "X-GitHub-Api-Version": "2022-11-28", "Content-Type": "application/json", "User-Agent": "RikkaHub-durable-signer"},
        )
        try:
            with self.opener.open(request, timeout=30) as response:
                data = response.read(65537)
                if len(data) > 65536:
                    fail("SECRET_API", "The secret service returned an unexpectedly large response.")
                return json.loads(data) if data else None
        except urllib.error.HTTPError as error:
            if missing_ok and error.code == 404:
                return None
            fail("SECRET_API", f"GitHub secret storage returned HTTP {error.code}; sensitive response details are suppressed.")
        except (urllib.error.URLError, TimeoutError, OSError, ValueError):
            fail("SECRET_API", "GitHub secret storage is unavailable; sensitive response details are suppressed.")

    def metadata(self, name):
        return self.request("GET", "/" + name, missing_ok=True)

    def put(self, name, value):
        if self.public_key is None:
            self.public_key = self.request("GET", "/public-key")
        try:
            from nacl.public import PublicKey, SealedBox
            key = PublicKey(base64.b64decode(self.public_key["key"], validate=True))
            encrypted = base64.b64encode(SealedBox(key).encrypt(value.encode())).decode("ascii")
        except (ImportError, ValueError, TypeError, KeyError):
            fail("SECRET_ENCRYPTION", "The GitHub public key or required encryption library is unavailable.")
        self.request("PUT", "/" + name, {"encrypted_value": encrypted, "key_id": self.public_key["key_id"]})

    def delete(self, name):
        self.request("DELETE", "/" + name)

    def prove_writable(self):
        # Prove both write and cleanup authorization with non-sensitive data before
        # generating the one long-lived private key.
        name = "RIKKAHUB_SIGNING_PROBE_" + secrets.token_hex(8).upper()
        if self.metadata(name) is not None:
            fail("STORAGE_PROBE", "The storage probe name already exists; no existing secret was changed.")
        self.put(name, "non-sensitive-signing-storage-probe")
        try:
            if self.metadata(name) is None:
                fail("STORAGE_PROBE", "GitHub did not confirm persistent secret storage.")
        finally:
            self.delete(name)


def generate_bundle(directory):
    password = secrets.token_urlsafe(48)
    mask(password)
    keystore = directory / "generated.p12"
    env = keytool_environment(SIGNING_NEW_PASSWORD=password)
    alias = "rikkahub-personal-v1"
    run_private([
        "keytool", "-genkeypair", "-keystore", str(keystore), "-storetype", "PKCS12",
        "-storepass:env", "SIGNING_NEW_PASSWORD", "-keypass:env", "SIGNING_NEW_PASSWORD",
        "-alias", alias, "-keyalg", "RSA", "-keysize", "4096", "-sigalg", "SHA256withRSA",
        "-validity", "36500", "-dname", "CN=RikkaHub Personal, O=Personal Android Application", "-noprompt",
    ], env)
    keystore.chmod(0o600)
    certificate = run_private(["keytool", "-exportcert", "-keystore", str(keystore), "-storetype", "PKCS12", "-storepass:env", "SIGNING_NEW_PASSWORD", "-alias", alias], env)
    encoded_key = base64.b64encode(keystore.read_bytes()).decode("ascii")
    mask(encoded_key)
    return {
        "schema": 1, "application_id": PACKAGE, "certificate_sha256": hashlib.sha256(certificate).hexdigest(),
        "keystore_pkcs12_b64": encoded_key, "store_password": password, "key_password": password,
        "key_alias": alias, "created_at": datetime.now(timezone.utc).isoformat(),
    }


def write_public(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def bootstrap(identity, output, api, environ=None):
    environ = os.environ if environ is None else environ
    metadata = {source: api.metadata(name) for source, name in SECRET_NAMES.items()}
    loaded = {}
    for source, name in SECRET_NAMES.items():
        value = environ.get(name, "")
        if metadata[source] is not None and not value:
            fail("FRESH_RUN_REQUIRED", "An existing signing secret is absent from this run's snapshot. Start a fresh workflow; no key was generated or replaced.")
        if value:
            loaded[source] = decode_bundle(value)
    if not loaded and identity.get("certificate_sha256") is not None:
        fail("PINNED_SIGNER_MISSING", "The committed signer is missing. Automatic key replacement is forbidden.")
    if len({bundle["certificate_sha256"] for bundle, _ in loaded.values()}) > 1:
        fail("BACKUP_MISMATCH", "Primary and backup contain different signing identities. Neither was changed.")
    with tempfile.TemporaryDirectory(prefix="rikkahub-signing-", dir=environ.get("RUNNER_TEMP")) as temporary:
        directory = Path(temporary)
        for source, (bundle, key_bytes) in loaded.items():
            validate_bundle(bundle, key_bytes, identity, directory / source)
        api.prove_writable()
        if loaded:
            bundle = next(iter(loaded.values()))[0]
            created = False
        else:
            bundle = generate_bundle(directory)
            # The generated material must pass the same checks as restored material.
            generated = encode_bundle(bundle)
            bundle, key_bytes = decode_bundle(generated)
            validate_bundle(bundle, key_bytes, identity, directory / "generated-check")
            created = True
        encoded = encode_bundle(bundle)
        mask(encoded)
        # The backup is persisted first. A failure after this point must leave it
        # intact so a fresh run can repair the missing primary without rekeying.
        for source in ("backup", "primary"):
            if metadata[source] is not None:
                continue
            if api.metadata(SECRET_NAMES[source]) is not None:
                fail("CONCURRENT_SETUP", "A signing secret appeared during setup. No existing secret will be overwritten; start a fresh run.")
            api.put(SECRET_NAMES[source], encoded)
            metadata[source] = api.metadata(SECRET_NAMES[source])
            if metadata[source] is None:
                fail("PERSISTENCE_UNCONFIRMED", "The signing secret write has not been confirmed. Start a fresh run; do not generate another signer.")
        public = public_identity(bundle)
        write_public(output / "signing-identity.json", public)
        write_public(output / "signing-persistence.json", {
            **public, "created_new_signer": created, "storage": "github-repository-actions-secrets",
            "backup_scope": "second-secret-in-the-same-repository-vault",
            "secrets": {source: {"name": SECRET_NAMES[source], "created_at": metadata[source].get("created_at"), "updated_at": metadata[source].get("updated_at")} for source in SECRET_NAMES},
            "restoration_verified": False,
        })
        print("Signing material is persisted in primary and backup secrets. A fresh independent-runner verification is still required.")


def restore(identity, output, source, environ=None):
    environ = os.environ if environ is None else environ
    if identity.get("certificate_sha256") is None:
        fail("IDENTITY_UNPINNED", "Commit the provisioned public certificate before restoring a release signer.")
    bundle, key_bytes = decode_bundle(environ.get(SECRET_NAMES[source], ""))
    runner_temp = environ.get("RUNNER_TEMP")
    github_env = environ.get("GITHUB_ENV")
    if not runner_temp or not github_env:
        fail("RESTORE_ENVIRONMENT", "A private runner temporary directory and Actions environment file are required.")
    directory = Path(tempfile.mkdtemp(prefix="rikkahub-personal-signer-", dir=runner_temp))
    try:
        keystore = validate_bundle(bundle, key_bytes, identity, directory)
        lines = {
            "CHATGPT_TEST_KEYSTORE": str(keystore),
            "CHATGPT_TEST_STORE_PASSWORD": bundle["store_password"],
            "CHATGPT_TEST_KEY_ALIAS": bundle["key_alias"],
            "CHATGPT_TEST_KEY_PASSWORD": bundle["key_password"],
            "CHATGPT_SIGNING_TEMP_DIRECTORY": str(directory),
        }
        with Path(github_env).open("a", encoding="utf-8") as stream:
            for key, value in lines.items():
                stream.write(f"{key}={value}\n")
        write_public(output / f"signing-restoration-{source}.json", {
            **public_identity(bundle), "source": source, "secret_name": SECRET_NAMES[source],
            "private_key_verified": True, "run_id": environ.get("GITHUB_RUN_ID"),
            "run_attempt": environ.get("GITHUB_RUN_ATTEMPT"), "source_commit": environ.get("GITHUB_SHA"),
        })
        print(f"Restored and verified the committed signing identity from the {source} secret.")
    except BaseException:
        import shutil
        shutil.rmtree(directory, ignore_errors=True)
        raise


def verify_retirement_evidence(identity, verification, environ):
    """Accept only the current publisher's completed download and recovery proof."""
    certificate = identity.get("certificate_sha256")
    if not isinstance(certificate, str) or not HEX_DIGEST.fullmatch(certificate):
        fail("IDENTITY_UNPINNED", "A committed signing certificate is required before retiring setup authorization.")
    try:
        delivery = json.loads(verification.read_text(encoding="utf-8"))
        restoration = json.loads(verification.with_name("signing-restoration-verification.json").read_text(encoding="utf-8"))
    except (OSError, ValueError):
        fail("RETIREMENT_EVIDENCE", "The successful public-download and dual-restoration proofs are required.")
    if not isinstance(delivery, dict) or not isinstance(restoration, dict):
        fail("RETIREMENT_EVIDENCE", "The delivery verification files are invalid.")
    source = environ.get("GITHUB_SHA", "")
    run_id = environ.get("GITHUB_RUN_ID", "")
    attempt = environ.get("GITHUB_RUN_ATTEMPT", "")
    if not re.fullmatch(r"[a-f0-9]{40}", source) or not run_id.isdecimal() or not attempt.isdecimal():
        fail("RETIREMENT_EVIDENCE", "The current Actions source or run identity is unavailable.")
    version_name = delivery.get("version_name")
    version_match = re.fullmatch(r"(3\.[23]\.0)-personal\.[1-9][0-9]*", version_name) if isinstance(version_name, str) else None
    if version_match is None:
        fail("RETIREMENT_EVIDENCE", "The delivery proof does not identify a supported personal release version.")
    tag = f"custom-{version_match.group(1)}-personal-{source[:12]}-{run_id}-{attempt}"
    release_url = f"https://github.com/{REPO}/releases/tag/{tag}"
    expected = {
        "application_id": PACKAGE, "certificate_sha256": certificate,
        "expected_certificate_sha256": certificate, "source_commit": source,
        "tag": tag, "actions_url": f"https://github.com/{REPO}/actions/runs/{run_id}",
        "release_url": release_url, "signing_persistence": "durable-primary-and-backup-secrets",
    }
    if any(delivery.get(key) != value for key, value in expected.items()) or \
            any(delivery.get(flag) is not True for flag in ("public_download_verified", "two_clean_restorations_verified", "fixed_certificate_matches")):
        fail("RETIREMENT_EVIDENCE", "The completed delivery does not match this pinned signer, source and Actions run.")
    expected_url = f"https://github.com/{REPO}/releases/download/{tag}/rikkahub-{version_name}-{source[:12]}-arm64.apk"
    if not isinstance(version_name, str) or not re.fullmatch(r"[A-Za-z0-9._-]+", version_name) or \
            delivery.get("apk_url") != expected_url or \
            not isinstance(delivery.get("apk_sha256"), str) or not HEX_DIGEST.fullmatch(delivery["apk_sha256"]) or \
            type(delivery.get("apk_size")) is not int or delivery["apk_size"] <= 0:
        fail("RETIREMENT_EVIDENCE", "A verified public APK identity is missing from the delivery proof.")
    proof_identity = {"schema": 1, "application_id": PACKAGE, "certificate_sha256": certificate, "source_commit": source}
    if any(restoration.get(key) != value for key, value in proof_identity.items()) or restoration.get("restoration_verified") is not True:
        fail("RETIREMENT_EVIDENCE", "The signing restoration proof is missing or belongs to another identity.")
    jobs = restoration.get("clean_builds")
    if not isinstance(jobs, list) or len(jobs) != 2 or any(not isinstance(job, dict) for job in jobs):
        fail("RETIREMENT_EVIDENCE", "Both independent successful signing restoration jobs are required.")
    if {job.get("name") for job in jobs} != {"Restore and build (primary)", "Restore and build (backup)"} or \
            any(job.get("conclusion") != "success" or type(job.get("id")) is not int or job["id"] <= 0 or type(job.get("runner_id")) is not int or job["runner_id"] <= 0 for job in jobs) or \
            jobs[0]["id"] == jobs[1]["id"] or jobs[0]["runner_id"] == jobs[1]["runner_id"]:
        fail("RETIREMENT_EVIDENCE", "The signing restoration proofs do not identify two distinct successful jobs and runners.")


def retire_setup_token(identity, verification, environ=None, api=None):
    environ = os.environ if environ is None else environ
    verify_retirement_evidence(identity, verification, environ)
    token = environ.get(SETUP_TOKEN_NAME, "")
    if not token:
        print("No setup-token Secret is available in this run; signing copies are unchanged.")
        return "absent"
    client = api if api is not None else GitHubSecrets(token)
    try:
        if client.metadata(SETUP_TOKEN_NAME) is None:
            print("The setup-token Secret is already absent; signing copies are unchanged.")
            return "absent"
        # This is deliberately a fixed name, never a caller-supplied deletion target.
        client.delete(SETUP_TOKEN_NAME)
        if client.metadata(SETUP_TOKEN_NAME) is not None:
            fail("RETIREMENT_UNCONFIRMED", "The setup-token Secret removal has not been confirmed.")
    except SigningError:
        fail("SETUP_TOKEN_RETIRE_FAILED", "The setup-token Secret could not be retired and may remain stored. Primary and backup signing secrets were not changed.")
    print("Removed the one-time setup-token Secret after verified delivery. Primary and backup signing secrets remain stored; the user's PAT itself was not revoked.")
    return "removed"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("bootstrap", "restore", "retire-setup-token"))
    parser.add_argument("--identity", type=Path, required=True)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--source", choices=tuple(SECRET_NAMES))
    parser.add_argument("--verification", type=Path)
    args = parser.parse_args()
    if (os.environ.get("GITHUB_ACTIONS") != "true" or os.environ.get("GITHUB_REPOSITORY") != REPO
            or os.environ.get("GITHUB_REF") != "refs/heads/custom-3.2.0"):
        fail("ENVIRONMENT", "Signer commands require the personal repository's default Actions branch.")
    identity = read_identity(args.identity, require_certificate=args.command != "bootstrap")
    if args.command == "retire-setup-token":
        if args.verification is None:
            fail("RETIREMENT_EVIDENCE", "The successful publisher verification file is required.")
        retire_setup_token(identity, args.verification)
        return
    if args.output is None:
        fail("PUBLIC_OUTPUT", "A public receipt output directory is required.")
    if args.command == "bootstrap":
        bootstrap(identity, args.output, GitHubSecrets(os.environ.get(SETUP_TOKEN_NAME)))
    else:
        if args.source is None:
            fail("RESTORE_SOURCE", "Choose the primary or backup signing secret explicitly.")
        restore(identity, args.output, args.source)


if __name__ == "__main__":
    try:
        main()
    except SigningError as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
    except Exception:
        print("SIGNING_FAILED: Signing setup failed; raw details are suppressed to protect private material.", file=sys.stderr)
        sys.exit(1)
