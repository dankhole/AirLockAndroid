#!/usr/bin/env python3
"""Bounded, emulator-only content-settings smoke (Python standard library only).

Usage: python3 scripts/content-filter-smoke.py [--adb /path/to/adb]
Requires the debug Airlock and com.dankhole.airlock.smoketarget already installed.
Does NOT build, install, start/stop an emulator, or qualify real feed selectors.
Requires the current debug fixture's content_filter_diagnostics command.
Exit 0 means passed; exit 1 means failed.
Like android-smoke.sh, this DESTRUCTIVELY seeds/resets app-local fixture state;
use a disposable test emulator, never personal app data. Android access/settings
changed here are restored in finally. Reports contain redacted UI XML and only
allowlisted filter preferences, never complete preferences, PINs or codes.
"""

import argparse
import copy
import datetime
import json
import os
from pathlib import Path
import re
import shlex
import signal
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PACKAGE = "com.dankhole.airlock"
NAMESPACE = "com.dankhole.airlockandroid"
TARGET = "com.dankhole.airlock.smoketarget"
SERVICE = PACKAGE + "/" + NAMESPACE + ".ContentFilterService"
EDITOR = PACKAGE + "/" + NAMESPACE + ".ContentFilterSettingsActivity"
MAIN = PACKAGE + "/" + NAMESPACE + ".MainActivity"
FILTERS = ("youtube_shorts", "instagram_reels", "facebook_reels",
           "snapchat_spotlight", "instagram_stories")
FILTER_KEY = "content_filter_enabled_ids"
CONSENT_KEY = "content_filter_consent_version"
# Filter on the device BEFORE returning any preferences to the host. Android's
# platform shell supplies read/case/printf; no on-device Python/awk is required.
FILTER_PREFS_SH = r'''printf '<map>\n'
in_set=0
while IFS= read -r line; do
    case "$line" in
        *'<set name="content_filter_enabled_ids"'* ) in_set=1 ;;
        *'name="content_filter_consent_version"'* ) printf '%s\n' "$line" ;;
    esac
    if [ "$in_set" = 1 ]; then
        printf '%s\n' "$line"
        case "$line" in *'</set>'* | *'/>'* ) in_set=0 ;; esac
    fi
done < shared_prefs/airlock_prefs.xml
printf '</map>\n'
'''


class SmokeFailure(RuntimeError):
    pass


class DiagnosticPending(SmokeFailure):
    """Bounded diagnostics are busy or their live service is not ready yet."""


class AdbTimeout(SmokeFailure):
    pass


def require(condition, message):
    if not condition:
        raise SmokeFailure(message)


def resource_id(name):
    return name if ":id/" in name else PACKAGE + ":id/" + name


def node_by_id(root, name):
    return next((n for n in root.iter("node")
                 if n.get("resource-id") == resource_id(name)), None)


def bounds(node):
    match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
    require(match is not None, "UI node has invalid bounds")
    left, top, right, bottom = map(int, match.groups())
    require(right > left and bottom > top, "UI node has empty bounds")
    return left, top, right, bottom


def redacted_xml(root):
    root = copy.deepcopy(root)
    for node in root.iter("node"):
        if (node.get("password") == "true"
                or "EditText" in node.get("class", "")
                or "pin" in node.get("resource-id", "").lower()):
            for key in ("text", "content-desc", "hint"):
                if key in node.attrib:
                    node.set(key, "[REDACTED]")
    return ET.tostring(root, encoding="unicode")


def parse_filter_preferences(xml):
    """Accept only the two remotely selected keys; fail on unexpected material."""
    root = ET.fromstring(xml)
    require(root.tag == "map", "Invalid filter preference wrapper")
    values = {}
    for child in root:
        key = child.get("name")
        require(key in (FILTER_KEY, CONSENT_KEY) and key not in values,
                "Unexpected or duplicated filter preference key")
        values[key] = child
    selected = set()
    if FILTER_KEY in values:
        value = values[FILTER_KEY]
        require(value.tag == "set", "Filter IDs are not a string set")
        for entry in value:
            require(entry.tag == "string" and entry.text in FILTERS,
                    "Unexpected stored filter ID")
            selected.add(entry.text)
    consent = values.get(CONSENT_KEY)
    if consent is not None:
        require(consent.tag == "int" and re.fullmatch(r"\d+", consent.get("value", "")),
                "Consent version is not an integer")
    return {"selected": sorted(selected), "consent_present": consent is not None,
            "consent_version": int(consent.get("value")) if consent is not None else 0}


