#!/usr/bin/env python3
"""Shared GitHub release integrity checks and redacted CLI errors."""

from pathlib import Path
import os
import re
import sys
import time
import hashlib
import json
import ssl
import subprocess
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


def stage_draft(paths, env):
    """Upload to the run's collaborator-only draft and resume matching partial uploads."""
    repo = "0425-sketch/rikkahub"
    run, attempt, source = (env[key] for key in ("GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT", "GITHUB_SHA"))
    if (env.get("GITHUB_ACTIONS") != "true" or env.get("GITHUB_REPOSITORY") != repo
            or env.get("GITHUB_REF") != "refs/heads/custom-3.2.0" or env.get("GITHUB_EVENT_NAME") != "workflow_dispatch"
            or not run.isdecimal() or not attempt.isdecimal() or not re.fullmatch(r"[0-9a-f]{40}", source)):
        fail("SOURCE", "Staging is restricted to the authorized workflow and exact build commit.")
    tag = f"qa-personal-{run}-{attempt}"
    title = f"Private Android QA · {run}/{attempt}"
    for retry in range(3):
        try:
            if not paths:
                try:
                    gh(["release", "create", tag, "-R", repo, "--draft", "--latest=false", "--target", source,
                        "--title", title, "--notes", "Unpublished test APKs. Promote the tested primary APK through the Personal Release workflow."], env)
                except DeliveryError as error:
                    if not str(error).startswith("CONFLICT:"):
                        raise
            info = json.loads(gh(["release", "view", tag, "-R", repo, "--json", "apiUrl,isDraft,targetCommitish,name"], env))
            if not info["isDraft"] or info["targetCommitish"] != source or info["name"] != title:
                fail("IDENTITY", "The run's private draft identity changed.")
            match = re.fullmatch(rf"https://api.github.com/(repos/{repo}/releases/[1-9]\d*)", info["apiUrl"])
            if not match:
                fail("IDENTITY", "Unexpected draft Release API URL.")
            release = api_json(match[1], env)
            if not release["draft"] or release["tag_name"] != tag or release["target_commitish"] != source:
                fail("IDENTITY", "Draft Release identity changed before upload.")
            remote = {asset["name"]: asset for asset in release["assets"]}
            for path in paths:
                asset = remote.get(path.name)
                if asset:
                    if (asset["state"] != "uploaded" or asset.get("digest") != "sha256:" + sha256(path)
                            or asset["size"] != path.stat().st_size):
                        fail("INTEGRITY", f"Different {path.name} is already staged.")
                else:
                    gh(["release", "upload", tag, "-R", repo, str(path)], env)
            return
        except DeliveryError as error:
            if retry == 2 or not str(error).startswith(("NETWORK:", "CONFLICT:", "TOOL:")):
                raise
            time.sleep(2 ** retry)


if __name__ == "__main__":
    try:
        stage_draft([Path(name) for name in sys.argv[1:]], os.environ)
    except DeliveryError as error:
        sys.exit(str(error))
