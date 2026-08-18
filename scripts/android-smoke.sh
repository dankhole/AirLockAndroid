#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ADB="${ADB:-${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb}"
PACKAGE="com.dankhole.airlock"
COMPONENT_NAMESPACE="com.dankhole.airlockandroid"
TARGET_PACKAGE="${TARGET_PACKAGE:-com.google.android.youtube}"
TARGET_QUERY="${TARGET_QUERY:-YouTube}"
NAVIGATION_MATRIX="${NAVIGATION_MATRIX:-true}"
NAVIGATION_ONLY="${NAVIGATION_ONLY:-false}"
RELEASE_VALIDATION="${RELEASE_VALIDATION:-false}"
APK="$ROOT_DIR/app/build/outputs/apk/debug/app-debug.apk"
STAMP="$(date +%Y%m%d-%H%M%S)"
REPORT_DIR="$ROOT_DIR/app/build/reports/android-smoke/$STAMP"
REMOTE_XML="/sdcard/airlock-smoke.xml"
REMOTE_SCREENSHOT="/sdcard/airlock-smoke.png"
CURRENT_SCENARIO="startup"
LATEST_XML="$REPORT_DIR/latest.xml"

mkdir -p "$REPORT_DIR"

fail() {
    printf 'FAIL: %s\n' "$*" >&2
    return 1
}

adb_e() {
    "$ADB" -e "$@"
}

emulator_count="$($ADB devices | awk '$1 ~ /^emulator-/ && $2 == "device" { count++ } END { print count + 0 }')"
if [[ "$emulator_count" != "1" ]]; then
    fail "Expected exactly one running emulator, found $emulator_count. Physical devices are never targeted."
fi
adb_e get-state >/dev/null
SCREEN_SIZE="$(adb_e shell wm size | sed -n 's/.*Physical size: \([0-9][0-9]*x[0-9][0-9]*\).*/\1/p' | head -1 | tr -d '\r')"
SCREEN_WIDTH="${SCREEN_SIZE%x*}"
SCREEN_HEIGHT="${SCREEN_SIZE#*x}"
[[ -n "$SCREEN_WIDTH" && -n "$SCREEN_HEIGHT" ]] || fail "Could not determine emulator size."

if ! command -v xmllint >/dev/null 2>&1; then
    fail "xmllint is required to inspect uiautomator output."
fi

read_setting() {
    adb_e shell settings get "$1" "$2" | tr -d '\r'
}

restore_setting() {
    local namespace="$1"
    local key="$2"
    local value="$3"
    if [[ -z "$value" || "$value" == "null" ]]; then
        adb_e shell settings delete "$namespace" "$key" >/dev/null
    else
        adb_e shell settings put "$namespace" "$key" "$value" >/dev/null
    fi
}

appop_mode() {
    local operation="$1"
    local mode
    mode="$(adb_e shell appops get "$PACKAGE" "$operation" 2>/dev/null \
        | sed -n 's/.*: \([a-z][a-z]*\).*/\1/p' | head -1 | tr -d '\r')"
    printf '%s' "${mode:-default}"
}

ORIGINAL_ROTATION="$(read_setting system user_rotation)"
ORIGINAL_ACCELEROMETER="$(read_setting system accelerometer_rotation)"
ORIGINAL_FONT_SCALE="$(read_setting system font_scale)"
ORIGINAL_WINDOW_ANIMATION="$(read_setting global window_animation_scale)"
ORIGINAL_TRANSITION_ANIMATION="$(read_setting global transition_animation_scale)"
ORIGINAL_ANIMATOR_DURATION="$(read_setting global animator_duration_scale)"
ORIGINAL_NAVIGATION_OVERLAY="$(adb_e shell cmd overlay list 2>/dev/null \
    | sed -n 's/^\[x\] \(com\.android\.internal\.systemui\.navbar\.[^[:space:]]*\).*/\1/p' \
    | head -1 | tr -d '\r')"
