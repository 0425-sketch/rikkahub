#!/usr/bin/env python3
"""Prepare a signed personal Release, or promote the exact tested candidate."""

import argparse
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import time
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request


REPO = "0425-sketch/rikkahub"
PACKAGE = "me.rerere.rikkahub.personal"
ROOT = Path(__file__).resolve().parents[1]
PROPERTIES = dict(line.strip().split("=", 1) for line in (ROOT / "gradle.properties").read_text().splitlines()
                  if "=" in line and not line.lstrip().startswith("#"))
VERSION_CODE = int(PROPERTIES["personalVersionCode"])
VERSION_NAME = PROPERTIES["personalVersionName"]
SERIES, SERIAL = VERSION_NAME.split("-personal.")
BASELINE_SHA = "590dca0ef8ca2ea3b6d9bd45d345717e5294b013"
BASELINE_CODE = 209
RELEASE_NUMBER = VERSION_CODE - BASELINE_CODE
TITLE = "RikkaHub Release 209（Release 1）" if RELEASE_NUMBER == 1 else f"RikkaHub Release {RELEASE_NUMBER}"
BRANCH = "custom-3.2.0"
PERSONAL_TAG = re.compile(r"custom-\d+\.\d+\.\d+-personal-[a-f0-9]{12}-\d+-\d+")
VERSION_HINT = re.compile(r"^<!-- rikkahub-personal-version-code: ([1-9]\d*) -->\r?$", re.M)
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
spec = importlib.util.spec_from_file_location("existing_delivery", Path(__file__).with_name("release_delivery.py"))
delivery = importlib.util.module_from_spec(spec)
spec.loader.exec_module(delivery)


def inspect_apk(apk, build_tools, *, successor=False):
    version_code = VERSION_CODE + int(successor)
    version_name = f"{SERIES}-personal.{int(SERIAL) + 1}" if successor else VERSION_NAME
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
    if not identity or identity.groups() != (PACKAGE, str(version_code), version_name):
        delivery.fail("IDENTITY", "APK is not the expected personal Release package/version.")
    if re.search(r"^application-debuggable", badging, re.M):
        delivery.fail("DEBUGGABLE", "The personal Release APK permits debugging.")
    if not re.search(r"^native-code: 'arm64-v8a'\s*$", badging, re.M):
        delivery.fail("ABI", "APK must contain exactly the arm64-v8a native architecture.")
    if not re.search(r"^minSdkVersion:'26'$", badging, re.M):
        delivery.fail("SDK", "Unexpected minimum Android SDK.")
    return {
        "application_id": PACKAGE,
        "version_code": version_code,
        "version_name": version_name,
        "build_variant": "githubRelease",
        "min_sdk": 26,
        "architectures": ["arm64-v8a"],
        "certificate_sha256": certificates[0],
        "expected_certificate_sha256": signing_identity()["certificate_sha256"],
        "fixed_certificate_matches": certificates[0] == signing_identity()["certificate_sha256"],
        "apk_sha256": delivery.sha256(apk),
        "apk_size": apk.stat().st_size,
    }


def ensure_fixed_signer(metadata, *, successor=False):
    expected = signing_identity()["certificate_sha256"]
    if expected is None:
        delivery.fail("SIGNING_NOT_READY", "Durable signing has not been bootstrapped and pinned; publication is disabled.")
    if metadata.get("certificate_sha256") != expected:
        delivery.fail("SIGNING_MISMATCH", "APK certificate differs from the pinned personal signer; no Release was created.")
    if metadata.get("application_id") != PACKAGE or metadata.get("version_code") != VERSION_CODE + int(successor):
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



def repository_releases(env):
    releases = []
    page = 1
    while True:
        entries = delivery.api_json(f"repos/{REPO}/releases?per_page=100&page={page}", env)
        releases.extend(entries)
        if len(entries) < 100:
            return releases
        page += 1


def find_release(releases, tag):
    matches = [r for r in releases if r["tag_name"] == tag]
    if len(matches) > 1:
        delivery.fail("CONFLICT", f"Multiple releases use {tag}.")
    return matches[0] if matches else None


