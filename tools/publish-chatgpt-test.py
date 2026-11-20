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
PACKAGE = "me.rerere.rikkahub.personal"
VERSION_CODE = 205
VERSION_NAME = "3.3.0-personal.5"
IDENTITY_FILE = Path(__file__).resolve().parents[1] / "docs/chatgpt-personal-signing.json"


def signing_identity():
    try:
        identity = json.loads(IDENTITY_FILE.read_text())
    except (OSError, ValueError):
        delivery.fail("SIGNING_IDENTITY", "The public signing identity file is missing or invalid.")
    if identity.get("schema") != 1 or identity.get("application_id") != PACKAGE:
        delivery.fail("SIGNING_IDENTITY", "The pinned signing identity belongs to another application.")
    certificate = identity.get("certificate_sha256")
    if certificate is not None and not re.fullmatch(r"[a-f0-9]{64}", certificate):
        delivery.fail("SIGNING_IDENTITY", "The pinned certificate digest is invalid.")
    return identity


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
        "expected_certificate_sha256": signing_identity()["certificate_sha256"],
        "fixed_certificate_matches": certificates[0] == signing_identity()["certificate_sha256"],
        "apk_sha256": delivery.sha256(apk),
        "apk_size": apk.stat().st_size,
    }


def ensure_fixed_signer(metadata):
    expected = signing_identity()["certificate_sha256"]
    if expected is None:
        delivery.fail("SIGNING_NOT_READY", "Durable signing has not been bootstrapped and pinned; publication is disabled.")
    if metadata.get("certificate_sha256") != expected:
        delivery.fail("SIGNING_MISMATCH", "APK certificate differs from the pinned personal signer; no Release was created.")
    if metadata.get("application_id") != PACKAGE or metadata.get("version_code") != VERSION_CODE:
        delivery.fail("SIGNING_IDENTITY", "The APK package or version differs from the personal release identity.")


def verify_restoration_pair(primary, backup, receipts, source_sha, run_id, attempt):
    for role, metadata in (("primary", primary), ("backup", backup)):
        ensure_fixed_signer(metadata)
        receipt = receipts.get(role, {})
        expected = {
            "schema": 1, "source": role, "application_id": PACKAGE,
            "certificate_sha256": metadata["certificate_sha256"],
            "source_commit": source_sha, "run_id": run_id, "run_attempt": attempt,
            "private_key_verified": True,
        }
        if any(receipt.get(key) != value for key, value in expected.items()):
            delivery.fail("RESTORATION", f"The {role} restoration receipt is missing, stale, or does not prove private-key recovery.")
    for field in ("application_id", "version_code", "version_name", "certificate_sha256", "architectures", "min_sdk"):
        if primary.get(field) != backup.get(field):
            delivery.fail("RESTORATION", "The two clean builds do not share the same package, version and signing certificate.")