ORIGINAL_OVERRIDE_SIZE="$(adb_e shell wm size | sed -n 's/.*Override size: \([0-9][0-9]*x[0-9][0-9]*\).*/\1/p' | head -1 | tr -d '\r')"
ORIGINAL_OVERRIDE_DENSITY="$(adb_e shell wm density | sed -n 's/.*Override density: \([0-9][0-9]*\).*/\1/p' | head -1 | tr -d '\r')"
ORIGINAL_USAGE_OP="default"
ORIGINAL_OVERLAY_OP="default"
ORIGINAL_NOTIFICATION_STATE="denied"
ORIGINAL_TARGET_NOTIFICATION_STATE="denied"
TARGET_NOTIFICATION_MANAGED=false
APP_INSTALLED=false

dump_ui() {
    local name="$1"
    local destination="$REPORT_DIR/$name.xml"
    adb_e shell uiautomator dump "$REMOTE_XML" >/dev/null
    adb_e pull "$REMOTE_XML" "$destination" >/dev/null 2>&1
    cp "$destination" "$LATEST_XML"
}

wait_for_id() {
    local id="$1"
    local timeout_seconds="${2:-15}"
    local attempt
    for ((attempt = 0; attempt < timeout_seconds * 2; attempt++)); do
        if dump_ui "wait-$id" 2>/dev/null \
                && grep -q "resource-id=\"$PACKAGE:id/$id\"" "$LATEST_XML"; then
            return 0
        fi
        sleep 0.5
    done
    fail "Timed out waiting for resource ID $id during $CURRENT_SCENARIO"
}

wait_for_id_absent() {
    local id="$1"
    local timeout_seconds="${2:-15}"
    local attempt
    for ((attempt = 0; attempt < timeout_seconds * 2; attempt++)); do
        if dump_ui "wait-absent-$id" 2>/dev/null \
                && ! grep -q "resource-id=\"$PACKAGE:id/$id\"" "$LATEST_XML"; then
            return 0
        fi
        sleep 0.5
    done
    fail "Timed out waiting for resource ID $id to disappear during $CURRENT_SCENARIO"
}

wait_for_log() {
    local expected="$1"
    local timeout_seconds="${2:-10}"
    local attempt
    for ((attempt = 0; attempt < timeout_seconds * 5; attempt++)); do
        if adb_e logcat -d -v brief AirLockMonitor:D '*:S' 2>/dev/null \
                | grep -Fq "$expected"; then
            return 0
        fi
        sleep 0.2
    done
    fail "Timed out waiting for log '$expected' during $CURRENT_SCENARIO"
}

view_attribute() {
    local id="$1"
    local attribute="$2"
    xmllint --xpath \
        "string((//node[@resource-id='$PACKAGE:id/$id'])[1]/@$attribute)" \
        "$LATEST_XML" 2>/dev/null
}

assert_id_contains() {
    local id="$1"
    local expected="$2"
    dump_ui "assert-$id"
    local value
    value="$(view_attribute "$id" text) $(view_attribute "$id" content-desc)"
    if [[ "$value" != *"$expected"* ]]; then
        fail "$id did not contain '$expected'; actual value: $value"
    fi
}

tap_id() {
    local id="$1"
    local attempt bounds coordinates left top right bottom
    for ((attempt = 0; attempt < 10; attempt++)); do
        dump_ui "tap-$id"
        bounds="$(view_attribute "$id" bounds)"
        if [[ "$bounds" =~ ^\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]$ ]]; then
            coordinates="$(printf '%s' "$bounds" | sed 's/\]\[/,/' | tr -d '[]')"
            IFS=',' read -r left top right bottom <<< "$coordinates"
            if ((right > left && bottom > top && top < SCREEN_HEIGHT)); then
                adb_e shell input tap "$(((left + right) / 2))" "$(((top + bottom) / 2))"
                return 0
            fi
        fi
        adb_e shell input swipe \
            "$((SCREEN_WIDTH / 2))" "$((SCREEN_HEIGHT * 4 / 5))" \
            "$((SCREEN_WIDTH / 2))" "$((SCREEN_HEIGHT / 3))" 250
        sleep 0.3
    done
    fail "Could not bring $id on screen during $CURRENT_SCENARIO"
}