def check_new_version(releases, tag, env):
    for release in releases:
        other = release["tag_name"]
        if other == tag or release["draft"] or not PERSONAL_TAG.fullmatch(other):
            continue
        hints = VERSION_HINT.findall(release.get("body") or "")
        # Early personal releases had no version hint. Inspect their public proof.
        if len(hints) == 1 and int(hints[0]) >= VERSION_CODE:
            delivery.fail("CONFLICT", f"Version code {hints[0]} is already published at {other}.")
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "build-provenance.json"
            delivery.gh(["release", "download", other, "-R", REPO, "-p", path.name, "-O", str(path)], env)
            proof = json.loads(path.read_text())
        if proof.get("application_id") == PACKAGE and (
                int(proof["version_code"]) >= VERSION_CODE or proof["version_name"] == VERSION_NAME):
            delivery.fail("CONFLICT", f"A published personal version at {other} conflicts with this candidate.")


def check_remote_assets(release, paths, *, complete):
    remote = {asset["name"]: asset for asset in release["assets"]}
    expected = {path.name: path for path in paths}
    if len(remote) != len(release["assets"]) or remote.keys() - expected.keys() or (complete and remote.keys() != expected.keys()):
        delivery.fail("CONFLICT", "The Release asset set differs from the tested release.")
    for name, asset in remote.items():
        path = expected[name]
        if (asset.get("state") != "uploaded" or asset.get("digest") != "sha256:" + delivery.sha256(path)
                or asset.get("size") != path.stat().st_size):
            delivery.fail("CONFLICT", f"Existing {name} differs from the tested candidate.")
    return remote


def retry_read(operation):
    for attempt in range(4):
        try:
            return operation()
        except delivery.DeliveryError as error:
            if attempt == 3 or not str(error).startswith(("DOWNLOAD:", "NETWORK:")):
                raise
            time.sleep(2 ** attempt)


def publish(tag, source, notes, paths, env):
    # Reconcile against the actual draft after each attempt. Never replace an asset.
    for attempt in range(3):
        releases = repository_releases(env)
        existing = find_release(releases, tag)
        check_new_version(releases, tag, env)
        if existing:
            if (existing["target_commitish"] != source or existing["name"] != TITLE or existing["prerelease"]
                    or existing["body"].replace("\r\n", "\n").rstrip() != notes.read_text().rstrip()):
                delivery.fail("CONFLICT", "An existing release has different source, title, notes or release type.")
            remote = check_remote_assets(existing, paths, complete=not existing["draft"])
            if not existing["draft"]:
                break
        else:
            # Refuse an unrelated pre-existing Git tag even when there is no release.
            ref = delivery.api_json(f"repos/{REPO}/git/matching-refs/tags/{tag}", env)
            if any(item["ref"] == f"refs/tags/{tag}" for item in ref):
                delivery.fail("CONFLICT", "A tag already exists without the expected Release.")
        try:
            if not existing:
                delivery.gh(["release", "create", tag, "-R", REPO, "--target", source, "--title", TITLE,
                             "--draft", "--latest=false", "--notes-file", str(notes)], env)
                remote = {}
            for path in paths:
                if path.name not in remote:
                    delivery.gh(["release", "upload", tag, "-R", REPO, str(path)], env)
            draft = find_release(repository_releases(env), tag)
            if not draft or not draft["draft"] or draft["target_commitish"] != source:
                delivery.fail("CONFLICT", "Draft identity changed before publication.")
            check_remote_assets(draft, paths, complete=True)
            # Publish only when all four expected hashes are on GitHub.
            delivery.gh(["api", "-X", "PATCH", f"repos/{REPO}/releases/{draft['id']}",
                         "-F", "draft=false", "-F", "prerelease=false", "-f", "make_latest=true"], env)
            break
        except delivery.DeliveryError as error:
            if attempt == 2 or not str(error).startswith(("NETWORK:", "CONFLICT:", "TOOL:")):
                raise
            time.sleep(2 ** attempt)
    release = delivery.api_json(f"repos/{REPO}/releases/tags/{tag}", env)
    latest = delivery.api_json(f"repos/{REPO}/releases/latest", env)
    ref = delivery.api_json(f"repos/{REPO}/git/ref/tags/{tag}", env)
    if (release["target_commitish"] != source or release["prerelease"] or release["draft"] or release["name"] != TITLE
            or release["id"] != latest["id"] or (ref["object"]["type"], ref["object"]["sha"]) != ("commit", source)):
        delivery.fail("IDENTITY", "Published tag, source, title, stable status or Latest designation is wrong.")
    return release, check_remote_assets(release, paths, complete=True)