def parse_content_filter_diagnostics(output):
    """Validate the debug broadcast and retain only required service metadata.

    Never return the complete diagnostic object or quote its contents in errors.
    A null subscription/cache result is not an empty or safe subscription.
    """
    require(len(output) <= 65_536, "Diagnostic response exceeded size bound")
    match = re.search(r'^Broadcast completed: result=(-?\d+)(?:, data="([^\n]*)")?\s*$',
                      output, re.MULTILINE)
    require(match is not None, "Diagnostic broadcast result missing")
    code, data = int(match.group(1)), match.group(2)
    if code != -1 and data in ("content_filter_diagnostics:busy", "content_filter_diagnostics:timeout"):
        raise DiagnosticPending("Diagnostic worker busy or timed out")
    require(code == -1 and data is not None, "Diagnostic broadcast failed (output withheld)")
    try:
        value = json.loads(data)
    except (ValueError, TypeError) as exc:
        raise SmokeFailure("Diagnostic JSON is malformed") from exc
    require(isinstance(value, dict) and type(value.get("schema")) is int
            and value["schema"] == 1, "Unsupported diagnostic schema")
    require(value.get("target") == SERVICE, "Diagnostic target does not match Airlock service")
    fields = ("connected", "cache_enabled", "subscribed_packages",
              "setting_matches_target", "accessibility_enabled_setting")
    require(all(key in value for key in fields), "Required diagnostic field missing")
    require(type(value["connected"]) is bool and type(value["setting_matches_target"]) is bool
            and type(value["accessibility_enabled_setting"]) is int,
            "Invalid diagnostic connection/setting types")
    cache = value["cache_enabled"]
    require(cache is None or type(cache) is bool, "Invalid diagnostic cache state")
    packages = value["subscribed_packages"]
    if packages is None or cache is None:
        raise DiagnosticPending("Live service subscription or cache state unavailable")
    require(isinstance(packages, list) and packages and all(
        isinstance(p, str) and re.fullmatch(r"[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+", p)
        for p in packages), "Empty, invalid or broad diagnostic subscription")
    require(len(packages) == len(set(packages)), "Duplicate diagnostic package subscription")
    return {"connected": value["connected"], "cache_enabled": cache,
            "subscribed_packages": sorted(packages),
            "setting_matches_target": value["setting_matches_target"],
            "accessibility_enabled_setting": value["accessibility_enabled_setting"]}


