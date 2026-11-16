#!/usr/bin/env python3
"""Publish a freshly built ChatGPT test APK from Actions and verify its public download."""

import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request


REPO = "0425-sketch/rikkahub"
PACKAGE = "me.rerere.rikkahub.chatgpt.debug"
VERSION_CODE = 198
VERSION_NAME = "3.2.0-chatgpt.1"
PREVIOUS_VERSION_CODE = 197
# Public certificate from the previously delivered APK. Never make this a caller override:
# publication must preserve the installed application's signing identity and its private data.
EXPECTED_UPGRADE_CERTIFICATE_SHA256 = "369f5d9de364514a92337b02efc50ce5c35bfb53753c325286c0efcaabc5b497"

# Reuse the reviewed HTTPS redirect restrictions and redacted CLI error handling.
# Its old credential_env() is deliberately never called: only Actions GITHUB_TOKEN is used.
spec = importlib.util.spec_from_file_location("existing_delivery", Path(__file__).with_name("upload-release-assets.py"))
delivery = importlib.util.module_from_spec(spec)
spec.loader.exec_module(delivery)


def inspect_apk(apk, build_tools):
    signature = subprocess.run(
        [str(build_tools / "apksigner"), "verify", "--verbose", "--print-certs", str(apk)],
        capture_output=True, text=True, check=True,
    ).stdout
    certificates = re.findall(r"certificate SHA-256 digest: ([a-f0-9]{64})", signature)
    if len(certificates) != 1:
        delivery.fail("SIGNATURE", "Expected exactly one verified APK signing certificate.")
    badging = subprocess.run(
        [str(build_tools / "aapt2"), "dump", "badging", str(apk)],
        capture_output=True, text=True, check=True,
    ).stdout
    identity = re.search(r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'", badging, re.M)
    if not identity or identity.groups() != (PACKAGE, str(VERSION_CODE), VERSION_NAME):
        delivery.fail("IDENTITY", "APK is not the expected ChatGPT update package/version.")
    if not re.search(r"^native-code: 'arm64-v8a'\s*$", badging, re.M):
        delivery.fail("ABI", "APK must contain exactly the arm64-v8a native architecture.")
    if not re.search(r"^minSdkVersion:'26'$", badging, re.M):
        delivery.fail("SDK", "Unexpected minimum Android SDK.")
    return {
        "application_id": PACKAGE,
        "version_code": VERSION_CODE,
        "version_name": VERSION_NAME,
        "min_sdk": 26,
        "architectures": ["arm64-v8a"],
        "certificate_sha256": certificates[0],
        "expected_upgrade_certificate_sha256": EXPECTED_UPGRADE_CERTIFICATE_SHA256,
        "upgrade_certificate_matches": certificates[0] == EXPECTED_UPGRADE_CERTIFICATE_SHA256,
        "previous_version_code": PREVIOUS_VERSION_CODE,
        "apk_sha256": delivery.sha256(apk),
        "apk_size": apk.stat().st_size,
    }


def ensure_upgrade_compatible(metadata):
    if metadata["certificate_sha256"] != EXPECTED_UPGRADE_CERTIFICATE_SHA256:
        delivery.fail(
            "UPGRADE_SIGNING",
            "APK build succeeded, but its signing certificate does not match the installed version. "
            "The original signing key is required; no Release or APK upload was created.",
        )
    if metadata["application_id"] != PACKAGE or metadata["version_code"] <= PREVIOUS_VERSION_CODE:
        delivery.fail("UPGRADE_IDENTITY", "An update must keep the package name and increase versionCode.")


def download_apk(asset, tag, output, expected):
    if asset.get("state") != "uploaded" or asset.get("size") != expected["apk_size"]:
        delivery.fail("INTEGRITY", "Published APK size/state differs from the build.")
    digest = asset.get("digest")
    if digest and digest != "sha256:" + expected["apk_sha256"]:
        delivery.fail("INTEGRITY", "Published APK digest differs from the build.")
    url = delivery.validate_download_url(asset.get("browser_download_url"), repo=REPO, tag=tag, name=output.name)
    opener = urllib.request.build_opener(delivery.GitHubRedirects())
    request = urllib.request.Request(url, headers={"User-Agent": "RikkaHub-ChatGPT-release-verifier"})
    total = 0
    try:
        with opener.open(request, timeout=120) as response, output.open("wb") as stream:
            if response.status != 200:
                delivery.fail("DOWNLOAD", "Public APK download did not return HTTP 200.")
            for chunk in iter(lambda: response.read(1024 * 1024), b""):
                total += len(chunk)
                if total > expected["apk_size"]:
                    delivery.fail("INTEGRITY", "Downloaded APK exceeds its expected size.")
                stream.write(chunk)
    except urllib.error.HTTPError as error:
        delivery.fail("DOWNLOAD", f"Public APK download returned HTTP {error.code}; no token was sent.")
    except (urllib.error.URLError, TimeoutError, OSError):
        delivery.fail("DOWNLOAD", "Public APK download failed; signed URL details are suppressed.")
    if total != expected["apk_size"] or delivery.sha256(output) != expected["apk_sha256"]:
        delivery.fail("INTEGRITY", "Public APK download differs from the verified build.")
    return url


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--build-tools", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--verify-only", action="store_true")
    args = parser.parse_args()
    metadata = inspect_apk(args.apk.resolve(), args.build_tools.resolve())
    source_sha = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    if not re.fullmatch(r"[0-9a-f]{40}", source_sha):
        delivery.fail("SOURCE", "Invalid source commit.")
    metadata["source_commit"] = source_sha
    args.output.mkdir(parents=True, exist_ok=True)
    # Keep a public diagnostic receipt even when publication is blocked by a missing original key.
    (args.output / "apk-verification.json").write_text(json.dumps(metadata, indent=2) + "\n")
    if args.verify_only:
        print(json.dumps(metadata, indent=2))
        return

    ensure_upgrade_compatible(metadata)
    if os.environ.get("GITHUB_ACTIONS") != "true" or os.environ.get("GITHUB_REPOSITORY") != REPO:
        delivery.fail("AUTH", "Publication runs only in this repository's GitHub Actions.")
    if os.environ.get("GITHUB_REF") != "refs/heads/custom-3.2.0" or os.environ.get("GITHUB_SHA") != source_sha:
        delivery.fail("SOURCE", "Checkout must be the exact custom-3.2.0 workflow commit.")
    run_id, attempt = os.environ["GITHUB_RUN_ID"], os.environ["GITHUB_RUN_ATTEMPT"]
    if not run_id.isdecimal() or not attempt.isdecimal():
        delivery.fail("SOURCE", "Invalid Actions run identity.")
    token = os.environ.get("GITHUB_TOKEN")
    if not token:
        delivery.fail("AUTH", "The workflow's automatic GITHUB_TOKEN is missing.")
    env = dict(os.environ, GH_TOKEN=token, GH_HOST="github.com", GH_PROMPT_DISABLED="1", GH_PAGER="cat")
    env.pop("GH_DEBUG", None)
    env.pop("DEBUG", None)
    tag = f"custom-3.2.0-chatgpt-{source_sha[:12]}-{run_id}-{attempt}"
    metadata["tag"] = tag
    metadata["actions_url"] = f"https://github.com/{REPO}/actions/runs/{run_id}"
    metadata["signing_persistence"] = "external-secret" if os.environ.get("CHATGPT_TEST_KEYSTORE") else "this-build-only"
    name = f"rikkahub-{VERSION_NAME}-{source_sha[:12]}-arm64.apk"
    apk = args.output / name
    shutil.copyfile(args.apk, apk)
    checksum = apk.with_name(apk.name + ".sha256")
    checksum.write_text(f"{metadata['apk_sha256']}  {apk.name}\n", encoding="ascii")
    provenance = args.output / "build-provenance.json"
    provenance.write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    notes = args.output / "release-notes.txt"
    notes.write_text(
        "RikkaHub 3.2.0 — ChatGPT 真机反馈修复测试版\n\n"
        f"源码： https://github.com/{REPO}/commit/{source_sha}\n"
        f"Actions： {metadata['actions_url']}\n\n"
        "ARM64，Android 8.0 及以上。已验证包名、原版证书和递增versionCode，可覆盖上一版ChatGPT自用测试包，保留其应用数据。\n"
        "已保留通过真机验证的官方登录和正常聊天；本次修复仍需手机复测工具设置及搜索。\n"
        "安装本更新不需要卸载旧版或重新配置提供商。\n\n"
        f"APK SHA-256: `{metadata['apk_sha256']}`\n"
        f"证书 SHA-256: `{metadata['certificate_sha256']}`\n",
        encoding="utf-8",
    )
    delivery.gh([
        "release", "create", tag, "--repo", REPO, "--target", source_sha,
        "--title", f"ChatGPT OAuth test {source_sha[:12]}", "--prerelease", "--latest=false",
        "--notes-file", str(notes),
    ], env)
    for path in (checksum, provenance, apk):
        delivery.gh(["release", "upload", tag, str(path), "--repo", REPO], env)
    release = delivery.api_json(f"repos/{REPO}/releases/tags/{tag}", env)
    if release.get("target_commitish") != source_sha or not release.get("prerelease") or release.get("draft"):
        delivery.fail("IDENTITY", "Published Release identity is not the requested test build.")
    assets = {asset["name"]: asset for asset in release["assets"]}
    for path in (checksum, provenance):
        delivery.verify_asset(assets[path.name], REPO, tag, path.name, delivery.sha256(path), path.stat().st_size)
    with tempfile.TemporaryDirectory(prefix="rikkahub-download-") as directory:
        downloaded = Path(directory) / apk.name
        url = download_apk(assets[apk.name], tag, downloaded, metadata)
        downloaded_metadata = inspect_apk(downloaded, args.build_tools.resolve())
        if any(downloaded_metadata[key] != value for key, value in metadata.items() if key in downloaded_metadata):
            delivery.fail("INTEGRITY", "Downloaded APK signing certificate or package metadata changed.")
    result = {"release_url": release["html_url"], "apk_url": url, **metadata, "public_download_verified": True}
    (args.output / "verified-delivery.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2))
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as summary:
            summary.write(f"Verified public APK: [{apk.name}]({url})\n\n")
            summary.write(f"Source `{source_sha}`; SHA-256 `{metadata['apk_sha256']}`; verified signing-compatible update.\n")


if __name__ == "__main__":
    try:
        main()
    except (delivery.DeliveryError, subprocess.SubprocessError, KeyError) as error:
        if isinstance(error, delivery.DeliveryError):
            print(str(error), file=sys.stderr)
        else:
            print("Delivery verification failed; raw subprocess output is suppressed.", file=sys.stderr)
        sys.exit(1)