def stage_notes(metadata, apk_name):
    source, tag = metadata["source_commit"], metadata["tag"]
    if RELEASE_NUMBER == 1:
        summary = "这是以 Debug 209 为功能基线的第一次正式发布。已安装 Debug 209 的用户请在聊天侧栏检查更新，下载后按 Android 提示覆盖安装。聊天、设置和本地统计沿用原安装的数据。今后此渠道只发布正式 Release。"
    else:
        summary = "在聊天侧栏检查更新，下载后按 Android 提示覆盖安装。"
    return (f"{TITLE}\n\nRikkaHub 非官方个人维护版。{summary}\n\n"
            f"[新用户下载 ARM64 APK](https://github.com/{REPO}/releases/download/{tag}/{apk_name})\n\n"
            f"安装版本 `{VERSION_NAME}`（versionCode {VERSION_CODE}）\n\n"
            f"[Debug 209 基线 `{BASELINE_SHA}`](https://github.com/{REPO}/commit/{BASELINE_SHA})\n\n"
            f"[本次构建提交 `{source}`](https://github.com/{REPO}/commit/{source}) · [构建记录]({metadata['actions_url']})\n\n"
            f"APK SHA-256 `{metadata['apk_sha256']}`\n\n<!-- rikkahub-personal-version-code: {VERSION_CODE} -->\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--verify-only", action="store_true")
    mode.add_argument("--prepare", action="store_true")
    mode.add_argument("--promote", action="store_true")
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--build-tools", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--successor", action="store_true", help="Verify the unpublished upgrade-test version")
    parser.add_argument("--recovery-apk", type=Path)
    parser.add_argument("--primary-receipt", type=Path)
    parser.add_argument("--backup-receipt", type=Path)
    parser.add_argument("--stage-run")
    parser.add_argument("--stage-attempt")
    parser.add_argument("--tested-sha256")
    parser.add_argument("--staged-files", type=Path, help="Prepared checksum, proof, restoration proof and notes")
    args = parser.parse_args()
    if args.successor and not args.verify_only:
        parser.error("The successor is for local upgrade tests only.")
    if not re.fullmatch(r"(\d+\.){2}\d+-personal\.[1-9]\d*", VERSION_NAME) or VERSION_CODE <= BASELINE_CODE:
        delivery.fail("VERSION", "Invalid personal Release version.")
    metadata = inspect_apk(args.apk.resolve(), args.build_tools.resolve(), successor=args.successor)
    source_sha = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    if not re.fullmatch(r"[0-9a-f]{40}", source_sha):
        delivery.fail("SOURCE", "Invalid source commit.")
    args.output.mkdir(parents=True, exist_ok=True)
    metadata["source_commit"] = source_sha
    ensure_fixed_signer(metadata, successor=args.successor)
    if args.verify_only:
        (args.output / "apk-verification.json").write_text(json.dumps(metadata, indent=2) + "\n")
        return

    if os.environ.get("GITHUB_ACTIONS") != "true" or os.environ.get("GITHUB_REPOSITORY") != REPO:
        delivery.fail("AUTH", "The release workflow must run in the personal repository.")
    if (os.environ.get("GITHUB_SHA") != source_sha or os.environ.get("GITHUB_REF") != f"refs/heads/{BRANCH}"
            or os.environ.get("GITHUB_EVENT_NAME") != "workflow_dispatch"):
        delivery.fail("SOURCE", "Run the workflow manually on the default branch at the exact build commit.")
    run_id, attempt = os.environ["GITHUB_RUN_ID"], os.environ["GITHUB_RUN_ATTEMPT"]
    if args.promote:
        if args.tested_sha256 != metadata["apk_sha256"] or args.staged_files is None:
            delivery.fail("ACCEPTANCE", "Promotion must name the tested candidate digest on the default branch.")
        run_id, attempt = args.stage_run, args.stage_attempt
    if not run_id or not attempt or not re.fullmatch("[1-9][0-9]*", run_id) or not re.fullmatch("[1-9][0-9]*", attempt):
        delivery.fail("SOURCE", "Invalid staging run or attempt.")
    env = dict(os.environ, GH_TOKEN=os.environ["GITHUB_TOKEN"], GH_HOST="github.com", GH_PROMPT_DISABLED="1", GH_PAGER="cat")
    env.pop("GH_DEBUG", None)
    env.pop("DEBUG", None)
    if args.promote:
        stage = delivery.api_json(f"repos/{REPO}/actions/runs/{run_id}/attempts/{attempt}", env)
        if (stage.get("id") != int(run_id) or stage.get("run_attempt") != int(attempt) or stage.get("head_sha") != source_sha
                or stage.get("head_branch") != BRANCH or stage.get("event") != "workflow_dispatch" or stage.get("conclusion") != "success"
                or stage.get("path") != ".github/workflows/chatgpt-test-apk.yml"):
            delivery.fail("SOURCE", "Staging run must have succeeded for this exact build commit and workflow.")
    if not all((args.recovery_apk, args.primary_receipt, args.backup_receipt)):
        delivery.fail("RESTORATION", "Both actual APKs and both public recovery receipts are required.")
    receipts = {"primary": json.loads(args.primary_receipt.read_text()), "backup": json.loads(args.backup_receipt.read_text())}
    backup = inspect_apk(args.recovery_apk.resolve(), args.build_tools.resolve())
    verify_restoration_pair(metadata, backup, receipts, source_sha, run_id, attempt)
    jobs = verify_clean_jobs(delivery.api_json(f"repos/{REPO}/actions/runs/{run_id}/attempts/{attempt}/jobs?per_page=100", env))
    restoration_file = args.output / "signing-restoration-verification.json"
    restoration = {
        "schema": 1, "source_commit": source_sha, "application_id": PACKAGE, "certificate_sha256": metadata["certificate_sha256"],
        "clean_builds": jobs, "signing_storage": "GitHub Actions encrypted repository secrets",
        "backup_scope": "separate logical recovery copy in the same repository vault", "restoration_verified": True,
    }
    restoration_file.write_text(json.dumps(restoration, indent=2) + "\n")
    tag = f"custom-{SERIES}-personal-{source_sha[:12]}-{run_id}-{attempt}"
    metadata.update(tag=tag, actions_url=f"https://github.com/{REPO}/actions/runs/{run_id}",
                    signing_persistence="durable-primary-and-backup-secrets", two_clean_restorations_verified=True)
    apk = args.output / f"rikkahub-{VERSION_NAME}-{source_sha[:12]}-arm64.apk"
    shutil.copyfile(args.apk, apk)
    checksum = apk.with_name(apk.name + ".sha256")
    checksum.write_text(f"{metadata['apk_sha256']}  {apk.name}\n", encoding="ascii")
    provenance = args.output / "build-provenance.json"
    provenance.write_text(json.dumps(metadata, indent=2) + "\n")
    notes = args.output / "release-notes.txt"
    notes.write_text(stage_notes(metadata, apk.name), encoding="utf-8")
    if args.prepare:
        return
    for path in (notes, checksum, provenance, restoration_file):
        if path.read_bytes() != (args.staged_files / path.name).read_bytes():
            delivery.fail("ACCEPTANCE", f"{path.name} differs from the staged release.")
    release, assets = publish(tag, source_sha, notes, (checksum, provenance, restoration_file, apk), env)
    for path in (checksum, provenance, restoration_file):
        retry_read(lambda: delivery.verify_asset(assets[path.name], REPO, tag, path.name, delivery.sha256(path), path.stat().st_size))
    with tempfile.TemporaryDirectory(prefix="rikkahub-download-") as directory:
        downloaded = Path(directory) / apk.name
        url = retry_read(lambda: download_apk(assets[apk.name], tag, downloaded, metadata))
        downloaded_metadata = inspect_apk(downloaded, args.build_tools.resolve())
        if any(downloaded_metadata[key] != metadata[key] for key in downloaded_metadata):
            delivery.fail("INTEGRITY", "Downloaded APK metadata changed.")
    result = {"release_url": release["html_url"], "apk_url": url, **metadata, "public_download_verified": True}
    (args.output / "verified-delivery.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    try:
        main()
    except (delivery.DeliveryError, subprocess.SubprocessError, KeyError, OSError, ValueError) as error:
        print(str(error) if isinstance(error, delivery.DeliveryError) else "Release verification failed; sensitive subprocess output is suppressed.", file=sys.stderr)
        sys.exit(1)
