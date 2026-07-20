# AirLock Android

AirLock Android is a native Android MVP for adding AirLock-style friction to distracting phone apps. Users choose apps, set a daily usage budget, and get a blocking wall after the limit is reached. Extra time requires a one-time access code that can be sent to a preset accountability phone number.

This repo is scaffolded for development, but the APK has not been compiled in this environment because Android SDK and Gradle are not installed locally.

## Current MVP

- Native Android Java, no AndroidX, no Compose, no third-party dependencies.
- App picker for launchable installed apps.
- Daily limit and extra-time settings.
- Preset accountability phone number.
- Foreground monitoring service.
- Usage Access based foreground-app detection.
- Overlay blocking screen after the selected app exceeds its limit.
- SMS compose intent for requesting an access code.
- Code entry grants the configured extra minutes.
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
gradle :app:assembleDebug
```

This repo does not include a Gradle wrapper yet because no local Gradle installation was available to generate one.

## First Device Test

1. Install and open AirLock Android.
2. Tap `Grant Usage Access` and enable AirLock Android.
3. Tap `Grant Display Over Other Apps` and allow overlays.
4. Enter:
   - Daily limit: `1`
   - Extra time: `1`
   - Accountability phone number: a test number you control
5. Tap `Select Apps` and choose one non-critical app.
6. Tap `Start Monitoring`.
7. Open the selected app and keep it foregrounded for over one minute.
8. Confirm the AirLock overlay appears.
9. Tap `Text access code`.
10. Return to the blocked app and enter the code from the composed SMS.
11. Confirm the overlay disappears for roughly one extra minute.

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

- The SMS step opens the user's SMS app with the code in the message body. That avoids restricted SMS permissions, but it is weak accountability because the user can see the code.
- Overlay blocking is friction, not hard security. A determined user can revoke special permissions, force-stop, or uninstall the app.
- The foreground app detector uses polling and UsageStats; OEM battery management can affect reliability.
- No settings-change delay, unlock caps, audit trail, or backend SMS provider yet.
- No release signing, Gradle wrapper, CI, store listing, or Play policy submission package yet.

## Documentation

- [App Plan](docs/APP_PLAN.md)
- [Architecture](docs/ARCHITECTURE.md)
- [Development Guide](docs/DEVELOPMENT.md)
- [Test Plan](docs/TEST_PLAN.md)
- [Privacy Notes](PRIVACY.md)

## License

GPL-3.0-only. See [LICENSE](LICENSE).
