"""Host-only smoke helper tests: no adb, builds, or emulator operations."""
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock
from types import SimpleNamespace
import xml.etree.ElementTree as ET

SPEC = importlib.util.spec_from_file_location(
    "content_filter_smoke", Path(__file__).resolve().parents[1] / "content-filter-smoke.py")
SMOKE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SMOKE)


class ContentFilterSmokeHelpersTest(unittest.TestCase):
    def test_empty_defaults(self):
        self.assertEqual({"selected": [], "consent_present": False, "consent_version": 0},
                         SMOKE.parse_filter_preferences("<map/>"))

    def test_selected_and_current_consent(self):
        parsed = SMOKE.parse_filter_preferences('''<map>
            <set name="content_filter_enabled_ids"><string>youtube_shorts</string></set>
            <int name="content_filter_consent_version" value="1"/></map>''')
        self.assertEqual(["youtube_shorts"], parsed["selected"])
        self.assertEqual(1, parsed["consent_version"])
        self.assertTrue(parsed["consent_present"])

    def test_rejects_unexpected_or_corrupt_preferences(self):
        for value in ('<string name="master_pin_hash">sensitive</string>',
                      '<set name="content_filter_enabled_ids"><string>unknown</string></set>',
                      '<string name="content_filter_consent_version">1</string>',
                      '<int name="content_filter_consent_version" value="x"/>',
                      '<int name="content_filter_consent_version" value="1"/>' * 2):
            with self.subTest(value=value), self.assertRaises(SMOKE.SmokeFailure):
                SMOKE.parse_filter_preferences("<map>" + value + "</map>")

    def test_remote_shell_filter_never_returns_other_keys(self):
        # Exercise the same POSIX shell filter used inside run-as, without adb.
        with tempfile.TemporaryDirectory() as temp:
            prefs = Path(temp) / "shared_prefs"
            prefs.mkdir()
            (prefs / "airlock_prefs.xml").write_text('''<?xml version="1.0"?>
<map>
    <string name="master_pin_hash">DO_NOT_RETURN_HASH</string>
    <string name="master_pin_salt">DO_NOT_RETURN_SALT</string>
    <set name="pending_requests"><string>DO_NOT_RETURN_CODE</string></set>
    <set name="content_filter_enabled_ids">
        <string>youtube_shorts</string>
        <string>instagram_reels</string>
    </set>
    <int name="content_filter_consent_version" value="1" />
    <string name="accountability_number">DO_NOT_RETURN_PHONE</string>
</map>
''', encoding="utf-8")
            output = subprocess.run(["sh", "-c", SMOKE.FILTER_PREFS_SH], cwd=temp,
                                    capture_output=True, text=True, check=True).stdout
            self.assertNotIn("DO_NOT_RETURN", output)
            self.assertNotIn("master_pin", output)
            self.assertEqual(["instagram_reels", "youtube_shorts"],
                             SMOKE.parse_filter_preferences(output)["selected"])

    def test_remote_shell_empty_set(self):
        with tempfile.TemporaryDirectory() as temp:
            prefs = Path(temp) / "shared_prefs"
            prefs.mkdir()
            (prefs / "airlock_prefs.xml").write_text('''<map>
    <set name="content_filter_enabled_ids" />
    <string name="master_pin_hash">PRIVATE</string>
</map>
''', encoding="utf-8")
            output = subprocess.run(["sh", "-c", SMOKE.FILTER_PREFS_SH], cwd=temp,
                                    capture_output=True, text=True, check=True).stdout
            self.assertNotIn("PRIVATE", output)
            self.assertEqual([], SMOKE.parse_filter_preferences(output)["selected"])

    @staticmethod
    def diagnostic(**updates):
        value = {"schema": 1, "target": SMOKE.SERVICE, "connected": True,
                 "cache_enabled": False, "subscribed_packages": ["com.google.android.youtube"],
                 "setting_matches_target": True, "accessibility_enabled_setting": 1,
                 "ignored_field": "DO_NOT_RETAIN"}
        value.update(updates)
        return 'Broadcasting: Intent { ... }\nBroadcast completed: result=-1, data="' + json.dumps(value) + '"\n'

    def test_diagnostic_parser_retains_only_needed_metadata(self):
        value = SMOKE.parse_content_filter_diagnostics(self.diagnostic())
        self.assertEqual(["com.google.android.youtube"], value["subscribed_packages"])
        self.assertTrue(value["connected"])
        self.assertFalse(value["cache_enabled"])
        self.assertNotIn("DO_NOT_RETAIN", json.dumps(value))
        self.assertNotIn("target", value)

    def test_diagnostic_parser_rejects_schema_target_and_bad_types(self):
        for update in ({"schema": 2}, {"schema": True}, {"target": "wrong/service"},
                       {"connected": 1}, {"cache_enabled": "false"},
                       {"setting_matches_target": "true"},
                       {"accessibility_enabled_setting": True},
                       {"subscribed_packages": []}, {"subscribed_packages": ["*"]},
                       {"subscribed_packages": "com.google.android.youtube"},
                       {"subscribed_packages": [True]},
                       {"subscribed_packages": ["com.example", "com.example"]}):
            with self.subTest(update=update), self.assertRaises(SMOKE.SmokeFailure):
                SMOKE.parse_content_filter_diagnostics(self.diagnostic(**update))
        for output in ("missing", 'Broadcast completed: result=-1, data="not-json"',
                       'Broadcast completed: result=-1, data="{}"',
                       'Broadcast completed: result=0, data="content_filter_diagnostics:Exception"'):
            with self.subTest(output=output), self.assertRaises(SMOKE.SmokeFailure):
                SMOKE.parse_content_filter_diagnostics(output)

    def test_busy_timeout_null_cache_and_null_packages_are_never_ready(self):
        for output in ('Broadcast completed: result=0, data="content_filter_diagnostics:busy"',
                       'Broadcast completed: result=0, data="content_filter_diagnostics:timeout"',
                       self.diagnostic(connected=False, cache_enabled=None, subscribed_packages=None),
                       self.diagnostic(subscribed_packages=None),
                       self.diagnostic(cache_enabled=None)):
            with self.subTest(output=output), self.assertRaises(SMOKE.DiagnosticPending):
                SMOKE.parse_content_filter_diagnostics(output)

    def test_subscription_retries_without_ui_dumps_and_saves_only_checked_evidence(self):
        with tempfile.TemporaryDirectory() as temp, mock.patch.object(SMOKE.time, "sleep"):
            smoke = object.__new__(SMOKE.Smoke)
            smoke.deadline = SMOKE.time.monotonic() + 5
            smoke.report = Path(temp)
            smoke.scenario = "host"
            smoke.ui = mock.Mock(side_effect=AssertionError("No UI dumps during diagnostic retries"))
            smoke.shell = mock.Mock(side_effect=[
                SMOKE.AdbTimeout("bounded timeout"),
                SimpleNamespace(stdout='Broadcast completed: result=0, data="content_filter_diagnostics:busy"'),
                SimpleNamespace(stdout=self.diagnostic(connected=False, cache_enabled=None, subscribed_packages=None)),
                SimpleNamespace(stdout=self.diagnostic(cache_enabled=True)),
                SimpleNamespace(stdout=self.diagnostic(subscribed_packages=["com.other.app"])),
                SimpleNamespace(stdout=self.diagnostic())])
            smoke.assert_subscription({"com.google.android.youtube"})
            self.assertEqual(6, smoke.shell.call_count)
            self.assertFalse(smoke.ui.called)
            for args, kwargs in smoke.shell.call_args_list:
                self.assertEqual(("--es", "command", "content_filter_diagnostics"), args[-3:])
                self.assertGreater(kwargs["timeout"], 0)
                self.assertLessEqual(kwargs["timeout"], 5)
            evidence = (Path(temp) / "host-subscription.json").read_text()
            self.assertNotIn("DO_NOT_RETAIN", evidence)
            self.assertEqual(["com.google.android.youtube"], json.loads(evidence)["subscribed_packages"])

    def test_mismatched_subscription_cannot_pass_before_deadline(self):
        with tempfile.TemporaryDirectory() as temp:
            smoke = object.__new__(SMOKE.Smoke)
            smoke.deadline = SMOKE.time.monotonic() + 0.01
            smoke.report = Path(temp)
            smoke.scenario = "host"
            smoke.shell = mock.Mock(return_value=SimpleNamespace(stdout=self.diagnostic(
                subscribed_packages=["com.google.android.youtube", "com.other.app"])))
            with self.assertRaises(SMOKE.SmokeFailure):
                smoke.assert_subscription({"com.google.android.youtube"})
            self.assertFalse((Path(temp) / "host-subscription.json").exists())

    def test_xml_redaction_preserves_ids_and_hides_input(self):
        xml = '''<hierarchy rotation="0"><node resource-id="com.dankhole.airlock:id/master_pin_dialog_input"
        text="1234" content-desc="1234" class="android.widget.EditText" password="true"
        bounds="[0,0][100,50]"/><node resource-id="com.dankhole.airlock:id/content_filter_status"
        text="Duty off"/></hierarchy>'''
        root = ET.fromstring(xml)
        redacted = SMOKE.redacted_xml(root)
        self.assertNotIn("1234", redacted)
        self.assertIn("Duty off", redacted)
        self.assertEqual("1234", SMOKE.node_by_id(root, "master_pin_dialog_input").get("text"))
        self.assertEqual((0, 0, 100, 50), SMOKE.bounds(SMOKE.node_by_id(root, "master_pin_dialog_input")))

    def test_pin_submission_clears_all_four_digits_before_same_dialog_control(self):
        smoke = object.__new__(SMOKE.Smoke)
        calls = []
        smoke.wait_id = lambda name: None
        smoke.tap = lambda name: calls.append(("tap", name))
        smoke.shell = lambda *args: calls.append(args)
        smoke.enter_pin()
        self.assertIn(("input", "keyevent", "KEYCODE_MOVE_END", "KEYCODE_DEL",
                       "KEYCODE_DEL", "KEYCODE_DEL", "KEYCODE_DEL"), calls)
        self.assertEqual(("tap", "android:id/button1"), calls[-1])

    def test_android_access_assertions_distinguish_disabled_and_enabled(self):
        smoke = object.__new__(SMOKE.Smoke)
        checks = []
        smoke.text_contains = lambda name, text: checks.append((name, text))
        smoke.assert_android_access(False)
        self.assertEqual("Android Accessibility: not enabled (cached setting).", checks[0][1])
        self.assertIn("does not give filtering consent", checks[1][1])
        checks.clear()
        smoke.assert_android_access(True)
        self.assertEqual("Android Accessibility: enabled (cached setting).", checks[0][1])
        self.assertIn("does not confirm a connection", checks[1][1])
        self.assertTrue(all(name == "content_filter_android_access" for name, _ in checks))

    def test_bounds_reject_empty_or_invalid(self):
        for value in ("", "[1,1][1,5]", "[5,5][1,1]", "malformed"):
            with self.subTest(value=value), self.assertRaises(SMOKE.SmokeFailure):
                SMOKE.bounds(ET.Element("node", {"bounds": value}))


if __name__ == "__main__":
    unittest.main()
