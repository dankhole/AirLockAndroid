# Development Guide

Last updated: August 11, 2026

## Status

This is an internal-testing candidate, not a verified release. JVM tests,
debug/release assembly, bundle generation, lint, and the batched emulator smoke
flow work locally. The physical-device matrix still must pass before release
readiness is claimed. See `docs/PROJECT_STATUS.md` for the current handoff.

## Local Setup

1. Install Android Studio.
2. Install Android SDK Platform 36 and Android SDK Build-Tools 35 or newer.
3. Use JDK 17 or 21.
4. Open `/Users/d.cole/Desktop/Projects/AirLockAndroid`.
5. Let Gradle sync.
6. Run `app` on a physical Android device.

Use the checked-in Gradle 8.11.1 wrapper:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

The app compiles and targets Android 16 (`compileSdk 36`, `targetSdk 36`) using
Android Gradle Plugin 8.10.1. Google Play requires new apps and app updates to
target API 36 or higher starting August 31, 2026:
<https://support.google.com/googleplay/android-developer/answer/11926878>.

## Coding Conventions

- Keep the MVP native and dependency-light until the first real device test passes.
- Prefer Android platform APIs over adding libraries.
- Keep test-only dependencies out of the packaged application; JUnit 4 backs
  the current pure-Java unit suite.
- Keep app usage data local by default.
- Keep settings and blocker behavior explicit; hidden hardening is a trust problem.
- Do not request permissions before the UI explains why they are needed.
- Do not request direct SMS permissions for the default MVP path.
- Do not request Accessibility Service for whole-app blocking.

## Engineering Priority Order

AirLock is useful only while monitoring remains trustworthy over ordinary
multi-day use. Apply these priorities, in order, when making tradeoffs:

1. Long-running monitoring reliability and automatic recovery.
2. Visible, accurate health and permission state; never silently fail open.
3. Prompt blocker appearance during normal and gesture-based app switching.
4. Low idle battery, CPU, and storage cost through bounded adaptive work.
5. Correct limits, usage totals, override grants, and persisted state.
6. Maintainable native Java implementation and accessible visual polish.

Reliability work should favor idempotent startup, persisted checkpoints,
periodic reconciliation, and bounded recovery windows. Avoid permanent fast
polling, frequent synchronous preference writes, unbounded wake locks, and
restart loops. Android and manufacturer background restrictions that cannot be
fixed in-app must be detected where possible, shown as degraded health, and
covered in `docs/TEST_PLAN.md`.

## Programmatic UI Styling

This app uses platform Java views, so shared styling lives in `UiStyle.java`.
New UI should use that helper for colors, spacing, text styles, cards, buttons,
input fields, status messages, selectable rows, overlay controls, and system
inset padding. Avoid hardcoded colors, corner radii, button backgrounds, and
screen padding in activities or services unless a new reusable style is first
added to `UiStyle`.

The green palette is one tonal family in `UiStyle`: `COLOR_PRIMARY` for main
actions, `COLOR_PRIMARY_DEEP` for secondary actions, `COLOR_PRIMARY_PRESSED`
for pressed state, `COLOR_PRIMARY_SOFT` for themed surfaces, and
`COLOR_PRIMARY_BRIGHT` for ready/status emphasis. Android XML accents use the
matching `airlock_primary` value. Do not introduce a separate green directly in
Java, XML, or Canvas drawing.

User-facing copy belongs in `app/src/main/res/values/strings.xml`, including
formatted status messages and plurals. Java should pass resource values into
the `UiStyle` builders instead of assembling display text in `setText(...)`.

Use the named button styles by intent:

- `primaryButton`: main forward action.
- `secondaryButton`: alternate or setup action.
- `dangerButton`: destructive or stop action.
- `overlaySecondaryButton`: secondary action on the dark blocking overlay.

Requirement and error states should use `statusText` with `setStatus(...)` so
users get text, color, and shape cues together. Screens that can touch the
status bar, navigation bar, or camera cutout should use `screenScroll(...)` and
apply `applyScreenInsetsPadding(...)` to the scroll viewport and its content,
then attach that content with `attachScreenContent(...)`. The shared host
centers content at a readable maximum width on tablets and landscape screens.
This keeps scrolled controls clipped outside system bar and cutout regions.
Overlay content uses `attachOverlayContent(...)`, and overlay windows must also
use `overlayWindowRoot(...)`; its neutral system-bar
scrims preserve icon contrast when the underlying app controls icon appearance.

`PermissionSetupScreen` owns the required first-run access gate. Usage Access,
Display Over Other Apps, and notification visibility must all be ready before
`MainActivity` reveals the dashboard. `MainActivity.refresh()` rechecks that
policy on every resume, so revoked access returns to the gate. Keep battery
restriction as a dashboard reliability warning because OEM battery controls are
not a consistently verifiable runtime permission.

The foreground-service channel is intentionally silent. Keep its low importance,
null sound, disabled vibration, and zero notification defaults when changing
monitoring notifications. A channel-ID migration is required if a future release
needs to change immutable channel behavior for existing installs.

## Goose Visual Theme