def verify_clean_jobs(payload):
    jobs = payload.get("jobs", [])
    selected = []
    for role in ("primary", "backup"):
        matches = [job for job in jobs if job.get("name") == f"Restore and build ({role})"]
        if len(matches) != 1 or matches[0].get("status") != "completed" or matches[0].get("conclusion") != "success":
            delivery.fail("RESTORATION", "Both independent restoration build jobs must finish successfully before publishing.")
        selected.append(matches[0])
    if not all(job.get("id") and job.get("runner_id") for job in selected) or \
            selected[0]["id"] == selected[1]["id"] or selected[0]["runner_id"] == selected[1]["runner_id"]:
        delivery.fail("RESTORATION", "The primary and backup builds must run as distinct jobs on distinct runners.")
    return [{key: job[key] for key in ("id", "name", "runner_id", "conclusion")} for job in selected]


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
    parser.add_argument("--recovery-apk", type=Path)
    parser.add_argument("--primary-receipt", type=Path)
    parser.add_argument("--backup-receipt", type=Path)
    args = parser.parse_args()
    metadata = inspect_apk(args.apk.resolve(), args.build_tools.resolve())
    source_sha = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    if not re.fullmatch(r"[0-9a-f]{40}", source_sha):
        delivery.fail("SOURCE", "Invalid source commit.")
    metadata["source_commit"] = source_sha
    args.output.mkdir(parents=True, exist_ok=True)
    # Keep public metadata even when the durable personal signer is not ready.
    (args.output / "apk-verification.json").write_text(json.dumps(metadata, indent=2) + "\n")
    if args.verify_only:
        print(json.dumps(metadata, indent=2))
        return

    ensure_fixed_signer(metadata)
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
    if not all((args.recovery_apk, args.primary_receipt, args.backup_receipt)):
        delivery.fail("RESTORATION", "The independently restored backup APK and both restoration receipts are required.")
    try:
        receipts = {
            "primary": json.loads(args.primary_receipt.read_text()),
            "backup": json.loads(args.backup_receipt.read_text()),
        }
    except (OSError, ValueError):
        delivery.fail("RESTORATION", "Cannot read the public restoration receipts.")
    backup_metadata = inspect_apk(args.recovery_apk.resolve(), args.build_tools.resolve())
    verify_restoration_pair(metadata, backup_metadata, receipts, source_sha, run_id, attempt)
    jobs = verify_clean_jobs(delivery.api_json(f"repos/{REPO}/actions/runs/{run_id}/attempts/{attempt}/jobs?per_page=100", env))
    restoration = {
        "schema": 1, "source_commit": source_sha, "application_id": PACKAGE,
        "certificate_sha256": metadata["certificate_sha256"], "clean_builds": jobs,
        "signing_storage": "GitHub Actions encrypted repository secrets",
        "backup_scope": "separate logical recovery copy in the same repository vault",
        "restoration_verified": True,
    }
    restoration_file = args.output / "signing-restoration-verification.json"
    restoration_file.write_text(json.dumps(restoration, indent=2) + "\n")
    tag = f"custom-{VERSION_NAME.split('-personal.')[0]}-personal-{source_sha[:12]}-{run_id}-{attempt}"
    metadata["tag"] = tag
    metadata["actions_url"] = f"https://github.com/{REPO}/actions/runs/{run_id}"
    metadata["signing_persistence"] = "durable-primary-and-backup-secrets"
    metadata["two_clean_restorations_verified"] = True
    name = f"rikkahub-{VERSION_NAME}-{source_sha[:12]}-arm64.apk"
    apk = args.output / name
    shutil.copyfile(args.apk, apk)
    checksum = apk.with_name(apk.name + ".sha256")
    checksum.write_text(f"{metadata['apk_sha256']}  {apk.name}\n", encoding="ascii")
    provenance = args.output / "build-provenance.json"
    provenance.write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    notes = args.output / "release-notes.txt"
    notes.write_text(
        f"RikkaHub {VERSION_NAME}\n\n"
        "图片保存完成后，当前卡片会结束计时并显示成图。保持在同一版本即可看到结果。\n"
        "OpenAI Platform 图像支持单张流式预览，选择多张时会关闭流式。完成后可查看实际收到的预览数。SVG 编写模型可选用已在自用项目验证的 gpt-6.2-sol。\n\n"
        "已安装自用版 204 的用户可从聊天侧栏下载本次更新，再按 Android 提示覆盖安装。\n\n"
        f"[构建源码](https://github.com/{REPO}/commit/{source_sha}) · [构建记录]({metadata['actions_url']})\n\n"
        f"APK SHA-256 `{metadata['apk_sha256']}`\n\n"
        f"<!-- rikkahub-personal-version-code: {VERSION_CODE} -->\n",
        encoding="utf-8",
    )
    delivery.gh([
        "release", "create", tag, "--repo", REPO, "--target", source_sha,
        "--title", f"RikkaHub Personal {VERSION_NAME} {source_sha[:12]}", "--prerelease", "--latest=false",
        "--notes-file", str(notes),
    ], env)
    for path in (checksum, provenance, restoration_file, apk):
        delivery.gh(["release", "upload", tag, str(path), "--repo", REPO], env)
    release = delivery.api_json(f"repos/{REPO}/releases/tags/{tag}", env)
    if release.get("target_commitish") != source_sha or not release.get("prerelease") or release.get("draft"):
        delivery.fail("IDENTITY", "Published Release identity is not the requested test build.")
    assets = {asset["name"]: asset for asset in release["assets"]}
    for path in (checksum, provenance, restoration_file):
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
            summary.write(f"Source `{source_sha}`; SHA-256 `{metadata['apk_sha256']}`; two clean signing restorations verified for the personal package.\n")


if __name__ == "__main__":
    try:
        main()
    except (delivery.DeliveryError, subprocess.SubprocessError, KeyError, OSError, ValueError) as error:
        if isinstance(error, delivery.DeliveryError):
            print(str(error), file=sys.stderr)
        else:
            print("Delivery verification failed; raw subprocess output is suppressed.", file=sys.stderr)
        sys.exit(1)
