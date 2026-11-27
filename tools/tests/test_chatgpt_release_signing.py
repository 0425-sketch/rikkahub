"""No network or real signer: test publication's mandatory restoration boundary."""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("personal_release", Path(__file__).resolve().parents[1] / "personal-release.py")
release = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(release)
CERT = "c" * 64
SHA = "a" * 40


class PublishReached(Exception):
    pass


class ChatGPTReleaseSigningTest(unittest.TestCase):
    def test_release_identity_matches_android_output_and_build_config(self):
        source = (release.ROOT / "app/build.gradle.kts").read_text()
        self.assertIn('gradleProperty("personalVersionCode")', source)
        self.assertIn('gradleProperty("personalVersionName")', source)
        self.assertGreater(release.VERSION_CODE, 209)
        self.assertRegex(release.VERSION_NAME, r"\A[0-9]+\.[0-9]+\.[0-9]+-personal\.[1-9][0-9]*\Z")

    def test_inspection_rejects_unsigned_apk_before_publication(self):
        with tempfile.TemporaryDirectory() as tmp, \
             patch.object(release.subprocess, "run", side_effect=subprocess.CalledProcessError(1, ["apksigner"])), \
             patch.object(release.delivery, "gh") as gh:
            with self.assertRaises(subprocess.CalledProcessError):
                release.inspect_apk(Path(tmp) / "unsigned.apk", Path(tmp))
            gh.assert_not_called()

    def test_inspection_rejects_old_personal_version_with_valid_signature(self):
        signature = f"Signer certificate SHA-256 digest: {CERT}\n"
        badging = "package: name='me.rerere.rikkahub.personal' versionCode='204' versionName='3.3.0-personal.4'\n"
        with tempfile.TemporaryDirectory() as tmp, patch.object(release.subprocess, "run", side_effect=[
            subprocess.CompletedProcess([], 0, stdout=signature),
            subprocess.CompletedProcess([], 0, stdout=badging),
        ]):
            with self.assertRaisesRegex(release.delivery.DeliveryError, "IDENTITY"):
                release.inspect_apk(Path(tmp) / "previous.apk", Path(tmp))

    def metadata(self, certificate=CERT, **changes):
        return {"application_id": release.PACKAGE, "version_code": release.VERSION_CODE, "version_name": release.VERSION_NAME,
                "min_sdk": 26, "architectures": ["arm64-v8a"], "certificate_sha256": certificate,
                "fixed_certificate_matches": True, "apk_sha256": "0" * 64, "apk_size": 10, **changes}

    def receipt(self, role, **changes):
        return {"schema": 1, "source": role, "application_id": release.PACKAGE, "certificate_sha256": CERT,
                "source_commit": SHA, "run_id": "1", "run_attempt": "1", "private_key_verified": True, **changes}

    def jobs(self, same_runner=False):
        return {"jobs": [{"id": i + 1, "name": f"Restore and build ({role})", "runner_id": 10 if same_runner else 10 + i,
                          "status": "completed", "conclusion": "success"} for i, role in enumerate(("primary", "backup"))]}

    def invoke(self, directory, primary=None, backup=None, receipts=None, pin=CERT, verify_only=False):
        arguments = ["personal-release.py", "--apk", str(directory / "primary.apk"), "--build-tools", str(directory),
                     "--output", str(directory / "evidence")]
        if verify_only:
            arguments.append("--verify-only")
        else:
            arguments += ["--prepare", "--recovery-apk", str(directory / "backup.apk"),
                          "--primary-receipt", str(directory / "primary.json"), "--backup-receipt", str(directory / "backup.json")]
            for role in ("primary", "backup"):
                (directory / f"{role}.json").write_text(json.dumps((receipts or {}).get(role, self.receipt(role))))
        (directory / "primary.apk").write_bytes(b"candidate!")
        with patch.object(release, "inspect_apk", side_effect=[primary or self.metadata(), backup or self.metadata()]), \
             patch.object(release, "signing_identity", return_value={"schema": 1, "application_id": release.PACKAGE, "certificate_sha256": pin}), \
             patch.object(release.subprocess, "check_output", return_value=SHA + "\n"), \
             patch.object(release.sys, "argv", arguments), \
             patch.dict(os.environ, {"GITHUB_ACTIONS": "true", "GITHUB_REPOSITORY": release.REPO,
                 "GITHUB_REF_NAME": "custom-3.2.0", "GITHUB_SHA": SHA, "GITHUB_RUN_ID": "1", "GITHUB_RUN_ATTEMPT": "1",
                 "GITHUB_TOKEN": "synthetic-test-token-never-sent"}), contextlib.redirect_stdout(io.StringIO()):
            release.main()

    def test_unpinned_identity_cannot_publish(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(release.delivery, "gh") as gh:
            with self.assertRaisesRegex(release.delivery.DeliveryError, "SIGNING_NOT_READY"):
                self.invoke(Path(tmp), pin=None)
            gh.assert_not_called()

    def test_different_certificate_cannot_publish_even_with_forged_success_flag(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(release.delivery, "gh") as gh:
            with self.assertRaisesRegex(release.delivery.DeliveryError, "SIGNING_MISMATCH"):
                self.invoke(Path(tmp), primary=self.metadata(certificate="d" * 64))
            gh.assert_not_called()

    def test_backup_must_match_the_pinned_certificate(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(release.delivery, "gh") as gh:
            with self.assertRaisesRegex(release.delivery.DeliveryError, "SIGNING_MISMATCH"):
                self.invoke(Path(tmp), backup=self.metadata(certificate="d" * 64))
            gh.assert_not_called()

    def test_old_package_or_wrong_version_cannot_publish(self):
        for change in ({"application_id": "me.rerere.rikkahub.chatgpt.debug"}, {"version_code": release.VERSION_CODE - 1}):
            with tempfile.TemporaryDirectory() as tmp, patch.object(release.delivery, "gh") as gh:
                with self.assertRaisesRegex(release.delivery.DeliveryError, "SIGNING_IDENTITY"):
                    self.invoke(Path(tmp), primary=self.metadata(**change))
                gh.assert_not_called()

    def test_stale_or_unverified_or_wrong_source_receipt_cannot_publish(self):
        for change in ({"source_commit": "b" * 40}, {"run_attempt": "2"}, {"private_key_verified": False}, {"source": "primary"}):
            with tempfile.TemporaryDirectory() as tmp, patch.object(release.delivery, "gh") as gh:
                with self.assertRaisesRegex(release.delivery.DeliveryError, "RESTORATION"):
                    self.invoke(Path(tmp), receipts={"backup": self.receipt("backup", **change)})
                gh.assert_not_called()

    def test_same_runner_or_incomplete_build_cannot_publish(self):
        incomplete = self.jobs()
        incomplete["jobs"][1]["conclusion"] = "failure"
        for jobs in (self.jobs(same_runner=True), incomplete, {"jobs": self.jobs()["jobs"][:1]}):
            with tempfile.TemporaryDirectory() as tmp, patch.object(release.delivery, "api_json", return_value=jobs), patch.object(release.delivery, "gh") as gh:
                with self.assertRaisesRegex(release.delivery.DeliveryError, "RESTORATION"):
                    self.invoke(Path(tmp))
                gh.assert_not_called()

    def test_staging_produces_old_client_metadata_without_publishing(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(release.delivery, "api_json", return_value=self.jobs()), \
             patch.object(release.delivery, "gh") as gh:
            self.invoke(Path(tmp))
            gh.assert_not_called()
            evidence = Path(tmp) / "evidence"
            notes = (evidence / "release-notes.txt").read_text()
            self.assertIn(release.TITLE, notes)
            self.assertIn(release.BASELINE_SHA, notes)
            self.assertIn(f"<!-- rikkahub-personal-version-code: {release.VERSION_CODE} -->", notes)
            proof = json.loads((evidence / "build-provenance.json").read_text())
            self.assertEqual(proof["tag"], f"custom-{release.SERIES}-personal-{SHA[:12]}-1-1")
            self.assertTrue(proof["two_clean_restorations_verified"])
            self.assertEqual(len(list(evidence.glob(f"rikkahub-{release.VERSION_NAME}-{SHA[:12]}-arm64.apk"))), 1)

    def test_verification_only_requires_fixed_signer(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(release.delivery, "gh") as gh:
            with self.assertRaisesRegex(release.delivery.DeliveryError, "SIGNING_MISMATCH"):
                self.invoke(Path(tmp), primary=self.metadata(certificate="d" * 64), verify_only=True)
            gh.assert_not_called()

    def test_resume_accepts_only_matching_draft_assets(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "checksum"
            path.write_bytes(b"x")
            asset = {"name": path.name, "state": "uploaded", "size": 1, "digest": "sha256:" + release.delivery.sha256(path)}
            self.assertEqual(release.check_remote_assets({"assets": []}, [path], complete=False), {})
            for invalid in ([], [asset, asset], [dict(asset, digest="sha256:" + "f"*64)], [dict(asset, name="wrong")]):
                with self.assertRaisesRegex(release.delivery.DeliveryError, "CONFLICT"):
                    release.check_remote_assets({"assets": invalid}, [path], complete=True)
            self.assertEqual(release.check_remote_assets({"assets": [asset]}, [path], complete=True), {path.name: asset})

    def test_reserved_version_cannot_be_reused(self):
        other = {"tag_name": f"custom-3.3.0-personal-{SHA[:12]}-1-1", "draft": False,
                 "body": f"<!-- rikkahub-personal-version-code: {release.VERSION_CODE} -->"}
        with self.assertRaisesRegex(release.delivery.DeliveryError, "CONFLICT"), patch.object(release.delivery, "gh") as gh:
            release.check_new_version([other], "another-tag", {})
        gh.assert_not_called()



if __name__ == "__main__":
    unittest.main()