type_id() {
    local id="$1"
    local value="$2"
    wait_for_id "$id"
    tap_id "$id"
    adb_e shell input text "$value"
}

plus_five_code() {
    local request_code="$1"
    local result=""
    local index digit
    for ((index = 0; index < ${#request_code}; index++)); do
        digit="${request_code:index:1}"
        if [[ ! "$digit" =~ ^[0-9]$ ]]; then
            fail "Request code is not numeric: $request_code"
            return 1
        fi
        result+="$(((digit + 5) % 10))"
    done
    printf '%s' "$result"
}

fixture() {
    local output
    output="$(adb_e shell am broadcast \
        -n "$PACKAGE/$COMPONENT_NAMESPACE.DebugFixtureReceiver" \
        --es command "$1" "${@:2}")"
    if [[ "$output" != *"result=-1"* ]]; then
        fail "Debug fixture failed: $output"
        return 1
    fi
    printf '%s' "$output"
}

launch_main() {
    adb_e shell am start -W -n "$PACKAGE/$COMPONENT_NAMESPACE.MainActivity" >/dev/null
    wait_for_id main_scroll
}

set_portrait() {
    adb_e shell settings put system accelerometer_rotation 0 >/dev/null
    adb_e shell settings put system user_rotation 0 >/dev/null
}

set_navigation_overlay() {
    local overlay="$1"
    local attempt
    [[ -n "$overlay" ]] || return 0
    adb_e shell cmd overlay enable-exclusive --category "$overlay" >/dev/null
    for ((attempt = 0; attempt < 15; attempt++)); do
        if adb_e shell cmd overlay list 2>/dev/null \
                | grep -Fq "[x] $overlay"; then
            return 0
        fi
        sleep 0.2
    done
    fail "Timed out enabling navigation overlay $overlay"
}

assert_blocker_clear_of_navigation_bar() {
    local bounds bottom
    bounds="$(view_attribute blocker_root bounds)"
    if [[ ! "$bounds" =~ ^\[[0-9]+,[0-9]+\]\[[0-9]+,([0-9]+)\]$ ]]; then
        fail "Could not read blocker bounds during $CURRENT_SCENARIO: $bounds"
        return 1
    fi
    bottom="${BASH_REMATCH[1]}"
    if ((bottom >= SCREEN_HEIGHT)); then
        fail "Blocker extends through the navigation region: $bounds on ${SCREEN_WIDTH}x${SCREEN_HEIGHT}"
    fi
}

run_blocker_navigation_scenario() {
    local navigation_overlay="$1"
    local mode_name="${navigation_overlay##*.}"
    local sanity_token startup_token home_token
    [[ -n "$mode_name" ]] || mode_name="current"
    CURRENT_SCENARIO="blocker-navigation-$mode_name"

    set_navigation_overlay "$navigation_overlay"
    fixture seed --es target_package "$TARGET_PACKAGE" --ez monitoring true >/dev/null
    adb_e shell am start -W -n "$PACKAGE/$COMPONENT_NAMESPACE.MainActivity" >/dev/null
    startup_token="startup-$mode_name-$RANDOM-$RANDOM"
    fixture force_foreground_sanity --es sanity_token "$startup_token" >/dev/null
    wait_for_log "debug foreground sanity check completed token=$startup_token" 10
    adb_e shell monkey -p "$TARGET_PACKAGE" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
    wait_for_id blocker_root 20
    assert_blocker_clear_of_navigation_bar

    adb_e shell input keyevent KEYCODE_APP_SWITCH
    wait_for_id_absent blocker_root 5
    sanity_token="$mode_name-$RANDOM-$RANDOM"
    fixture force_foreground_sanity --es sanity_token "$sanity_token" >/dev/null
    wait_for_log "debug foreground sanity check completed token=$sanity_token" 10
    wait_for_id_absent blocker_root 5
    capture_current_artifacts "$CURRENT_SCENARIO-recents"

    adb_e shell input keyevent KEYCODE_HOME
    wait_for_id_absent blocker_root 5
    home_token="home-$mode_name-$RANDOM-$RANDOM"
    fixture force_foreground_sanity --es sanity_token "$home_token" >/dev/null
    wait_for_log "debug foreground sanity check completed token=$home_token" 10
    wait_for_id_absent blocker_root 5
    adb_e shell monkey -p "$TARGET_PACKAGE" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
    wait_for_id blocker_root 20

    adb_e shell am start -W -n "$PACKAGE/$COMPONENT_NAMESPACE.MainActivity" >/dev/null
    wait_for_id_absent blocker_root 5
    adb_e shell monkey -p "$TARGET_PACKAGE" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
    wait_for_id blocker_root 20

    adb_e shell input keyevent KEYCODE_BACK
    wait_for_id_absent blocker_root 10
    capture_current_artifacts "$CURRENT_SCENARIO-back"
}

run_blocker_navigation_matrix() {
    local navigation_overlay
    local navigation_overlays=("$ORIGINAL_NAVIGATION_OVERLAY")
    if [[ "$NAVIGATION_MATRIX" == "true" ]]; then
        while IFS= read -r navigation_overlay; do
            [[ -n "$navigation_overlay" ]] || continue
            if [[ " ${navigation_overlays[*]} " != *" $navigation_overlay "* ]]; then
                navigation_overlays+=("$navigation_overlay")
            fi
        done < <(adb_e shell cmd overlay list 2>/dev/null \
            | sed -n 's/^\[[x ]\] \(com\.android\.internal\.systemui\.navbar\.[^[:space:]]*\).*/\1/p' \
            | tr -d '\r' \
            | grep -E '\.(gestural|threebutton)$' || true)
    fi
    for navigation_overlay in "${navigation_overlays[@]}"; do
        run_blocker_navigation_scenario "$navigation_overlay"
    done
    set_navigation_overlay "$ORIGINAL_NAVIGATION_OVERLAY"
}

capture_artifacts() {
    local name="$1"
    dump_ui "$name" >/dev/null 2>&1 || true
    adb_e shell screencap -p "$REMOTE_SCREENSHOT" >/dev/null 2>&1 || true
    adb_e pull "$REMOTE_SCREENSHOT" "$REPORT_DIR/$name.png" >/dev/null 2>&1 || true
    adb_e logcat -d -v threadtime >"$REPORT_DIR/$name.log" 2>/dev/null || true
}

capture_current_artifacts() {
    local name="$1"
    if [[ -f "$LATEST_XML" ]]; then
        cp "$LATEST_XML" "$REPORT_DIR/$name.xml"
    fi
    adb_e shell screencap -p "$REMOTE_SCREENSHOT" >/dev/null 2>&1 || true
    adb_e pull "$REMOTE_SCREENSHOT" "$REPORT_DIR/$name.png" >/dev/null 2>&1 || true
    adb_e logcat -d -v threadtime >"$REPORT_DIR/$name.log" 2>/dev/null || true
}

check_logs() {
    adb_e logcat -d -v threadtime >"$REPORT_DIR/final.log"
    if grep -A4 "FATAL EXCEPTION" "$REPORT_DIR/final.log" \
            | grep -q "Process: $PACKAGE" \
            || grep -q "ANR in $PACKAGE" "$REPORT_DIR/final.log"; then
        fail "Crash or ANR detected; see $REPORT_DIR/final.log"
    fi
    local overlay_changes
    overlay_changes="$(grep -Ec "overlay added|hiding overlay" "$REPORT_DIR/final.log" || true)"
    if ((overlay_changes > 20)); then
        fail "Possible overlay add/remove loop detected ($overlay_changes transitions)."
    fi
}

finish() {
    local exit_code="$1"
    set +e
    if ((exit_code != 0)); then
        capture_artifacts "FAILED-$CURRENT_SCENARIO"
    fi
    if [[ "$APP_INSTALLED" == true ]]; then
        fixture reset >/dev/null 2>&1
        adb_e shell am force-stop "$PACKAGE" >/dev/null 2>&1
        adb_e shell appops set --uid "$PACKAGE" GET_USAGE_STATS "$ORIGINAL_USAGE_OP" >/dev/null 2>&1
        adb_e shell appops set --uid "$PACKAGE" SYSTEM_ALERT_WINDOW "$ORIGINAL_OVERLAY_OP" >/dev/null 2>&1
        if [[ "$ORIGINAL_NOTIFICATION_STATE" == "granted" ]]; then
            adb_e shell pm grant "$PACKAGE" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1
        else
            adb_e shell pm revoke "$PACKAGE" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1
        fi
    fi
    if [[ "$TARGET_NOTIFICATION_MANAGED" == true ]]; then
        if [[ "$ORIGINAL_TARGET_NOTIFICATION_STATE" == "granted" ]]; then
            adb_e shell pm grant "$TARGET_PACKAGE" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1
        else
            adb_e shell pm revoke "$TARGET_PACKAGE" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1
        fi
    fi
    restore_setting system user_rotation "$ORIGINAL_ROTATION"
    restore_setting system accelerometer_rotation "$ORIGINAL_ACCELEROMETER"
    restore_setting system font_scale "$ORIGINAL_FONT_SCALE"
    restore_setting global window_animation_scale "$ORIGINAL_WINDOW_ANIMATION"
    restore_setting global transition_animation_scale "$ORIGINAL_TRANSITION_ANIMATION"
    restore_setting global animator_duration_scale "$ORIGINAL_ANIMATOR_DURATION"
    if [[ -n "$ORIGINAL_NAVIGATION_OVERLAY" ]]; then
        set_navigation_overlay "$ORIGINAL_NAVIGATION_OVERLAY" >/dev/null 2>&1 || true
    fi
    if [[ -n "$ORIGINAL_OVERRIDE_SIZE" ]]; then
        adb_e shell wm size "$ORIGINAL_OVERRIDE_SIZE" >/dev/null 2>&1
    else
        adb_e shell wm size reset >/dev/null 2>&1
    fi
    if [[ -n "$ORIGINAL_OVERRIDE_DENSITY" ]]; then
        adb_e shell wm density "$ORIGINAL_OVERRIDE_DENSITY" >/dev/null 2>&1
    else
        adb_e shell wm density reset >/dev/null 2>&1
    fi
    if ((exit_code == 0)); then
        printf 'Android smoke suite passed. Artifacts: %s\n' "$REPORT_DIR"
    else
        printf 'Android smoke suite failed in %s. Artifacts: %s\n' \
            "$CURRENT_SCENARIO" "$REPORT_DIR" >&2
    fi
    exit "$exit_code"
}
trap 'finish $?' EXIT

restore_setting system font_scale 1.0
restore_setting global window_animation_scale 0
restore_setting global transition_animation_scale 0
restore_setting global animator_duration_scale 0
set_portrait

if [[ "${1:-}" != "--skip-build" ]]; then
    (cd "$ROOT_DIR" && ./gradlew :app:testDebugUnitTest :app:assembleDebug)
fi
[[ -f "$APK" ]] || fail "Debug APK not found at $APK"
adb_e install -r "$APK" >/dev/null
APP_INSTALLED=true
ORIGINAL_USAGE_OP="$(appop_mode GET_USAGE_STATS)"
ORIGINAL_OVERLAY_OP="$(appop_mode SYSTEM_ALERT_WINDOW)"
if adb_e shell dumpsys package "$PACKAGE" \
        | grep -q "android.permission.POST_NOTIFICATIONS: granted=true"; then
    ORIGINAL_NOTIFICATION_STATE="granted"
fi
TARGET_PATH="$(adb_e shell pm path "$TARGET_PACKAGE" | tr -d '\r')"
[[ -n "$TARGET_PATH" ]] \
    || fail "Target app $TARGET_PACKAGE is not installed. Set TARGET_PACKAGE and TARGET_QUERY."
if adb_e shell dumpsys package "$TARGET_PACKAGE" \
        | grep -q "android.permission.POST_NOTIFICATIONS: granted=true"; then
    ORIGINAL_TARGET_NOTIFICATION_STATE="granted"
fi
if adb_e shell pm grant "$TARGET_PACKAGE" \
        android.permission.POST_NOTIFICATIONS >/dev/null 2>&1; then
    TARGET_NOTIFICATION_MANAGED=true
fi
adb_e logcat -c

if [[ "$NAVIGATION_ONLY" == "true" ]]; then
    adb_e shell appops set --uid "$PACKAGE" GET_USAGE_STATS allow
    adb_e shell appops set --uid "$PACKAGE" SYSTEM_ALERT_WINDOW allow
    adb_e shell pm grant "$PACKAGE" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
    run_blocker_navigation_matrix
    check_logs
    exit 0
fi

if [[ "$RELEASE_VALIDATION" == "true" ]]; then
    adb_e shell appops set --uid "$PACKAGE" GET_USAGE_STATS allow
    adb_e shell appops set --uid "$PACKAGE" SYSTEM_ALERT_WINDOW allow
    adb_e shell pm grant "$PACKAGE" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
    run_blocker_navigation_matrix
    check_logs
    adb_e logcat -c
fi

CURRENT_SCENARIO="required-setup"
fixture reset >/dev/null
adb_e shell appops set --uid "$PACKAGE" GET_USAGE_STATS ignore
adb_e shell appops set --uid "$PACKAGE" SYSTEM_ALERT_WINDOW ignore
adb_e shell pm revoke "$PACKAGE" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
adb_e shell am force-stop "$PACKAGE"
adb_e shell am start -W -n "$PACKAGE/$COMPONENT_NAMESPACE.MainActivity" >/dev/null
wait_for_id permission_setup_scroll
assert_id_contains permission_setup_progress "0 of 3"
assert_id_contains permission_usage_status "NOT DONE"
assert_id_contains permission_overlay_status "NOT DONE"
tap_id permission_notifications_status
assert_id_contains permission_notifications_status "NOT DONE"
capture_artifacts "$CURRENT_SCENARIO-gate"

adb_e shell appops set --uid "$PACKAGE" GET_USAGE_STATS allow
adb_e shell am force-stop "$PACKAGE"
adb_e shell am start -W -n "$PACKAGE/$COMPONENT_NAMESPACE.MainActivity" >/dev/null
wait_for_id permission_setup_scroll
assert_id_contains permission_setup_progress "1 of 3"
assert_id_contains permission_usage_status DONE

adb_e shell appops set --uid "$PACKAGE" SYSTEM_ALERT_WINDOW allow
adb_e shell am force-stop "$PACKAGE"
adb_e shell am start -W -n "$PACKAGE/$COMPONENT_NAMESPACE.MainActivity" >/dev/null
wait_for_id permission_setup_scroll
assert_id_contains permission_setup_progress "2 of 3"
tap_id permission_overlay_status
assert_id_contains permission_overlay_status DONE

adb_e shell pm grant "$PACKAGE" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1
adb_e shell am force-stop "$PACKAGE"
launch_main
assert_id_contains main_monitoring_controls_status LOCKED
assert_id_contains main_monitoring_controls_status Keyholder
capture_artifacts "$CURRENT_SCENARIO-dashboard"

adb_e shell appops set --uid "$PACKAGE" SYSTEM_ALERT_WINDOW ignore
adb_e shell am force-stop "$PACKAGE"
adb_e shell am start -W -n "$PACKAGE/$COMPONENT_NAMESPACE.MainActivity" >/dev/null
wait_for_id permission_setup_scroll
tap_id permission_overlay_status
assert_id_contains permission_overlay_status "NOT DONE"
adb_e shell appops set --uid "$PACKAGE" SYSTEM_ALERT_WINDOW allow

CURRENT_SCENARIO="app-limit-wizard"
adb_e shell appops set --uid "$PACKAGE" GET_USAGE_STATS allow
adb_e shell appops set --uid "$PACKAGE" SYSTEM_ALERT_WINDOW allow
adb_e shell pm grant "$PACKAGE" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
fixture seed --es target_package "$TARGET_PACKAGE" --ez monitoring false >/dev/null
adb_e shell am force-stop "$PACKAGE"
launch_main
tap_id main_usage_status
assert_id_contains main_usage_status "duty is OFF"
tap_id usage_app_enforcement_status
assert_id_contains usage_app_enforcement_status "not being blocked"
tap_id main_app_limits_button
wait_for_id app_picker_search 30
type_id app_picker_search "$TARGET_QUERY"
adb_e shell input keyevent 4
wait_for_id app_picker_row 20
tap_id app_picker_row
assert_id_contains app_picker_selection_status READY
adb_e shell settings put system user_rotation 1
wait_for_id app_picker_search 30
assert_id_contains app_picker_search "$TARGET_QUERY"
assert_id_contains app_picker_selection_status READY
capture_artifacts "app-picker-selected"
set_portrait
tap_id app_picker_continue
wait_for_id app_limit_minutes
type_id app_limit_minutes 37
adb_e shell input keyevent 4
adb_e shell settings put system user_rotation 1
wait_for_id app_limit_minutes
assert_id_contains app_limit_minutes 37
set_portrait
capture_artifacts "$CURRENT_SCENARIO"

CURRENT_SCENARIO="live-blocker"
fixture seed --es target_package "$TARGET_PACKAGE" --ez monitoring true >/dev/null
adb_e shell am force-stop "$PACKAGE"
launch_main
sleep 2
adb_e shell monkey -p "$TARGET_PACKAGE" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
wait_for_id blocker_root 20
tap_id blocker_unlock
wait_for_id blocker_error
assert_id_contains blocker_error REQUIRED
type_id blocker_minutes 9
type_id blocker_approval_code 123
adb_e shell input keyevent 3
adb_e shell monkey -p "$TARGET_PACKAGE" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
wait_for_id blocker_root 20
assert_id_contains blocker_minutes 9
assert_id_contains blocker_approval_code 123
tap_id blocker_unlock
wait_for_id blocker_error
assert_id_contains blocker_error REQUIRED
tap_id blocker_emergency_toggle
wait_for_id blocker_emergency_hint
assert_id_contains blocker_emergency_hint 24
capture_artifacts "$CURRENT_SCENARIO"

CURRENT_SCENARIO="approval-duration-and-celebration"
fixture_output="$(fixture seed --es target_package "$TARGET_PACKAGE" \
    --ei request_minutes 7 --ez monitoring false)"
request_code="$(printf '%s' "$fixture_output" \
    | sed -n 's/.*request=\([0-9][0-9]*\).*/\1/p')"
approval_code="$(printf '%s' "$fixture_output" \
    | sed -n 's/.*approval=\([0-9][0-9]*\).*/\1/p')"
[[ -n "$request_code" ]] || fail "Fixture did not return a request code: $fixture_output"
[[ -n "$approval_code" ]] || fail "Fixture did not return an approval code: $fixture_output"
expected_approval_code="$(plus_five_code "$request_code")"
[[ "$approval_code" == "$expected_approval_code" ]] \
    || fail "Approval code $approval_code did not follow the +5 rule for $request_code"
adb_e shell am force-stop "$PACKAGE"
adb_e shell am start -W -n "$PACKAGE/$COMPONENT_NAMESPACE.DebugBlockerActivity" \
    --es target_package "$TARGET_PACKAGE" --es app_label "$TARGET_QUERY" \
    --ei used_minutes 2 --ei limit_minutes 1 >/dev/null
wait_for_id blocker_approval_code
type_id blocker_approval_code "$expected_approval_code"
tap_id blocker_unlock
wait_for_id blocker_celebration 10
assert_id_contains blocker_summary "7 minutes"
capture_artifacts "$CURRENT_SCENARIO"

check_logs
