"""Exercise the publication boundary without a network request or signing private key."""

import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location(
    "chatgpt_release", Path(__file__).resolve().parents[1] / "publish-chatgpt-test.py"
)
release = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(release)


class PublishReached(Exception):
    pass


class ChatGPTReleaseSigningTest(unittest.TestCase):
    def metadata(self, certificate=None, version_code=198):
        return {
            "application_id": release.PACKAGE,
            "version_code": version_code,
            "version_name": release.VERSION_NAME,
            "min_sdk": 26,
            "architectures": ["arm64-v8a"],
            "certificate_sha256": certificate or release.EXPECTED_UPGRADE_CERTIFICATE_SHA256,
            # The gate must check the real certificate, not trust this display flag.
            "upgrade_certificate_matches": True,
            "apk_sha256": "0" * 64,
            "apk_size": 10,
        }

    def run_main(self, directory, metadata, verify_only=False):
        apk = directory / "candidate.apk"
        apk.write_bytes(b"candidate!")
        arguments = [
            "publish-chatgpt-test.py", "--apk", str(apk), "--build-tools", str(directory),
            "--output", str(directory / "evidence"),
        ]
        if verify_only:
            arguments.append("--verify-only")
        sha = "a" * 40
        with patch.object(release, "inspect_apk", return_value=metadata), \
             patch.object(release.subprocess, "check_output", return_value=sha + "\n"), \
             patch.object(release.sys, "argv", arguments), \
             patch.dict(os.environ, {
                 "GITHUB_ACTIONS": "true", "GITHUB_REPOSITORY": release.REPO,
                 "GITHUB_REF": "refs/heads/custom-3.2.0", "GITHUB_SHA": sha,
                 "GITHUB_RUN_ID": "1", "GITHUB_RUN_ATTEMPT": "1",
                 "GITHUB_TOKEN": "synthetic-test-token-never-sent",
             }), contextlib.redirect_stdout(io.StringIO()):
            release.main()

    def test_different_signer_cannot_create_release_or_upload_even_with_success_flag(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(release.delivery, "gh") as gh:
            directory = Path(tmp)
            with self.assertRaisesRegex(release.delivery.DeliveryError, "UPGRADE_SIGNING"):
                self.run_main(directory, self.metadata(certificate="1" * 64))
            gh.assert_not_called()
            self.assertTrue((directory / "evidence/apk-verification.json").is_file())

    def test_same_or_lower_version_cannot_publish(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(release.delivery, "gh") as gh:
            with self.assertRaisesRegex(release.delivery.DeliveryError, "UPGRADE_IDENTITY"):
                self.run_main(Path(tmp), self.metadata(version_code=197))
            gh.assert_not_called()

    def test_matching_certificate_and_higher_version_reach_publication(self):
        with tempfile.TemporaryDirectory() as tmp, \
             patch.object(release.delivery, "gh", side_effect=PublishReached) as gh:
            with self.assertRaises(PublishReached):
                self.run_main(Path(tmp), self.metadata())
            self.assertEqual(gh.call_count, 1)
            self.assertEqual(gh.call_args.args[0][:2], ["release", "create"])

    def test_verification_only_keeps_mismatch_evidence_without_publication(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(release.delivery, "gh") as gh:
            directory = Path(tmp)
            metadata = self.metadata(certificate="1" * 64)
            metadata["upgrade_certificate_matches"] = False
            self.run_main(directory, metadata, verify_only=True)
            evidence = json.loads((directory / "evidence/apk-verification.json").read_text())
            self.assertFalse(evidence["upgrade_certificate_matches"])
            gh.assert_not_called()


if __name__ == "__main__":
    unittest.main()
