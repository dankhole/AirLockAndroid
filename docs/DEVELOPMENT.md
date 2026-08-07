# Development Guide

Last updated: August 7, 2026

## Status

This is a ready-to-import Android scaffold, not a verified release. The code is intentionally minimal and should be compiled and device-tested before any product claims are made.

Known local environment gap: this machine did not have `gradle`, Android Studio, or an Android SDK in the usual locations, so `assembleDebug` was not run.

## Local Setup

1. Install Android Studio.
2. Install Android SDK Platform 35.
3. Use JDK 17.
4. Open `/Users/d.cole/Desktop/Projects/AirLockAndroid`.
5. Let Gradle sync.
6. Run `app` on a physical Android device.

After Gradle is available, generate a wrapper:

```sh
gradle wrapper --gradle-version 8.10.2
```

Then prefer:

```sh
./gradlew :app:assembleDebug
```

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
AirLock limits. Usage is stored locally in `Preferences` and reconciled from
Android UsageStats when Usage Access is available.

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

- Add Gradle wrapper.
- Add CI build.
- Add release signing notes outside git.
- Add privacy policy review.
- Add in-app permission disclosures.
- Add safety exclusions for critical apps.
- Add device test notes for at least Pixel and one Samsung device.
- Decide Play Store vs F-Droid/sideload distribution.
- Review foreground service policy and declaration text.
- Replace SMS-compose code if strong accountability is required.
