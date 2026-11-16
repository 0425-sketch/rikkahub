#!/usr/bin/env python3
"""Upload verified existing APK assets to an existing published GitHub Release."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import ssl
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request


DOWNLOAD_HOSTS = {"github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com"}


class DeliveryError(Exception):
    pass


def fail(kind, message):
    raise DeliveryError(f"{kind}: {message}")


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def local_assets(apk, checksum):
    for path in (apk, checksum):
        if not path.is_file() or not path.stat().st_size:
            fail("LOCAL", f"Missing or empty file: {path}")
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*", path.name):
            fail("LOCAL", "Asset filenames must use letters, numbers, '.', '_' or '-'.")
    if apk.name == checksum.name:
        fail("LOCAL", "APK and checksum filenames must differ.")
    if checksum.stat().st_size > 4096:
        fail("LOCAL", "Checksum file is unexpectedly large.")
    try:
        line = checksum.read_text(encoding="ascii").strip()
    except (UnicodeError, OSError):
        fail("LOCAL", "Checksum file must be readable ASCII.")
    match = re.fullmatch(r"([0-9a-fA-F]{64}) [ *](.+)", line)
    if not match or match.group(2) != apk.name:
        fail("LOCAL", "Checksum must contain exactly one sha256sum record naming the APK basename.")
    expected = match.group(1).lower()
    actual = sha256(apk)
    if actual != expected:
        fail("INTEGRITY", "Local APK does not match the checksum file; nothing was uploaded.")
    return [(apk, actual, apk.stat().st_size), (checksum, sha256(checksum), checksum.stat().st_size)]


def credential_env():
    token = os.environ.get("RIKKAHUB_RELEASE_TOKEN")
    if not token:
        fail("AUTH", "RIKKAHUB_RELEASE_TOKEN is missing. Configure it in cloud secure credentials for api.github.com and uploads.github.com; do not paste it into chat.")
    env = dict(os.environ)
    # Only this child process uses the selected release credential. Never log it.
    env["GH_TOKEN"] = token
    env["GH_HOST"] = "github.com"
    env["GH_PROMPT_DISABLED"] = "1"
    env["GH_PAGER"] = "cat"
    env.pop("GH_DEBUG", None)
    env.pop("DEBUG", None)
    return env


def classify_cli_error(stderr):
    low = stderr.lower()
    if "connect tunnel failed" in low or "proxyconnect" in low or "proxy error" in low:
        return "NETWORK", "Proxy connection failed; inspect domain policy or proxy reachability."
    if "request size too large" in low or "average request size too large" in low or "413" in low:
        return "CAPACITY", "Upload size or proxy payload limit rejected the request."
    if "401" in low or "bad credentials" in low:
        return "AUTH", "GitHub rejected the selected credential (401); verify its binding for the failing domain."
    if "429" in low or "rate limit" in low:
        return "RATE_LIMIT", "GitHub or the proxy limited the request. Stop and respect Retry-After."
    if "403" in low or "resource not accessible" in low:
        return "AUTH_OR_POLICY", "Request forbidden (403); check GitHub Contents write access and proxy policy evidence."
    if "404" in low:
        return "NOT_FOUND_OR_ACCESS", "Release/repository is missing or inaccessible to the selected credential."
    if any(word in low for word in ("timeout", "timed out", "certificate", "tls", "no such host", "network", "connection")):
        return "NETWORK", "Network, proxy, TLS or timeout failure; no automatic retry was attempted."
    if "already_exists" in low or "already exists" in low or "422" in low:
        return "CONFLICT", "Asset or release state changed; inspect existing assets before retrying. Nothing is overwritten."
    return "TOOL", "gh failed. Raw output is suppressed to avoid exposing credentials or signed URLs."


def gh(arguments, env):
    try:
        result = subprocess.run(["gh", *arguments], env=env, capture_output=True, text=True, timeout=900)
    except subprocess.TimeoutExpired:
        fail("NETWORK", "gh timed out. Upload outcome may be uncertain; rerun only after inspecting Release assets.")
    if result.returncode:
        kind, message = classify_cli_error(result.stderr)
        fail(kind, message)
    return result.stdout


def api_json(endpoint, env):
    raw = gh(["api", "--hostname", "github.com", endpoint], env)
    try:
        return json.loads(raw)
    except ValueError:
        fail("TOOL", "gh returned invalid JSON.")


def read_release(repo, tag, release_id, env):
    release = api_json(f"repos/{repo}/releases/{release_id}", env)
    if release.get("id") != release_id or release.get("tag_name") != tag:
        fail("IDENTITY", "Release ID and tag do not match. Nothing was uploaded.")
    if release.get("draft") or not release.get("published_at"):
        fail("STATE", "This script requires an already published Release.")
    assets = api_json(f"repos/{repo}/releases/{release_id}/assets?per_page=100", env)
    if not isinstance(assets, list) or len(assets) >= 100:
        fail("STATE", "Asset list is invalid or may be paginated; inspect it before uploading.")
    names = [asset.get("name") for asset in assets]
    if len(names) != len(set(names)):
        fail("CONFLICT", "Duplicate asset names were returned.")
    return release, {asset["name"]: asset for asset in assets}


def validate_download_url(url, *, repo=None, tag=None, name=None):
    try:
        parsed = urllib.parse.urlsplit(url)
        port = parsed.port
    except (TypeError, ValueError):
        fail("URL", "Invalid GitHub download URL.")
    if parsed.scheme != "https" or parsed.hostname not in DOWNLOAD_HOSTS or port not in (None, 443) or parsed.username or parsed.password or parsed.fragment:
        fail("URL", "Download or redirect must remain on an approved HTTPS GitHub host.")
    if repo is not None:
        expected = f"/{repo}/releases/download/{tag}/{name}"
        if parsed.hostname != "github.com" or urllib.parse.unquote(parsed.path) != expected or parsed.query:
            fail("IDENTITY", "Asset download URL does not match this repository, tag and filename.")
    return url


class GitHubRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        validate_download_url(newurl)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def verify_asset(asset, repo, tag, name, expected_sha, expected_size):
    if asset.get("state") != "uploaded" or asset.get("size") != expected_size:
        fail("INTEGRITY", f"Remote asset {name} has an unexpected state or size; it will not be overwritten.")
    api_digest = asset.get("digest")
    if api_digest and api_digest != "sha256:" + expected_sha:
        fail("INTEGRITY", f"Remote asset {name} has a different digest; it will not be overwritten.")
    url = validate_download_url(asset.get("browser_download_url"), repo=repo, tag=tag, name=name)
    # This public download carries no token. Inherited proxy settings and CA trust remain active.
    opener = urllib.request.build_opener(GitHubRedirects(), urllib.request.HTTPSHandler(context=ssl.create_default_context()))
    request = urllib.request.Request(url, headers={"User-Agent": "RikkaHub-release-verifier", "Accept": "application/octet-stream"})
    digest, size = hashlib.sha256(), 0
    try:
        with opener.open(request, timeout=120) as response:
            if response.status != 200:
                fail("DOWNLOAD", f"Public asset download returned HTTP {response.status}.")
            for chunk in iter(lambda: response.read(1024 * 1024), b""):
                size += len(chunk)
                if size > expected_size:
                    fail("INTEGRITY", f"Downloaded {name} is larger than expected.")
                digest.update(chunk)
    except urllib.error.HTTPError as error:
        fail("DOWNLOAD", f"Public asset download returned HTTP {error.code}; no credential was sent.")
    except (urllib.error.URLError, TimeoutError, OSError):
        fail("NETWORK", "Public download failed at the network/TLS layer; signed URL details are suppressed.")
    if size != expected_size or digest.hexdigest() != expected_sha:
        fail("INTEGRITY", f"Downloaded {name} does not match the local asset.")
    print(f"Verified {name}: {size} bytes; sha256={expected_sha}")
    return url


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", default="0425-sketch/rikkahub")
    parser.add_argument("--tag", required=True)
    parser.add_argument("--release-id", type=int, required=True)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--checksum", type=Path, help="Defaults to APK path plus .sha256")
    parser.add_argument("--check-local", action="store_true", help="Only verify local files; never use credentials or network")
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]*/[A-Za-z0-9][A-Za-z0-9_.-]*", args.repo):
        fail("INPUT", "Expected a GitHub owner/repository name.")
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*", args.tag) or args.release_id <= 0:
        fail("INPUT", "Use a positive Release ID and a tag containing letters, numbers, '.', '_' or '-'.")
    apk = args.apk.resolve()
    checksum = (args.checksum or Path(str(apk) + ".sha256")).resolve()
    assets = local_assets(apk, checksum)
    print(f"Local APK verified: {apk.name}; sha256={assets[0][1]}")
    if args.check_local:
        return
    if shutil.which("gh") is None:
        fail("TOOL", "GitHub CLI (gh) is required; no dependency was installed.")
    env = credential_env()
    _, remote = read_release(args.repo, args.tag, args.release_id, env)
    verified = {}
    # Validate all existing names before the first mutation, including checksum conflicts.
    for path, digest, size in assets:
        if path.name in remote:
            url = verify_asset(remote[path.name], args.repo, args.tag, path.name, digest, size)
            verified[path.name] = (remote[path.name]["id"], url)
    # Upload the small checksum first so upload authentication fails before the large APK.
    for path, digest, size in reversed(assets):
        if path.name in verified:
            print(f"Already present and identical; skipped {path.name}")
            continue
        print(f"Uploading {path.name} to {args.repo} / {args.tag} (Release {args.release_id})")
        gh(["release", "upload", args.tag, str(path), "--repo", args.repo], env)
        _, remote = read_release(args.repo, args.tag, args.release_id, env)
        if path.name not in remote:
            fail("STATE", "Upload returned success but the asset is missing. No automatic retry was attempted.")
        url = verify_asset(remote[path.name], args.repo, args.tag, path.name, digest, size)
        verified[path.name] = (remote[path.name]["id"], url)
    # Ensure no asset was replaced while this run was uploading or verifying another file.
    _, final_assets = read_release(args.repo, args.tag, args.release_id, env)
    if any(final_assets.get(name, {}).get("id") != entry[0] for name, entry in verified.items()):
        fail("CONFLICT", "A verified asset changed during delivery; inspect the Release.")
    print("Delivery complete. Public downloads verified:")
    for _, url in verified.values():
        print(url)


if __name__ == "__main__":
    try:
        main()
    except (DeliveryError, OSError) as error:
        if isinstance(error, DeliveryError):
            print(str(error), file=sys.stderr)
        else:
            print("LOCAL: Filesystem or tool execution failed; sensitive details suppressed.", file=sys.stderr)
        sys.exit(1)
    except KeyboardInterrupt:
        print("INTERRUPTED: Inspect Release assets before resuming; no automatic retry.", file=sys.stderr)
        sys.exit(130)
