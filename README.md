# AirLock Android

AirLock Android is a native Android MVP for adding AirLock-style friction to distracting phone apps. Users choose apps, set a daily usage budget, and get a blocking wall after the limit is reached. Extra time requires an approval code requested through a preset accountability phone number.

This repo builds locally with the Gradle wrapper after Android SDK setup.

## Current MVP

- Native Android Java, no AndroidX, no Compose, no third-party dependencies.
- App picker for launchable installed apps with per-app daily limits.
- Preset accountability phone number.
- Local master override PIN for changing monitoring state and active app limits.
- Foreground monitoring service.
- Usage Access based foreground-app detection.
- Overlay blocking screen after the selected app exceeds its limit.
- SMS compose intent for sending a numeric request code tied to requested extra minutes.
- Approval code entry grants the minutes approved when the request code was generated.
- Boot receiver restarts monitoring if monitoring was enabled.

## Why This Shape

The easiest responsible starting point is not a fork of a large blocker app. This scaffold keeps the first version small and focused while borrowing architectural lessons from TapBlok, Curbox, Open TimeLimit, and Mindful.

The MVP avoids direct `SEND_SMS`, `READ_SMS`, broad `QUERY_ALL_PACKAGES`, and Accessibility Service. Those are useful later only if the product requirements justify the policy and trust cost.

## Requirements

- Android Studio, or Android SDK plus Gradle.
- JDK 17.
- Android SDK platform 35.
- A physical Android device is strongly preferred because Usage Access, overlays, and foreground services are hard to validate accurately on emulators.

## Build

With Android Studio:

1. Open this folder: `/Users/d.cole/Desktop/Projects/AirLockAndroid`.
2. Let Android Studio sync Gradle.
3. Select the `app` run configuration.
4. Run on a physical Android device.

With command-line tooling:

```sh
./gradlew :app:assembleDebug
```

## First Device Test

1. Install and open AirLock Android.
2. Tap `Grant Usage Access` and enable AirLock Android.
3. Tap `Grant Display Over Other Apps` and allow overlays.
4. Enter:
   - Accountability phone number: a test number you control
   - Master override PIN: at least four digits
5. Tap `Set App Limits`, choose one non-critical app, continue, and set daily limit to `1`.
6. Tap `Start Monitoring` and enter the master PIN.
7. Open the selected app and keep it foregrounded for over one minute.
8. Confirm the AirLock overlay appears.
9. Enter requested extra minutes and tap `Text Request Code`.
10. Convert the request code with the temporary test rule, return to the blocked app, and enter the approval code.
11. Confirm the overlay disappears for the requested duration.

## Project Map

```text
app/src/main/AndroidManifest.xml
  Permissions, activities, foreground service, boot receiver

app/src/main/java/com/dankhole/airlockandroid/MainActivity.java
  Settings, permission shortcuts, monitoring controls

app/src/main/java/com/dankhole/airlockandroid/AppSelectionActivity.java
  Launchable app list and selected package persistence

app/src/main/java/com/dankhole/airlockandroid/MonitoringService.java
  Foreground app polling, usage accrual, blocking overlay, code unlock

app/src/main/java/com/dankhole/airlockandroid/Preferences.java
  SharedPreferences keys, daily counters, unlocks, one-time codes

app/src/main/java/com/dankhole/airlockandroid/BootReceiver.java
  Restarts monitoring after reboot when enabled

docs/APP_PLAN.md
  Product purpose, scope, references, milestones

docs/ARCHITECTURE.md
  Runtime flow, data model, Android API details

docs/DEVELOPMENT.md
  Local setup, coding rules, debugging notes

docs/TEST_PLAN.md
  Build checks and manual validation matrix
```

## Current Limitations

- The SMS step opens the user's SMS app with the request code and requested minutes. That avoids restricted SMS permissions, but the current deterministic test conversion is not production-grade accountability.
- Overlay blocking is friction, not hard security. A determined user can revoke special permissions, force-stop, or uninstall the app.
- The foreground app detector uses polling and UsageStats; OEM battery management can affect reliability.
- No settings-change delay, unlock caps, audit trail, or backend SMS provider yet.
- No release signing, CI, store listing, or Play policy submission package yet.

## Documentation

- [App Plan](docs/APP_PLAN.md)
- [Architecture](docs/ARCHITECTURE.md)
- [Development Guide](docs/DEVELOPMENT.md)
- [Test Plan](docs/TEST_PLAN.md)
- [Privacy Notes](PRIVACY.md)

## License

GPL-3.0-only. See [LICENSE](LICENSE).
