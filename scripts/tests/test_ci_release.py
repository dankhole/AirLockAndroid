import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("ci_release", Path(__file__).parents[1] / "ci-release.py")
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class ReleaseChecksTest(unittest.TestCase):
    def test_version_sequence_and_play_limit(self):
        self.assertEqual(10001, release.version_code("1", "10000"))
        self.assertEqual(10002, release.version_code("2", "10000"))
        self.assertEqual(2100000000, release.version_code("1", "2099999999"))
        for run, base in [("0", "10000"), ("1", "-1"), ("$(echo bad)", "10000"),
                          ("1", "2100000000"), ("", "10000")]:
            with self.subTest(run=run, base=base), self.assertRaises(ValueError):
                release.version_code(run, base)

    def test_audit_checks_visible_text_and_notes_not_resource_identifiers(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            values = root / "app/src/main/res/values"
            notes = root / "play-store/whatsnew"
            values.mkdir(parents=True)
            notes.mkdir(parents=True)
            (root / "play-store/listing-copy.txt").write_text("Airlock")
            changelog = notes / "whatsnew-en-US"
            changelog.write_text("Improved navigation.")
            strings = values / "strings.xml"
            strings.write_text('<resources><string name="override_title">Extra time</string></resources>')
            release.audit_copy(root)
            strings.write_text('<resources><string name="title">Hidden <b>testing</b> fallback</string></resources>')
            with self.assertRaisesRegex(ValueError, "strings.xml"):
                release.audit_copy(root)
            strings.write_text('<resources/>')
            changelog.write_text("x" * 501)
            with self.assertRaisesRegex(ValueError, "500 characters"):
                release.audit_copy(root)
            changelog.unlink()
            with self.assertRaisesRegex(ValueError, "Missing English"):
                release.audit_copy(root)

    def test_missing_signing_secrets_fail_before_writing(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {}, clear=True):
            target = Path(directory) / "upload.jks"
            with self.assertRaisesRegex(ValueError, "Configure GitHub environment secrets"):
                release.prepare_signing(target)
            self.assertFalse(target.exists())

    def test_signing_rejects_invalid_base64_and_wrong_identity(self):
        secrets = dict.fromkeys(("AIRLOCK_KEYSTORE_PASSWORD", "AIRLOCK_KEY_ALIAS",
                                "AIRLOCK_KEY_PASSWORD", "GOOGLE_PLAY_SERVICE_ACCOUNT_JSON"), "example")
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, secrets, clear=True):
            target = Path(directory) / "upload.jks"
            os.environ["AIRLOCK_KEYSTORE_BASE64"] = "???"
            with self.assertRaisesRegex(ValueError, "valid base64"):
                release.prepare_signing(target)
            self.assertFalse(target.exists())
            os.environ["AIRLOCK_KEYSTORE_BASE64"] = "a2V5"
            with patch.object(release.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, b"wrong cert")):
                with self.assertRaisesRegex(ValueError, "signing identity"):
                    release.prepare_signing(target)
            self.assertEqual(0o600, target.stat().st_mode & 0o777)
            with self.assertRaises(FileExistsError):
                release.prepare_signing(target)

    def test_unsigned_or_tampered_bundle_cannot_pass(self):
        with tempfile.NamedTemporaryFile(suffix=".aab") as bundle:
            for code, output in [(0, "jar is unsigned."), (16, "jar verified."), (1, "tampered")]:
                result = subprocess.CompletedProcess([], code, output)
                with self.subTest(code=code), patch.object(release.subprocess, "run", return_value=result):
                    with self.assertRaisesRegex(ValueError, "signature verification"):
                        release.verify_bundle(Path(bundle.name))

    def test_verified_bundle_requires_expected_certificate(self):
        with tempfile.NamedTemporaryFile(suffix=".aab") as bundle:
            for fingerprint, valid in [(release.UPLOAD_CERT_SHA256, True), ("AABB", False)]:
                results = [subprocess.CompletedProcess([], 4, "jar verified."),
                           subprocess.CompletedProcess([], 0, f"SHA256: {fingerprint}")]
                with patch.object(release.subprocess, "run", side_effect=results):
                    if valid:
                        release.verify_bundle(Path(bundle.name))
                    else:
                        with self.assertRaisesRegex(ValueError, "upload certificate"):
                            release.verify_bundle(Path(bundle.name))


if __name__ == "__main__":
    unittest.main()
