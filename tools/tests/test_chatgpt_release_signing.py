"""No network or real signer: test publication's mandatory restoration boundary."""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("personal_release", Path(__file__).resolve().parents[1] / "publish-chatgpt-test.py")
release = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(release)
CERT = "c" * 64
SHA = "a" * 40


class PublishReached(Exception):
    pass


class ChatGPTReleaseSigningTest(unittest.TestCase):
    def metadata(self, certificate=CERT, **changes):
        return {"application_id": release.PACKAGE, "version_code": 199, "version_name": release.VERSION_NAME,
                "min_sdk": 26, "architectures": ["arm64-v8a"], "certificate_sha256": certificate,
                "fixed_certificate_matches": True, "apk_sha256": "0" * 64, "apk_size": 10, **changes}

    def receipt(self, role, **changes):
        return {"schema": 1, "source": role, "application_id": release.PACKAGE, "certificate_sha256": CERT,
                "source_commit": SHA, "run_id": "1", "run_attempt": "1", "private_key_verified": True, **changes}

    def jobs(self, same_runner=False):
        return {"jobs": [{"id": i + 1, "name": f"Restore and build ({role})", "runner_id": 10 if same_runner else 10 + i,
                          "status": "completed", "conclusion": "success"} for i, role in enumerate(("primary", "backup"))]}

    def invoke(self, directory, primary=None, backup=None, receipts=None, pin=CERT, verify_only=False):
        arguments = ["publish-chatgpt-test.py", "--apk", str(directory / "primary.apk"), "--build-tools", str(directory),
                     "--output", str(directory / "evidence")]
        if verify_only:
            arguments.append("--verify-only")
        else:
            arguments += ["--recovery-apk", str(directory / "backup.apk"),
                          "--primary-receipt", str(directory / "primary.json"), "--backup-receipt", str(directory / "backup.json")]
            for role in ("primary", "backup"):
                (directory / f"{role}.json").write_text(json.dumps((receipts or {}).get(role, self.receipt(role))))
        (directory / "primary.apk").write_bytes(b"candidate!")
        with patch.object(release, "inspect_apk", side_effect=[primary or self.metadata(), backup or self.metadata()]), \
             patch.object(release, "signing_identity", return_value={"schema": 1, "application_id": release.PACKAGE, "certificate_sha256": pin}), \
             patch.object(release.subprocess, "check_output", return_value=SHA + "\n"), \
             patch.object(release.sys, "argv", arguments), \
             patch.dict(os.environ, {"GITHUB_ACTIONS": "true", "GITHUB_REPOSITORY": release.REPO,
                 "GITHUB_REF": "refs/heads/custom-3.2.0", "GITHUB_SHA": SHA, "GITHUB_RUN_ID": "1", "GITHUB_RUN_ATTEMPT": "1",
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
            self.assertTrue((Path(tmp) / "evidence/apk-verification.json").exists())

    def test_backup_must_match_the_pinned_certificate(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(release.delivery, "gh") as gh:
            with self.assertRaisesRegex(release.delivery.DeliveryError, "SIGNING_MISMATCH"):
                self.invoke(Path(tmp), backup=self.metadata(certificate="d" * 64))
            gh.assert_not_called()

    def test_old_package_or_wrong_version_cannot_publish(self):
        for change in ({"application_id": "me.rerere.rikkahub.chatgpt.debug"}, {"version_code": 198}):
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

    def test_two_real_restore_receipts_and_distinct_successful_jobs_reach_publication(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(release.delivery, "api_json", return_value=self.jobs()), \
             patch.object(release.delivery, "gh", side_effect=PublishReached) as gh:
            with self.assertRaises(PublishReached):
                self.invoke(Path(tmp))
            self.assertEqual(gh.call_count, 1)
            self.assertEqual(gh.call_args.args[0][:2], ["release", "create"])
            proof = json.loads((Path(tmp) / "evidence/signing-restoration-verification.json").read_text())
            self.assertTrue(proof["restoration_verified"])
            self.assertEqual(2, len(proof["clean_builds"]))

    def test_verification_only_is_not_publication_or_restoration_proof(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(release.delivery, "gh") as gh:
            self.invoke(Path(tmp), primary=self.metadata(certificate="d" * 64), verify_only=True, pin=None)
            gh.assert_not_called()
            self.assertFalse((Path(tmp) / "evidence/signing-restoration-verification.json").exists())


if __name__ == "__main__":
    unittest.main()
