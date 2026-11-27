"""Verify durable-signing boundaries with synthetic material and no GitHub writes."""

import base64
import contextlib
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest import mock


spec = importlib.util.spec_from_file_location("durable_signing", Path(__file__).parents[1] / "chatgpt-signing.py")
signing = importlib.util.module_from_spec(spec)
spec.loader.exec_module(signing)


def synthetic_bundle(certificate="a" * 64):
    return {
        "schema": 1,
        "application_id": signing.PACKAGE,
        "certificate_sha256": certificate,
        "keystore_pkcs12_b64": base64.b64encode(b"synthetic-test-keystore" * 50).decode(),
        "store_password": "synthetic-store-password-never-used-live",
        "key_password": "synthetic-key-password-never-used-live",
        "key_alias": "synthetic-test-alias",
    }


class FakeSecrets:
    def __init__(self, saved=None, fail_put=None, writable=True):
        self.saved = dict(saved or {})
        self.puts = []
        self.probes = 0
        self.fail_put = fail_put
        self.writable = writable

    def metadata(self, name):
        return {"name": name, "created_at": "2026-11-16T00:00:00Z", "updated_at": "2026-11-16T00:00:00Z"} if name in self.saved else None

    def prove_writable(self):
        self.probes += 1
        if not self.writable:
            signing.fail("SECRET_API", "Synthetic storage write permission denial.")

    def put(self, name, value):
        self.puts.append(name)
        if name == self.fail_put:
            signing.fail("SECRET_API", "Synthetic interrupted secret write.")
        if name in self.saved:
            raise AssertionError("Provisioning overwrote an existing secret")
        self.saved[name] = value