Goose-themed visuals are plain Java `View` classes with Canvas drawing, not
image dependencies. Use `GooseMascotView` with `UiStyle.gooseBannerParams(...)`
near the top of setup/wizard screens. `GooseCelebrationView` is reserved for
the successful extra-time unlock animation so that moment feels distinct.
Follow `docs/PRODUCT_LANGUAGE.md` for the roles of AirLock, the Goose, and the
Keyholder and for all user-facing copy.

## Icon Assets

The launcher and monitoring notification use Google's Material Symbols
`lock_clock` icon as an Android vector drawable. Material Symbols are licensed
under Apache License 2.0 and can be found in the Google Material icons
repository: <https://github.com/google/material-design-icons>.

## Usage Tracking

The main screen's "Today's goose count" section only renders apps that already have
AirLock limits. Foreground detection and full-day reconciliation run on separate
background workers. The service keeps current usage in memory, persists dirty
totals in one batch every 30 seconds, reconciles against Android UsageStats once
per minute, and retains seven days of usage keys. Foreground queries and
reconciliation pause while the screen is off or locked, then resume immediately
after unlock. See `docs/RELIABILITY.md` before changing service lifecycle,
polling cadence, UsageEvents windows, health state, or restart behavior.

## Permission Notes

### Usage Access

Required for foreground app detection. Users grant it in Settings through `Settings.ACTION_USAGE_ACCESS_SETTINGS`.

### Display Over Other Apps

Required for the blocking wall. Users grant it through `Settings.ACTION_MANAGE_OVERLAY_PERMISSION`.

### Foreground Service

Required because monitoring happens while the app is not foregrounded. The service uses a persistent notification.

### Notifications

Android allows a foreground service to run after Android 13 notification
permission is denied, exposing it only through the system Active apps surface.
AirLock deliberately treats visible notifications as a product setup
requirement so monitoring and recovery status cannot be hidden from the user.
Revocation returns the UI to the access gate while an already-running service
continues best-effort monitoring. Notification readiness checks the runtime
permission where applicable, app-level notification access, and the monitoring
channel instead of assuming pre-Android-13 notifications are on.

## Automated Tests

Run `./gradlew :app:testDebugUnitTest` for the fast local suite. The tests cover
one-use app-limit authorization and expiry, configuration-safe process-local
editor sessions, process-local token loss, bounded
foreground-query concurrency, approval-code duration policy, atomic approval
redemption with multiple pending requests, three-code emergency-batch
replacement and revocation, foreground lifecycle classification, failed-write restoration,
notification visibility policy, and monitoring-exit recovery classification.
These tests do not require an emulator. Batch the device scenarios in
`docs/TEST_PLAN.md` after changes to Android lifecycle, UsageStats, overlays,
permissions, or system settings.

The staged backlog for automating those functional, UI, accessibility, and
reliability checks is in `docs/TEST_AUTOMATION_TODO.md`.

### Emulator Smoke Suite

Run the batched P0 emulator flow after related UI, permission, UsageStats,
service, or overlay changes:

```sh
scripts/android-smoke.sh
```

The runner requires exactly one running emulator and always uses `adb -e`; it
refuses to target physical devices. It builds and installs the debug APK,
resets app-private fixture state, exercises setup gating, picker recreation,
live service blocking, retained blocker input, invalid codes, and the current
request-code `+5` approval rule, then restores emulator settings and app-op
permissions. Use `scripts/android-smoke.sh --skip-build` only after assembling
the current debug APK. Set `TARGET_PACKAGE` and `TARGET_QUERY` when the pinned
emulator does not contain YouTube.

Debug builds alone include `DebugFixtureReceiver` and `DebugBlockerActivity`
for deterministic state and blocker setup. Neither component is merged into
release builds. Smoke artifacts are written under
`app/build/reports/android-smoke/`.

### Local Data And Backup

App limits, usage totals, the Keyholder number, PIN material, approval-code
state, and emergency-code hashes stay in app-private storage. Android cloud
backup and device-to-device transfer are disabled for this data in both legacy
and current backup rules. Do not enable backup without a data migration and
security review.

## Debugging

Useful checks after installing a debug build:

```sh
adb -e shell dumpsys package com.dankhole.airlockandroid
adb -e shell dumpsys usagestats
adb -e shell appops get com.dankhole.airlockandroid
adb -e shell dumpsys activity services com.dankhole.airlockandroid
```

Start and stop from the app UI first. Use `adb -e` for emulator work and
`adb -d` only when intentionally inspecting a connected physical device.

## Build Verification Checklist

- Gradle sync succeeds.
- `:app:testDebugUnitTest` succeeds.
- `:app:assembleDebug` succeeds.
- App installs on a physical device.
- Usage Access grant is detected by the app.
- Overlay grant is detected by the app.
- Notification denial is explained and does not silently stop duty.
- Foreground service notification appears after notification access is granted.
- Critical phone, launcher, settings, messaging, camera, autofill, and Android
  credential-provider apps are absent from the limit picker.
- App selection persists after process restart.
- Blocking overlay appears after limit.
- Code unlock grants temporary extra time.
- Monitoring can be stopped from the app.

## Release Readiness

The authoritative release checklist and current external blockers live in
`docs/RELEASE.md`, `docs/PLAY_CONSOLE_SUBMISSION.md`, and
`docs/PROJECT_STATUS.md`. Keep signing credentials outside Git, verify critical
app exclusions on the release device matrix, and do not treat the transparent
`+5` approval rule as production authentication.
