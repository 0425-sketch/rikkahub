"""Verify the GitHub Release dependency report, merged manifest and packaged Android runtime."""

import argparse
from pathlib import Path
import re
import subprocess
from xml.etree import ElementTree
from zipfile import ZipFile


DEPENDENCY = re.compile(rb"com\.google\.firebase:|com\.google\.android\.gms:play-services-measurement")
COMPONENT = re.compile(rb"com\.google\.(?:firebase\.|android\.gms\.(?:internal\.)?measurement\.)|firebase_analytics|firebase_crashlytics")
RESOURCE = re.compile(rb"\bstring/(?:google_app_id|gcm_defaultSenderId|firebase_database_url|google_crash_reporting_api_key|google_storage_bucket|default_web_client_id)\b")
DEX_TYPE = re.compile(rb"Lcom/google/(?:firebase/|android/gms/(?:internal/)?measurement/)")
SDK_FILE = re.compile(rb"(?:firebase[-_.]|crashlytics|play-services-measurement)", re.I)
PACKAGE = "me.rerere.rikkahub.personal"
ANDROID = "{http://schemas.android.com/apk/res/android}"


def reject(data, pattern, location):
    match = pattern.search(data)
    if match:
        raise SystemExit(f"{location} contains {match.group().decode()}")


def check_manifest(data, location):
    reject(data, COMPONENT, location)
    root = ElementTree.fromstring(data)
    app = root.find("application")
    if root.get("package") != PACKAGE or app is None or app.get(ANDROID + "debuggable", "false") != "false":
        raise SystemExit(f"{location}: expected a non-debuggable personal application")
    permissions = {node.get(ANDROID + "name") for node in root.findall("uses-permission")}
    for permission in ("android.permission.REQUEST_INSTALL_PACKAGES", "android.permission.MANAGE_EXTERNAL_STORAGE"):
        if permission not in permissions:
            raise SystemExit(f"{location}: missing {permission}")
    providers = {node.get(ANDROID + "name"): node for node in app.findall("provider")}
    for name, suffix, exported in (("androidx.core.content.FileProvider", "fileprovider", "false"),
                                    ("me.rerere.rikkahub.service.WorkspaceDocumentsProvider", "documents", "true")):
        node = providers.get(name)
        if (node is None or node.get(ANDROID + "authorities") != f"{PACKAGE}.{suffix}"
                or node.get(ANDROID + "exported") != exported or node.get(ANDROID + "grantUriPermissions") != "true"):
            raise SystemExit(f"{location}: incorrect provider configuration for {name}")
    if not any(s.get(ANDROID + "name") == "me.rerere.rikkahub.phoneuse.PhoneUseAccessibilityService"
               for s in app.findall("service")):
        raise SystemExit(f"{location}: missing GitHub accessibility service")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime-dependencies", type=Path, required=True)
    parser.add_argument("--merged-manifest", type=Path, required=True)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--build-tools", type=Path, required=True)
    args = parser.parse_args()

    dependencies = args.runtime_dependencies.read_bytes()
    if not re.search(rb"^githubReleaseRuntimeClasspath\b", dependencies, re.M) or b"androidx.core:core" not in dependencies:
        raise SystemExit("Missing resolved githubReleaseRuntimeClasspath report")
    reject(dependencies, re.compile(rb"\bFAILED\b"), args.runtime_dependencies)
    reject(dependencies, DEPENDENCY, args.runtime_dependencies)
    check_manifest(args.merged_manifest.read_bytes(), args.merged_manifest)
    manifest = subprocess.check_output(["apkanalyzer", "manifest", "print", args.apk])
    check_manifest(manifest, "APK manifest")
    aapt = args.build_tools / "aapt2"
    badging = subprocess.check_output([aapt, "dump", "badging", args.apk]).decode().splitlines()
    if "application-label:'RikkaHub Personal'" not in badging or "application-label-zh:'RikkaHub 自用'" not in badging:
        raise SystemExit("APK display name does not match the installed personal application")
    resources = subprocess.check_output([aapt, "dump", "resources", "--no-values", args.apk])
    reject(resources, RESOURCE, "APK resource table")
    with ZipFile(args.apk) as apk:
        if "classes.dex" not in apk.namelist():
            raise SystemExit("APK contains no classes.dex")
        found_r8 = False
        for name in apk.namelist():
            reject(name.encode(), SDK_FILE, "APK entries")
            if re.fullmatch(r"classes(?:\d+)?\.dex", name):
                dex = apk.read(name)
                reject(dex, DEX_TYPE, name)
                found_r8 |= b"~~R8{" in dex
        if not found_r8:
            raise SystemExit("No R8 compiler marker in APK DEX")
    print("Checked githubReleaseRuntimeClasspath, installed identity, display name, permissions, providers, resources and R8 DEX.")


if __name__ == "__main__":
    main()