class Smoke:
    def __init__(self, adb, timeout):
        self.adb_path = adb
        self.deadline = time.monotonic() + timeout
        self.restoring = False
        self.original_settings = {}
        self.original_ops = {}
        self.notification_granted = None
        self.fixture_touched = False
        self.device_verified = False
        self.scenario = "preflight"
        self.results = []
        self.root = Path(__file__).resolve().parents[1]
        stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S-%f")
        self.report = self.root / "app/build/reports/content-filter-smoke" / stamp
        self.report.mkdir(parents=True)
        self.remote_xml = "/sdcard/airlock-content-filter-" + str(os.getpid()) + ".xml"
        self.remote_screenshot = self.remote_xml.removesuffix(".xml") + ".png"

    def adb(self, *args, check=True, timeout=20, emulator=True):
        remaining = 20 if self.restoring else self.deadline - time.monotonic()
        require(remaining > 0, "Overall smoke deadline exceeded")
        try:
            result = subprocess.run([self.adb_path] + (["-e"] if emulator else []) + list(args),
                                    capture_output=True, text=True,
                                    timeout=min(timeout, remaining))
        except subprocess.TimeoutExpired as exc:
            raise AdbTimeout("Bounded adb command timed out (output withheld)") from exc
        # Never echo commands/input or arbitrary adb output: it can contain codes.
        require(not check or result.returncode == 0, "adb command failed (output withheld)")
        return result

    def shell(self, *args, **kwargs):
        return self.adb("shell", shlex.join(args), **kwargs)

    def setting(self, table, key):
        return self.shell("settings", "get", table, key).stdout.strip()

    def set_setting(self, table, key, value):
        if value == "null":
            self.shell("settings", "delete", table, key)
        else:
            self.shell("settings", "put", table, key, value)

    def change_setting(self, table, key, value):
        if (table, key) not in self.original_settings:
            self.original_settings[table, key] = self.setting(table, key)
        self.set_setting(table, key, value)

    def wait(self, predicate, message, timeout=20):
        end = min(self.deadline, time.monotonic() + timeout)
        while time.monotonic() < end:
            if predicate():
                return
            time.sleep(0.3)
        raise SmokeFailure(message)

    def ui(self):
        self.shell("uiautomator", "dump", self.remote_xml, timeout=12)
        xml = self.shell("cat", self.remote_xml).stdout
        try:
            root = ET.fromstring(xml)
        except ET.ParseError as exc:
            raise SmokeFailure("Invalid uiautomator XML") from exc
        (self.report / "latest.xml").write_text(redacted_xml(root), encoding="utf-8")
        return root

    def capture_screen(self, name):
        # Only fixture-owned Airlock screens are captured, never real target apps.
        self.shell("screencap", "-p", self.remote_screenshot)
        self.adb("pull", self.remote_screenshot, str(self.report / (name + ".png")))

    def wait_id(self, name, present=True, timeout=20):
        found = []

        def check():
            node = node_by_id(self.ui(), name)
            found[:] = [node]
            return (node is not None) == present

        self.wait(check, "Timed out waiting for " + name + ("" if present else " to disappear"), timeout)
        return found[0]

    def find(self, name, scroll=None):
        # Reset to the top, then bounded forward swipes. Scroll coordinates come
        # from the actual scroll resource ID, never a fixed Pixel resolution.
        for direction in ("up", "down"):
            for _ in range(7):
                root = self.ui()
                node = node_by_id(root, name)
                if node is not None:
                    try:
                        left, top, right, bottom = bounds(node)
                        container = node_by_id(root, scroll) if scroll else None
                        if container is None:
                            return node
                        c_left, c_top, c_right, c_bottom = bounds(container)
                        if (c_left <= (left + right) // 2 < c_right
                                and c_top <= (top + bottom) // 2 < c_bottom):
                            return node
                    except SmokeFailure:
                        pass  # Offscreen/clipped nodes need a bounded scroll.
                container = node_by_id(root, scroll) if scroll else None
                if container is None:
                    break
                left, top, right, bottom = bounds(container)
                x = (left + right) // 2
                high, low = top + (bottom - top) // 5, bottom - (bottom - top) // 5
                start, end = (high, low) if direction == "up" else (low, high)
                self.shell("input", "swipe", str(x), str(start), str(x), str(end), "240")
        raise SmokeFailure("Could not locate resource ID " + name)

    def tap(self, name, scroll=None):
        node = self.find(name, scroll)
        require(node.get("enabled") != "false", "Control is unavailable: " + name)
        left, top, right, bottom = bounds(node)
        self.shell("input", "tap", str((left + right) // 2), str((top + bottom) // 2))

    def text_contains(self, name, text, scroll="content_filter_scroll"):
        def check():
            node = self.find(name, scroll)
            return text.casefold() in node.get("text", "").casefold()
        self.wait(check, "Expected visible status missing for " + name)

    def assert_android_access(self, enabled):
        # This row describes only Android's cached setting. It must remain
        # independent of consent and Duty status, never stand in for readiness.
        prefix = "Android Accessibility: " + ("enabled" if enabled else "not enabled")
        self.text_contains("content_filter_android_access", prefix + " (cached setting).")
        caveat = ("does not confirm a connection, consent or healthy protection."
                  if enabled else "Enabling Android access alone does not give filtering consent.")
        self.text_contains("content_filter_android_access", caveat)

    def fixture(self, command):
        self.fixture_touched = True
        args = ["am", "broadcast", "-n", PACKAGE + "/" + NAMESPACE + ".DebugFixtureReceiver",
                "--es", "command", command]
        if command == "seed":
            args += ["--es", "target_package", TARGET, "--ez", "monitoring", "false"]
        output = self.shell(*args).stdout
        require("result=-1" in output, "Debug fixture failed (output withheld)")

    def launch_task(self):
        # Launcher semantics return to the existing task's top activity, including
        # saved-state recreation; explicitly starting the unexported editor would
        # test only manifest permissions, not process-local authorization.
        self.shell("am", "start", "-W", "-a", "android.intent.action.MAIN", "-c",
                   "android.intent.category.LAUNCHER", "-f", "0x10200000", "-n", MAIN)

    def enter_pin(self, correct=True):
        self.wait_id("master_pin_dialog_input")
        self.tap("master_pin_dialog_input")
        # Every fixture PIN is four digits. Clear deterministically so the
        # retained wrong-PIN prompt can be used as a correct-PIN positive control.
        self.shell("input", "keyevent", "KEYCODE_MOVE_END", "KEYCODE_DEL",
                   "KEYCODE_DEL", "KEYCODE_DEL", "KEYCODE_DEL")
        self.shell("input", "text", "1234" if correct else "9999")
        # Platform AlertDialog adjusts above the IME. Click its actual positive
        # button, rather than assume a hardware Enter invoked IME_ACTION_DONE.
        self.tap("android:id/button1")

    def open_editor(self):
        self.tap("main_content_filter_settings", "main_scroll")
        self.enter_pin()
        self.wait_id("content_filter_status")
        self.wait(lambda: self.find("content_filter_youtube_shorts", "content_filter_scroll")
                  .get("enabled") == "true", "Filter choices did not become editable")

    def unlock_editor(self):
        self.tap("content_filter_unlock", "content_filter_scroll")
        self.enter_pin()
        self.wait_id("content_filter_status")
        self.wait(lambda: self.find("content_filter_youtube_shorts", "content_filter_scroll")
                  .get("enabled") == "true", "Filter choices did not become editable")

    def rotate(self, rotation):
        self.change_setting("system", "user_rotation", rotation)
        self.wait(lambda: self.ui().get("rotation") == rotation,
                  "Emulator did not rotate to the requested orientation")
        self.wait_id("content_filter_status")

    def preferences(self):
        xml = self.shell("run-as", PACKAGE, "sh", "-c", FILTER_PREFS_SH).stdout
        try:
            return parse_filter_preferences(xml)
        except ET.ParseError as exc:
            raise SmokeFailure("Invalid allowlisted preference XML") from exc

    def assert_preferences(self, selected, consent):
        def check():
            state = self.preferences()
            return (state["selected"] == sorted(selected)
                    and state["consent_present"] == consent
                    and state["consent_version"] == (1 if consent else 0))
        self.wait(check, "Filter persistence did not match expected selection/consent")

    def set_service(self, enabled):
        old = self.original_settings["secure", "enabled_accessibility_services"]
        services = [s for s in old.split(":") if s and s != "null"
                    and s not in (SERVICE, PACKAGE + "/.ContentFilterService")]
        if enabled:
            services.append(SERVICE)
        self.change_setting("secure", "enabled_accessibility_services", ":".join(services))
        self.change_setting("secure", "accessibility_enabled", "1" if services else "0")

    def assert_subscription(self, expected):
        # UIAutomator temporarily suppresses other accessibility services. Do NOT
        # dump UI between these retries: let the service reconnect, then query its
        # own metadata through the existing debug-only bounded receiver command.
        end = min(self.deadline, time.monotonic() + 15)
        error = "Live service did not connect with cache disabled and exact package subscriptions"
        while time.monotonic() < end:
            remaining = end - time.monotonic()
            if remaining <= 0:
                break
            try:
                output = self.shell(
                    "am", "broadcast", "-n", PACKAGE + "/" + NAMESPACE + ".DebugFixtureReceiver",
                    "--es", "command", "content_filter_diagnostics", timeout=min(5, remaining)).stdout
                evidence = parse_content_filter_diagnostics(output)
                if (evidence["connected"] and evidence["cache_enabled"] is False
                        and evidence["setting_matches_target"]
                        and evidence["accessibility_enabled_setting"] == 1
                        and evidence["subscribed_packages"] == sorted(expected)):
                    (self.report / (self.scenario + "-subscription.json")).write_text(
                        json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
                    return
                error = "Live service connection/cache/settings/subscriptions did not match expectations"
            except (DiagnosticPending, AdbTimeout) as exc:
                error = str(exc)
            time.sleep(min(0.3, max(0, end - time.monotonic())))
        raise SmokeFailure(error)

    def checkpoint(self, name):
        self.results.append({"scenario": self.scenario, "result": "passed"})
        self.scenario = name
        root = self.ui()
        (self.report / (name + ".xml")).write_text(redacted_xml(root), encoding="utf-8")

    def preflight(self):
        devices = self.adb("devices", emulator=False).stdout.splitlines()[1:]
        emulators = [line.split() for line in devices if line.startswith("emulator-")]
        require(len(emulators) == 1 and emulators[0][1:] == ["device"],
                "Exactly one connected, online emulator is required; physical devices are never targeted")
        require(self.shell("getprop", "ro.kernel.qemu").stdout.strip() == "1",
                "adb -e did not select an emulator")
        self.device_verified = True
        for package in (PACKAGE, TARGET):
            require(self.shell("pm", "path", package).stdout.startswith("package:"),
                    "Required preinstalled package missing: " + package)
        package_info = self.shell("dumpsys", "package", PACKAGE).stdout
        require(re.search(r"\bDEBUGGABLE\b", package_info) is not None,
                "Installed Airlock must be debuggable")
        self.shell("run-as", PACKAGE, "id")
        for table, key in (("secure", "enabled_accessibility_services"),
                           ("secure", "accessibility_enabled")):
            self.original_settings[table, key] = self.setting(table, key)
        for operation in ("GET_USAGE_STATS", "SYSTEM_ALERT_WINDOW"):
            output = self.shell("appops", "get", "--uid", PACKAGE, operation).stdout
            match = re.search(re.escape(operation) + r":\s*([a-z_]+)", output)
            require(match is not None or "No operations" in output,
                    "Cannot snapshot app-op mode: " + operation)
            self.original_ops[operation] = match.group(1) if match else "default"
        permission = re.search(r"android\.permission\.POST_NOTIFICATIONS: granted=(true|false)", package_info)
        require(permission is not None, "Cannot snapshot notification permission")
        self.notification_granted = permission.group(1) == "true"
        self.set_service(False)
        for operation in self.original_ops:
            self.shell("appops", "set", "--uid", PACKAGE, operation, "allow")
        self.shell("pm", "grant", PACKAGE, "android.permission.POST_NOTIFICATIONS")
        for table, key, value in (("system", "accelerometer_rotation", "0"),
                                  ("system", "user_rotation", "0"),
                                  ("global", "window_animation_scale", "0"),
                                  ("global", "transition_animation_scale", "0"),
                                  ("global", "animator_duration_scale", "0")):
            self.change_setting(table, key, value)
        self.fixture("seed")
        self.shell("am", "force-stop", PACKAGE)
        self.launch_task()
        self.wait_id("main_scroll")

    def run(self):
        self.preflight()
        self.checkpoint("pin-cancel-duty-off")
        self.tap("main_content_filter_settings", "main_scroll")
        self.wait_id("master_pin_dialog_input")
        self.tap("android:id/button2")
        self.wait_id("master_pin_dialog_input", present=False)
        require(node_by_id(self.ui(), "content_filter_status") is None,
                "Canceled PIN exposed editable filters")
        self.assert_preferences([], False)
        self.checkpoint("pin-wrong-duty-off")
        self.tap("main_content_filter_settings", "main_scroll")
        self.enter_pin(correct=False)
        self.wait_id("master_pin_dialog_input")
        require(node_by_id(self.ui(), "content_filter_status") is None,
                "Wrong PIN exposed editable filters")
        self.assert_preferences([], False)
        # Pixel 8 / Android 17 shows native EditText.setError visually, but its
        # popup is absent even from verbose uiautomator XML. Retain the masked
        # fixture screenshot for manual error-copy review, and test authorization
        # behavior rather than claim XML asserted unavailable error text.
        self.capture_screen("wrong-pin-native-error")
        self.checkpoint("pin-correct-defaults-duty-off")
        # Same dialog, same submit control: success after replacing only the PIN
        # is the positive control for the preceding rejected submission.
        self.enter_pin()
        self.wait_id("content_filter_status")
        self.wait(lambda: self.find("content_filter_youtube_shorts", "content_filter_scroll")
                  .get("enabled") == "true", "Filter choices did not become editable")
        self.assert_preferences([], False)
        for filter_id in FILTERS:
            require(self.find("content_filter_" + filter_id, "content_filter_scroll")
                    .get("checked") == "false", "A filter did not default off")
        self.text_contains("content_filter_status", "OFF:")
        self.assert_android_access(False)
        self.checkpoint("decline-no-consent")
        self.tap("content_filter_consent", "content_filter_scroll")
        self.wait_id("android:id/button2")
        self.tap("android:id/button2")
        self.assert_preferences([], False)
        self.checkpoint("android-enable-without-consent")
        self.tap("content_filter_youtube_shorts", "content_filter_scroll")
        self.assert_preferences(["youtube_shorts"], False)
        self.set_service(True)  # Android secure setting only; never write consent.
        self.assert_subscription({PACKAGE + ".content_filter_disabled"})
        self.assert_android_access(True)
        self.assert_preferences(["youtube_shorts"], False)
        self.text_contains("content_filter_status", "duty is off")
        self.checkpoint("affirmative-consent-visible-status")
        self.tap("content_filter_consent", "content_filter_scroll")
        self.wait_id("android:id/button1")
        self.tap("android:id/button1")
        self.wait_id("content_filter_scroll", present=False)
        self.wait(lambda: any(n.get("package") == "com.android.settings"
                            for n in self.ui().iter("node")),
                  "Affirmative consent did not open Android settings")
        self.assert_preferences(["youtube_shorts"], True)
        # Settings may already have a separate task/back stack from earlier
        # smoke scenarios. Resume Airlock's existing task instead of assuming
        # one Back leaves Settings and returns directly to this editor.
        self.launch_task()
        self.wait_id("content_filter_status")
        self.text_contains("content_filter_consent", "Withdraw")
        self.assert_android_access(True)
        self.text_contains("content_filter_status", "duty is off")
        # Selected-package events may be subscribed while Duty is off. The UI
        # remains NOT FILTERING; event delivery is not proof of tree inspection.
        self.assert_subscription({"com.google.android.youtube"})
        self.checkpoint("rotation-authorization-survives")
        self.rotate("1")
        require(node_by_id(self.ui(), "content_filter_locked") is None,
                "Configuration recreation unexpectedly lost valid authorization")
        self.assert_preferences(["youtube_shorts"], True)
        self.rotate("0")
        self.checkpoint("background-session-expiry")
        self.shell("input", "keyevent", "KEYCODE_HOME")
        require(self.deadline - time.monotonic() > 32, "Insufficient time for background-expiry check")
        time.sleep(32)  # Deliberately exceeds the 30-second process-local grace.
        self.launch_task()
        self.wait_id("content_filter_locked")
        require(node_by_id(self.ui(), "content_filter_status") is None,
                "Expired session exposed editable controls")
        self.assert_preferences(["youtube_shorts"], True)
        self.unlock_editor()
        self.checkpoint("process-recreation-no-stale-authorization")
        # A bound accessibility service prevents am kill from killing a cached
        # process. Disable it first; do not use force-stop (which discards the task).
        self.set_service(False)
        self.assert_android_access(False)
        self.shell("input", "keyevent", "KEYCODE_HOME")
        old_pid = self.shell("pidof", PACKAGE).stdout.strip()
        require(old_pid, "Airlock process missing before recreation check")
        def kill_cached_process():
            # Unbinding is asynchronous; retry only within the bounded wait.
            self.shell("am", "kill", PACKAGE)
            return not self.shell("pidof", PACKAGE, check=False).stdout.strip()
        self.wait(kill_cached_process,
                  "am kill did not terminate Airlock; cannot validate process recreation")
        self.launch_task()
        self.wait_id("content_filter_locked")
        require(node_by_id(self.ui(), "content_filter_status") is None,
                "Process recreation reused stale authorization")
        require(self.shell("pidof", PACKAGE).stdout.strip() != old_pid,
                "Process recreation did not create a new process")
        self.assert_preferences(["youtube_shorts"], True)
        self.unlock_editor()
        self.assert_android_access(False)
        require(self.find("content_filter_youtube_shorts", "content_filter_scroll")
                .get("checked") == "true", "Selection was not restored after process death")
        self.checkpoint("withdraw-consent-persisted-selection")
        self.set_service(True)
        self.assert_android_access(True)
        self.tap("content_filter_consent", "content_filter_scroll")
        self.assert_preferences(["youtube_shorts"], False)
        self.text_contains("content_filter_consent", "Review disclosure")
        self.assert_android_access(True)
        self.assert_subscription({PACKAGE + ".content_filter_disabled"})
        self.checkpoint("external-unexported-activity")
        output = self.shell("am", "start", "-W", "-n", EDITOR,
                            "--es", "com.dankhole.airlock.CONTENT_FILTER_AUTHORIZATION_TOKEN",
                            "invalid-smoke-token", check=False)
        require(re.search(r"Permission Denial|not exported", output.stdout + output.stderr,
                          re.IGNORECASE) is not None,
                "External launch did not explicitly reject the unexported settings activity")
        self.results.append({"scenario": self.scenario, "result": "passed"})

    def restore(self):
        self.restoring = True
        errors = []

        def attempt(label, action):
            try:
                action()
            except Exception:
                errors.append(label)  # No raw command output or private values.
        if self.fixture_touched:
            attempt("fixture reset", lambda: self.fixture("reset"))
            attempt("force-stop fixture app", lambda: self.shell("am", "force-stop", PACKAGE))
        for operation, mode in self.original_ops.items():
            attempt("restore " + operation, lambda op=operation, value=mode:
                    self.shell("appops", "set", "--uid", PACKAGE, op, value))
        if self.notification_granted is not None:
            attempt("restore notification permission", lambda: self.shell(
                "pm", "grant" if self.notification_granted else "revoke", PACKAGE,
                "android.permission.POST_NOTIFICATIONS"))
        # Restore the enabled-service list before the global enabled flag.
        for (table, key), value in self.original_settings.items():
            attempt("restore " + key, lambda t=table, k=key, v=value: self.set_setting(t, k, v))
            attempt("verify " + key, lambda t=table, k=key, v=value:
                    require(self.setting(t, k) == v, "Setting restoration mismatch"))
        if self.device_verified:
            attempt("remove temporary UI captures", lambda: self.shell(
                "rm", "-f", self.remote_xml, self.remote_screenshot))
        return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    default_adb = os.environ.get("ADB") or str(Path(os.environ.get(
        "ANDROID_HOME", str(Path.home() / "Library/Android/sdk"))) / "platform-tools/adb")
    parser.add_argument("--adb", default=default_adb)
    parser.add_argument("--timeout", type=int, default=600, help="overall run deadline in seconds (120–900)")
    args = parser.parse_args()
    if not 120 <= args.timeout <= 900:
        parser.error("--timeout must be between 120 and 900")
    smoke = Smoke(args.adb, args.timeout)
    outcome, error = "failed", None

    def interrupted(signum, frame):
        raise SmokeFailure("Interrupted; restoring changed Android access/settings")

    old_handlers = {sig: signal.signal(sig, interrupted) for sig in (signal.SIGINT, signal.SIGTERM)}
    try:
        smoke.run()
        outcome = "passed"
    except (SmokeFailure, OSError, ET.ParseError) as exc:
        # Exception text is our own bounded diagnostic, not adb output.
        error = str(exc) if isinstance(exc, SmokeFailure) else type(exc).__name__
    finally:
        # A second Ctrl-C must not skip restoration halfway through.
        for sig in old_handlers:
            signal.signal(sig, signal.SIG_IGN)
        restore_errors = smoke.restore()
        for sig, handler in old_handlers.items():
            signal.signal(sig, handler)
        if restore_errors:
            outcome = "failed"
        summary = {"result": outcome, "scenario": smoke.scenario, "error": error,
                   "checks": smoke.results,
                   "restoration_errors": restore_errors,
                   "limits": ["Settings/auth/consent and Android service subscription smoke only",
                              "No real target-app selectors, inspection count, Back actions or physical-device reliability validated",
                              "Native wrong-PIN error text is screenshot evidence, not an automated XML text assertion",
                              "Fixture app-local state is reset, not backed up/restored"]}
        (smoke.report / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print("Content-filter smoke " + outcome + ". Reports: " + str(smoke.report))
    if error:
        print("Scenario " + smoke.scenario + ": " + error, file=sys.stderr)
    if restore_errors:
        print("Restoration failed: " + ", ".join(restore_errors), file=sys.stderr)
    return 0 if outcome == "passed" else 1


if __name__ == "__main__":
    sys.exit(main())
