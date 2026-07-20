# Development Guide

Last updated: July 20, 2026

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