class DurableSigningTest(unittest.TestCase):
    def setUp(self):
        self.environment = mock.patch.dict(os.environ, {"GITHUB_ACTIONS": "false"})
        self.environment.start()
        self.bundle = synthetic_bundle()
        self.encoded = signing.encode_bundle(self.bundle)
        self.identity = {"schema": 1, "application_id": signing.PACKAGE, "certificate_sha256": None}
        self.primary = signing.SECRET_NAMES["primary"]
        self.backup = signing.SECRET_NAMES["backup"]

    def tearDown(self):
        self.environment.stop()

    def provision(self, output, api, env=None, identity=None):
        with mock.patch.object(signing, "generate_bundle", return_value=self.bundle) as generated, \
             mock.patch.object(signing, "validate_bundle") as validated, contextlib.redirect_stdout(io.StringIO()):
            signing.bootstrap(identity or self.identity, output, api, environ=env or {})
        return generated, validated

    def test_storage_write_permission_is_proven_before_generating_private_key(self):
        with tempfile.TemporaryDirectory() as folder, \
             mock.patch.object(signing, "generate_bundle") as generated:
            api = FakeSecrets(writable=False)
            with self.assertRaisesRegex(signing.SigningError, "SECRET_API"):
                signing.bootstrap(self.identity, Path(folder), api, environ={})
            generated.assert_not_called()
            self.assertEqual([], api.puts)

    def test_first_generation_persists_backup_before_primary_and_only_public_receipts(self):
        with tempfile.TemporaryDirectory() as folder:
            output = Path(folder)
            api = FakeSecrets()
            generated, validated = self.provision(output, api)
            generated.assert_called_once()
            validated.assert_called_once()
            self.assertEqual([self.backup, self.primary], api.puts)
            self.assertEqual(api.saved[self.backup], api.saved[self.primary])
            receipt = json.loads((output / "signing-persistence.json").read_text())
            self.assertFalse(receipt["restoration_verified"])
            self.assertEqual("second-secret-in-the-same-repository-vault", receipt["backup_scope"])
            self.assertEqual("a" * 64, receipt["certificate_sha256"])
            public_files = "".join(path.read_text() for path in output.glob("*.json"))
            for private in (self.encoded, self.bundle["store_password"], self.bundle["key_password"], self.bundle["keystore_pkcs12_b64"]):
                self.assertNotIn(private, public_files)

    def test_partial_primary_write_failure_is_repaired_from_saved_backup_without_rekeying(self):
        with tempfile.TemporaryDirectory() as folder, \
             mock.patch.object(signing, "generate_bundle", return_value=self.bundle) as generated, \
             mock.patch.object(signing, "validate_bundle"), contextlib.redirect_stdout(io.StringIO()):
            output = Path(folder)
            api = FakeSecrets(fail_put=self.primary)
            with self.assertRaisesRegex(signing.SigningError, "SECRET_API"):
                signing.bootstrap(self.identity, output, api, environ={})
            self.assertEqual({self.backup}, set(api.saved))
            saved_backup = api.saved[self.backup]
            self.assertFalse((output / "signing-persistence.json").exists())
            api.fail_put = None
            api.puts.clear()
            signing.bootstrap(self.identity, output, api, environ={self.backup: saved_backup})
            generated.assert_called_once()
            self.assertEqual([self.primary], api.puts)
            self.assertEqual(saved_backup, api.saved[self.primary])
            self.assertFalse(json.loads((output / "signing-persistence.json").read_text())["created_new_signer"])

    def test_existing_metadata_without_current_run_value_requires_fresh_run(self):
        with tempfile.TemporaryDirectory() as folder, mock.patch.object(signing, "generate_bundle") as generated:
            api = FakeSecrets({self.backup: self.encoded})
            with self.assertRaisesRegex(signing.SigningError, "FRESH_RUN_REQUIRED"):
                signing.bootstrap(self.identity, Path(folder), api, environ={})
            generated.assert_not_called()
            self.assertEqual(0, api.probes)
            self.assertEqual([], api.puts)

    def test_pinned_identity_missing_both_copies_cannot_be_automatically_replaced(self):
        with tempfile.TemporaryDirectory() as folder, mock.patch.object(signing, "generate_bundle") as generated:
            api = FakeSecrets()
            identity = {**self.identity, "certificate_sha256": "a" * 64}
            with self.assertRaisesRegex(signing.SigningError, "PINNED_SIGNER_MISSING"):
                signing.bootstrap(identity, Path(folder), api, environ={})
            generated.assert_not_called()
            self.assertEqual([], api.puts)

    def test_primary_backup_identity_conflict_does_not_overwrite_either_copy(self):
        with tempfile.TemporaryDirectory() as folder, mock.patch.object(signing, "generate_bundle") as generated:
            other = signing.encode_bundle(synthetic_bundle("b" * 64))
            values = {self.primary: self.encoded, self.backup: other}
            api = FakeSecrets(values)
            with self.assertRaisesRegex(signing.SigningError, "BACKUP_MISMATCH"):
                signing.bootstrap(self.identity, Path(folder), api, environ=values)
            generated.assert_not_called()
            self.assertEqual(values, api.saved)
            self.assertEqual([], api.puts)

    def test_existing_copies_are_validated_but_not_rewritten_or_regenerated(self):
        with tempfile.TemporaryDirectory() as folder:
            values = {self.primary: self.encoded, self.backup: self.encoded}
            api = FakeSecrets(values)
            generated, validated = self.provision(Path(folder), api, env=values)
            generated.assert_not_called()
            self.assertEqual(2, validated.call_count)
            self.assertEqual([], api.puts)

    def test_persisted_metadata_appearing_during_setup_is_not_overwritten(self):
        with tempfile.TemporaryDirectory() as folder, \
             mock.patch.object(signing, "generate_bundle", return_value=self.bundle), \
             mock.patch.object(signing, "validate_bundle"):
            api = FakeSecrets()
            def concurrent_writer():
                api.saved[self.backup] = "concurrent-existing-secret"
            api.prove_writable = concurrent_writer
            with self.assertRaisesRegex(signing.SigningError, "CONCURRENT_SETUP"):
                signing.bootstrap(self.identity, Path(folder), api, environ={})
            self.assertEqual([], api.puts)
            self.assertEqual("concurrent-existing-secret", api.saved[self.backup])

    def test_malformed_bundle_and_password_injection_are_rejected_without_echoing_values(self):
        bad_password = "synthetic-secret\nUNSAFE_ENV=1"
        cases = ["not-base64-secret-material", "x" * (signing.MAX_SECRET_BYTES + 1)]
        cases.append(signing.encode_bundle({**self.bundle, "store_password": bad_password}))
        cases.append(signing.encode_bundle({**self.bundle, "application_id": "another.package"}))
        for value in cases:
            with self.subTest(length=len(value)), self.assertRaises(signing.SigningError) as caught:
                signing.decode_bundle(value)
            self.assertNotIn(value, str(caught.exception))
            self.assertNotIn(bad_password, str(caught.exception))

    def test_restore_requires_committed_pin_before_reading_material(self):
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaisesRegex(signing.SigningError, "IDENTITY_UNPINNED"):
                signing.restore(self.identity, Path(folder), "primary", environ={self.primary: self.encoded})

    def test_restore_selects_requested_copy_and_records_public_origin_identity(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            env_file = root / "github-env"
            output = root / "public"
            identity = {**self.identity, "certificate_sha256": "a" * 64}
            env = {self.primary: "invalid-unused-primary", self.backup: self.encoded, "RUNNER_TEMP": str(root), "GITHUB_ENV": str(env_file), "GITHUB_SHA": "b" * 40, "GITHUB_RUN_ID": "123", "GITHUB_RUN_ATTEMPT": "2"}
            def validate(bundle, key_bytes, pin, directory):
                path = directory / "signer.p12"
                signing.write_private(path, key_bytes)
                return path
            with mock.patch.object(signing, "validate_bundle", side_effect=validate) as validator, contextlib.redirect_stdout(io.StringIO()):
                signing.restore(identity, output, "backup", environ=env)
            validator.assert_called_once()
            receipt = json.loads((output / "signing-restoration-backup.json").read_text())
            self.assertEqual({"schema": 1, "source": "backup", "application_id": signing.PACKAGE, "certificate_sha256": "a" * 64, "source_commit": "b" * 40, "run_id": "123", "run_attempt": "2", "private_key_verified": True, "secret_name": self.backup}, receipt)
            self.assertIn("CHATGPT_TEST_STORE_PASSWORD=" + self.bundle["store_password"], env_file.read_text())
            for private in (self.encoded, self.bundle["store_password"], self.bundle["key_password"], self.bundle["keystore_pkcs12_b64"]):
                self.assertNotIn(private, json.dumps(receipt))
            self.assertEqual(0o600, (next(root.glob("rikkahub-personal-signer-*")) / "signer.p12").stat().st_mode & 0o777)

    def test_restore_failure_removes_partial_material_without_exporting_environment(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            env_file = root / "github-env"
            identity = {**self.identity, "certificate_sha256": "a" * 64}
            env = {self.primary: self.encoded, "RUNNER_TEMP": str(root), "GITHUB_ENV": str(env_file)}
            def invalid(bundle, key_bytes, pin, directory):
                signing.write_private(directory / "signer.p12", key_bytes)
                signing.fail("KEYSTORE", "Synthetic unreadable private key.")
            with mock.patch.object(signing, "validate_bundle", side_effect=invalid), self.assertRaisesRegex(signing.SigningError, "KEYSTORE"):
                signing.restore(identity, root / "public", "primary", environ=env)
            self.assertEqual([], list(root.glob("rikkahub-personal-signer-*")))
            self.assertFalse(env_file.exists())

    def test_private_key_verification_checks_certificate_and_signed_proof_using_environment_passwords(self):
        with tempfile.TemporaryDirectory() as folder:
            certificate = b"synthetic-public-certificate"
            fingerprint = hashlib.sha256(certificate).hexdigest()
            bundle = synthetic_bundle(fingerprint)
            identity = {**self.identity, "certificate_sha256": fingerprint}
            def subprocess_result(command, env):
                self.assertNotIn(bundle["store_password"], command)
                self.assertNotIn(bundle["key_password"], command)
                if "-exportcert" in command:
                    return certificate
                return b""
            with mock.patch.object(signing, "run_private", side_effect=subprocess_result) as runner:
                signing.validate_bundle(bundle, b"synthetic-pkcs12", identity, Path(folder))
            self.assertEqual(2, runner.call_count)
            self.assertEqual("java", runner.call_args_list[1].args[0][0])
            self.assertTrue(runner.call_args_list[1].args[0][1].endswith("SigningKeyProof.java"))

    def test_subprocess_errors_never_expose_raw_private_output(self):
        secret = "synthetic-private-output"
        errors = [
            subprocess.CalledProcessError(1, ["keytool"], output=secret.encode(), stderr=secret.encode()),
            subprocess.TimeoutExpired(["keytool"], 60, output=secret.encode(), stderr=secret.encode()),
        ]
        for error in errors:
            with self.subTest(kind=type(error).__name__), mock.patch.object(signing.subprocess, "run", side_effect=error) as runner, self.assertRaises(signing.SigningError) as caught:
                signing.run_private(["keytool"], {})
            self.assertNotIn(secret, str(caught.exception))
            self.assertIs(subprocess.DEVNULL, runner.call_args.kwargs["stdin"])
            self.assertEqual(60, runner.call_args.kwargs["timeout"])

    def test_keytool_does_not_inherit_setup_authorization_or_persisted_bundles(self):
        values = {signing.SETUP_TOKEN_NAME: "synthetic-token", self.primary: self.encoded, self.backup: self.encoded}
        with mock.patch.dict(os.environ, values):
            env = signing.keytool_environment(SIGNING_KEY_PASSWORD="synthetic-key-password")
        for name in values:
            self.assertNotIn(name, env)
        self.assertEqual("synthetic-key-password", env["SIGNING_KEY_PASSWORD"])

    @unittest.skipUnless(shutil.which("keytool") and shutil.which("java"), "JDK keytool and Java are required for the short-lived test fixture")
    def test_real_short_lived_fixture_proves_private_key_password_and_certificate(self):
        # This key exists only inside this test's private temporary directory. It
        # is never the application's long-lived signer and is never persisted.
        with tempfile.TemporaryDirectory(prefix="synthetic-signing-test-") as folder:
            root = Path(folder)
            keystore = root / "synthetic.p12"
            password = "synthetic-only-signing-test-password"
            env = signing.keytool_environment(SYNTHETIC_TEST_PASSWORD=password)
            common = ["-keystore", str(keystore), "-storetype", "PKCS12", "-storepass:env", "SYNTHETIC_TEST_PASSWORD", "-alias", "synthetic-only"]
            subprocess.run(["keytool", "-genkeypair", *common, "-keypass:env", "SYNTHETIC_TEST_PASSWORD", "-keyalg", "RSA", "-keysize", "2048", "-validity", "1", "-dname", "CN=Synthetic unit test only", "-noprompt"], env=env, capture_output=True, check=True)
            certificate = subprocess.run(["keytool", "-exportcert", *common], env=env, capture_output=True, check=True).stdout
            bundle = {**self.bundle, "store_password": password, "key_password": password, "key_alias": "synthetic-only", "keystore_pkcs12_b64": base64.b64encode(keystore.read_bytes()).decode(), "certificate_sha256": hashlib.sha256(certificate).hexdigest()}
            restored, key_bytes = signing.decode_bundle(signing.encode_bundle(bundle))
            identity = signing.public_identity(restored)
            actual = signing.validate_bundle(restored, key_bytes, identity, root / "restored")
            self.assertTrue(actual.exists())
            with self.assertRaisesRegex(signing.SigningError, "KEYSTORE") as caught:
                signing.validate_bundle({**restored, "key_password": "synthetic-wrong-private-key-password"}, key_bytes, identity, root / "wrong-password")
            self.assertNotIn(password, str(caught.exception))


class GitHubSecretClientTest(unittest.TestCase):
    def test_storage_api_never_uses_plaintext_secret_in_transport(self):
        from nacl.public import PrivateKey, SealedBox
        private = PrivateKey.generate()
        client = signing.GitHubSecrets("synthetic-api-token")
        client.public_key = {"key": base64.b64encode(bytes(private.public_key)).decode(), "key_id": "public-key-id"}
        value = "synthetic-private-bundle-value"
        with mock.patch.object(client, "request") as request:
            client.put("SYNTHETIC_SECRET", value)
        method, path, payload = request.call_args.args
        self.assertEqual("PUT", method)
        self.assertEqual("/SYNTHETIC_SECRET", path)
        self.assertNotIn(value, json.dumps(payload))
        self.assertEqual(value.encode(), SealedBox(private).decrypt(base64.b64decode(payload["encrypted_value"])))

    def test_permission_probe_creates_only_non_sensitive_data_and_deletes_it(self):
        client = signing.GitHubSecrets("synthetic-api-token")
        with mock.patch.object(client, "metadata", side_effect=[None, {"name": "probe"}]), \
             mock.patch.object(client, "put") as put, mock.patch.object(client, "delete") as delete:
            client.prove_writable()
        self.assertEqual("non-sensitive-signing-storage-probe", put.call_args.args[1])
        delete.assert_called_once_with(put.call_args.args[0])

    def test_permission_probe_cleanup_runs_when_persistence_confirmation_fails(self):
        client = signing.GitHubSecrets("synthetic-api-token")
        with mock.patch.object(client, "metadata", return_value=None), \
             mock.patch.object(client, "put") as put, mock.patch.object(client, "delete") as delete, \
             self.assertRaisesRegex(signing.SigningError, "STORAGE_PROBE"):
            client.prove_writable()
        delete.assert_called_once_with(put.call_args.args[0])


class SetupTokenRetirementTest(unittest.TestCase):
    def evidence(self, root, version_name="3.2.0-personal.1"):
        identity = {"schema": 1, "application_id": signing.PACKAGE, "certificate_sha256": "a" * 64}
        env = {"GITHUB_SHA": "b" * 40, "GITHUB_RUN_ID": "123", "GITHUB_RUN_ATTEMPT": "2", signing.SETUP_TOKEN_NAME: "synthetic-one-time-token"}
        tag = f"custom-{version_name.split('-personal.')[0]}-personal-" + "b" * 12 + "-123-2"
        delivery = {
            "application_id": signing.PACKAGE, "certificate_sha256": "a" * 64,
            "expected_certificate_sha256": "a" * 64, "source_commit": "b" * 40,
            "tag": tag, "actions_url": f"https://github.com/{signing.REPO}/actions/runs/123",
            "release_url": f"https://github.com/{signing.REPO}/releases/tag/{tag}",
            "apk_url": f"https://github.com/{signing.REPO}/releases/download/{tag}/rikkahub-{version_name}-{'b' * 12}-arm64.apk",
            "version_name": version_name, "apk_sha256": "c" * 64, "apk_size": 123456,
            "signing_persistence": "durable-primary-and-backup-secrets",
            "public_download_verified": True, "two_clean_restorations_verified": True, "fixed_certificate_matches": True,
        }
        restoration = {
            **identity, "source_commit": "b" * 40, "restoration_verified": True,
            "clean_builds": [
                {"name": "Restore and build (primary)", "id": 11, "runner_id": 21, "conclusion": "success"},
                {"name": "Restore and build (backup)", "id": 12, "runner_id": 22, "conclusion": "success"},
            ],
        }
        verification = root / "verified-delivery.json"
        verification.write_text(json.dumps(delivery))
        (root / "signing-restoration-verification.json").write_text(json.dumps(restoration))
        return identity, env, verification, delivery, restoration

    def test_previous_and_current_release_tags_allow_idempotent_no_token_cleanup(self):
        for version in ("3.2.0-personal.1", "3.2.0-personal.2", "3.3.0-personal.1"):
            with self.subTest(version=version), tempfile.TemporaryDirectory() as folder, contextlib.redirect_stdout(io.StringIO()):
                identity, env, verification, _, _ = self.evidence(Path(folder), version)
                api = mock.Mock()
                self.assertEqual("absent", signing.retire_setup_token(
                    identity, verification, {**env, signing.SETUP_TOKEN_NAME: ""}, api,
                ))
                api.metadata.assert_not_called()
                api.delete.assert_not_called()
                api.put.assert_not_called()

    def test_current_release_rejects_wrong_family_source_run_and_unapproved_versions(self):
        changes = (
            {"version_name": "3.2.0-personal.2"},
            {"version_name": "3.4.0-personal.1"},
            {"version_name": "3.3.0-personal.0"},
            {"version_name": "3.3.0-personal.1/other"},
            {"tag": "custom-3.2.0-personal-" + "b" * 12 + "-123-2"},
            {"tag": "custom-3.3.0-personal-" + "d" * 12 + "-123-2"},
            {"tag": "custom-3.3.0-personal-" + "b" * 12 + "-999-2"},
            {"tag": "custom-3.3.0-personal-" + "b" * 12 + "-123-3"},
        )
        for change in changes:
            with self.subTest(change=change), tempfile.TemporaryDirectory() as folder:
                identity, env, verification, delivery, _ = self.evidence(Path(folder), "3.3.0-personal.1")
                delivery.update(change)
                verification.write_text(json.dumps(delivery))
                api = mock.Mock()
                with self.assertRaisesRegex(signing.SigningError, "RETIREMENT_EVIDENCE"):
                    signing.retire_setup_token(identity, verification, env, api)
                api.metadata.assert_not_called()
                api.delete.assert_not_called()
                api.put.assert_not_called()

    def test_successful_delivery_retires_only_setup_secret_and_empty_or_missing_is_idempotent(self):
        with tempfile.TemporaryDirectory() as folder, contextlib.redirect_stdout(io.StringIO()):
            identity, env, verification, _, _ = self.evidence(Path(folder))
            api = mock.Mock()
            api.metadata.side_effect = [{"name": signing.SETUP_TOKEN_NAME}, None]
            self.assertEqual("removed", signing.retire_setup_token(identity, verification, env, api))
            api.delete.assert_called_once_with(signing.SETUP_TOKEN_NAME)
            self.assertEqual([mock.call(signing.SETUP_TOKEN_NAME), mock.call(signing.SETUP_TOKEN_NAME)], api.metadata.call_args_list)
            api.put.assert_not_called()
            api = mock.Mock()
            api.metadata.return_value = None
            self.assertEqual("absent", signing.retire_setup_token(identity, verification, env, api))
            api.delete.assert_not_called()
            api = mock.Mock()
            self.assertEqual("absent", signing.retire_setup_token(identity, verification, {**env, signing.SETUP_TOKEN_NAME: ""}, api))
            api.metadata.assert_not_called()
            api.delete.assert_not_called()

    def test_missing_stale_or_incomplete_proof_never_deletes_setup_authorization(self):
        for failure in ("download", "restoration", "source", "signer", "same-runner", "failed-job", "unpin", "missing-file"):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as folder:
                root = Path(folder)
                identity, env, verification, delivery, restoration = self.evidence(root)
                if failure == "download": delivery["public_download_verified"] = False
                if failure == "restoration": delivery["two_clean_restorations_verified"] = False
                if failure == "source": delivery["source_commit"] = "d" * 40
                if failure == "signer": delivery["certificate_sha256"] = "d" * 64
                if failure == "same-runner": restoration["clean_builds"][1]["runner_id"] = 21
                if failure == "failed-job": restoration["clean_builds"][1]["conclusion"] = "failure"
                if failure == "unpin": identity["certificate_sha256"] = None
                verification.write_text(json.dumps(delivery))
                proof = root / "signing-restoration-verification.json"
                proof.write_text(json.dumps(restoration))
                if failure == "missing-file": proof.unlink()
                api = mock.Mock()
                with self.assertRaises(signing.SigningError):
                    signing.retire_setup_token(identity, verification, env, api)
                api.metadata.assert_not_called()
                api.delete.assert_not_called()

    def test_permission_failure_is_static_and_never_mutates_signing_copies(self):
        for stage in ("read", "delete", "confirmation"):
            with self.subTest(stage=stage), tempfile.TemporaryDirectory() as folder:
                identity, env, verification, _, _ = self.evidence(Path(folder))
                api = mock.Mock()
                api.metadata.return_value = {"name": signing.SETUP_TOKEN_NAME}
                error = signing.SigningError("Synthetic permission failure with " + env[signing.SETUP_TOKEN_NAME])
                if stage == "read": api.metadata.side_effect = error
                if stage == "delete": api.delete.side_effect = error
                with self.assertRaisesRegex(signing.SigningError, "SETUP_TOKEN_RETIRE_FAILED") as caught:
                    signing.retire_setup_token(identity, verification, env, api)
                self.assertNotIn(env[signing.SETUP_TOKEN_NAME], str(caught.exception))
                self.assertIn("may remain stored", str(caught.exception))
                api.put.assert_not_called()
                for call in api.delete.call_args_list:
                    self.assertEqual((signing.SETUP_TOKEN_NAME,), call.args)


class SigningEntrypointAuthorizationTest(unittest.TestCase):
    """Exercise the real CLI gate before any identity, private material or storage access."""

    def invoke(self, command, ref, *, repo=signing.REPO, actions="true", allowed=False, source="primary"):
        identity = {"schema": 1, "application_id": signing.PACKAGE, "certificate_sha256": "a" * 64}
        args = ["chatgpt-signing.py", command, "--identity", "pin.json", "--output", "public-output",
                "--source", source, "--verification", "verified-delivery.json"]
        with mock.patch.dict(os.environ, {"GITHUB_ACTIONS": actions, "GITHUB_REPOSITORY": repo,
                                          "GITHUB_REF": ref}, clear=True), \
             mock.patch.object(signing.sys, "argv", args), \
             mock.patch.object(signing, "read_identity", return_value=identity) as read_identity, \
             mock.patch.object(signing, "GitHubSecrets") as storage, \
             mock.patch.object(signing, "bootstrap") as bootstrap, \
             mock.patch.object(signing, "restore") as restore, \
             mock.patch.object(signing, "retire_setup_token") as retire:
            dispatch = {"bootstrap": bootstrap, "restore": restore, "retire-setup-token": retire}
            if allowed:
                signing.main()
                read_identity.assert_called_once_with(Path("pin.json"), require_certificate=command != "bootstrap")
                dispatch[command].assert_called_once()
                for name, operation in dispatch.items():
                    if name != command:
                        operation.assert_not_called()
                if command == "restore":
                    restore.assert_called_once_with(identity, Path("public-output"), source)
                    storage.assert_not_called()
                return
            with self.assertRaisesRegex(signing.SigningError, "ENVIRONMENT"):
                signing.main()
            read_identity.assert_not_called()
            storage.assert_not_called()
            for operation in dispatch.values():
                operation.assert_not_called()

    def test_artifact_branch_can_only_restore_either_existing_signer(self):
        for source in ("primary", "backup"):
            with self.subTest(source=source):
                self.invoke("restore", "refs/heads/migration/release-209", source=source, allowed=True)

    def test_artifact_branch_cannot_bootstrap_or_retire_secrets(self):
        for command in ("bootstrap", "retire-setup-token"):
            with self.subTest(command=command):
                self.invoke(command, "refs/heads/migration/release-209")

    def test_original_custom_branch_keeps_existing_command_permissions(self):
        for command in ("bootstrap", "restore", "retire-setup-token"):
            with self.subTest(command=command):
                self.invoke(command, "refs/heads/custom-3.2.0", allowed=True)

    def test_unrelated_branches_tags_and_pull_requests_never_reach_signer(self):
        for ref in ("refs/heads/master", "refs/heads/migration/release-209-other",
                    "refs/tags/migration/release-209", "refs/pull/1/merge", ""):
            for command in ("bootstrap", "restore", "retire-setup-token"):
                with self.subTest(ref=ref, command=command):
                    self.invoke(command, ref)

    def test_expected_ref_still_requires_correct_repository_and_actions(self):
        for ref in ("refs/heads/custom-3.2.0", "refs/heads/migration/release-209"):
            with self.subTest(ref=ref):
                self.invoke("restore", ref, repo="another-owner/rikkahub")
                self.invoke("restore", ref, actions="false")


if __name__ == "__main__":
    unittest.main()
