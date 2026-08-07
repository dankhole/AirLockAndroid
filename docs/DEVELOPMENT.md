# Development Guide

Last updated: August 7, 2026

## Status

This is a compiling Android MVP, not a verified release. The debug build and lint run locally, but the manual test plan still needs physical-device coverage before any product claims are made.

## Local Setup

1. Install Android Studio.
2. Install Android SDK Platform 36 and Android SDK Build-Tools 35 or newer.
3. Use JDK 17 or 21.
4. Open `/Users/d.cole/Desktop/Projects/AirLockAndroid`.
5. Let Gradle sync.
6. Run `app` on a physical Android device.

Use the checked-in Gradle 8.11.1 wrapper:

```sh
./gradlew :app:assembleDebug
```

The app compiles and targets Android 16 (`compileSdk 36`, `targetSdk 36`) using
Android Gradle Plugin 8.10.1. Google Play requires new apps and app updates to
target API 36 or higher starting August 31, 2026:
<https://support.google.com/googleplay/android-developer/answer/11926878>.

## Coding Conventions

- Keep the MVP native and dependency-light until the first real device test passes.
- Prefer Android platform APIs over adding libraries.
- Keep app usage data local by default.
- Keep settings and blocker behavior explicit; hidden hardening is a trust problem.
- Do not request permissions before the UI explains why they are needed.
- Do not request direct SMS permissions for the default MVP path.
- Do not request Accessibility Service for whole-app blocking.

## Programmatic UI Styling

This app uses platform Java views, so shared styling lives in `UiStyle.java`.
New UI should use that helper for colors, spacing, text styles, cards, buttons,
input fields, status messages, selectable rows, overlay controls, and system
inset padding. Avoid hardcoded colors, corner radii, button backgrounds, and
screen padding in activities or services unless a new reusable style is first
added to `UiStyle`.

Use the named button styles by intent:

- `primaryButton`: main forward action.
- `secondaryButton`: alternate or setup action.
- `dangerButton`: destructive or stop action.
- `overlaySecondaryButton`: secondary action on the dark blocking overlay.

Requirement and error states should use `statusText` with `setStatus(...)` so
users get text, color, and shape cues together. Screens that can touch the
status bar, navigation bar, or camera cutout should apply
`applySystemInsetsPadding(...)` to their top-level content container.

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
per minute, and retains seven days of usage keys.

## Permission Notes

### Usage Access

Required for foreground app detection. Users grant it in Settings through `Settings.ACTION_USAGE_ACCESS_SETTINGS`.

### Display Over Other Apps

Required for the blocking wall. Users grant it through `Settings.ACTION_MANAGE_OVERLAY_PERMISSION`.

### Foreground Service

Required because monitoring happens while the app is not foregrounded. The service uses a persistent notification.

### Notifications

Required on Android 13+ so the foreground service notification can be shown cleanly.

## Debugging

Useful checks after installing a debug build:

```sh
adb shell dumpsys package com.dankhole.airlockandroid
adb shell dumpsys usagestats
adb shell appops get com.dankhole.airlockandroid
adb shell dumpsys activity services com.dankhole.airlockandroid
```

Start and stop from the app UI first. Only use `adb` to inspect state until the MVP behavior is confirmed manually.

## Build Verification Checklist

- Gradle sync succeeds.
- `:app:assembleDebug` succeeds.
- App installs on a physical device.
- Usage Access grant is detected by the app.
- Overlay grant is detected by the app.
- Foreground service notification appears.
- App selection persists after process restart.
- Blocking overlay appears after limit.
- Code unlock grants temporary extra time.
- Monitoring can be stopped from the app.

## Release Readiness Checklist

Do not treat this as releasable until these are done:

- Add CI build.
- Keep release signing credentials outside git and maintain a secure keystore backup.
- Add privacy policy review.
- Add in-app permission disclosures.
- Add safety exclusions for critical apps.
- Add device test notes for at least Pixel and one Samsung device.
- Decide Play Store vs F-Droid/sideload distribution.
- Review foreground service policy and declaration text.
- Replace SMS-compose code if strong accountability is required.
