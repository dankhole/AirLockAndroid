# AirLock Android

AirLock Android is a goose-themed native Android MVP for adding AirLock-style friction to distracting phone apps. Users choose apps, set a daily usage budget, and get a blocking wall after the limit is reached. Extra time requires an approval code requested from a trusted Keyholder.

This repo builds locally with the Gradle wrapper after Android SDK setup.

## Current MVP

- Native Android Java, no AndroidX, no Compose, no third-party dependencies.
- App picker for launchable installed apps with per-app daily limits.
- Preset Keyholder phone number.
- Local master override PIN for changing monitoring state and active app limits.
- Foreground monitoring service.
- Usage Access based foreground-app detection.
- Goose-themed overlay blocking screen after the selected app exceeds its limit.
- SMS compose intent for sending a numeric request code tied to requested extra minutes.
- Approval code entry grants the minutes approved when the request code was generated and plays a goose animation.
- Master-PIN-protected generation of five hashed, one-time emergency codes; each pauses all blocking for 24 hours.
- Boot receiver restarts monitoring if monitoring was enabled.

## Why This Shape

The easiest responsible starting point is not a fork of a large blocker app. This scaffold keeps the first version small and focused while borrowing architectural lessons from TapBlok, Curbox, Open TimeLimit, and Mindful.

The MVP avoids direct `SEND_SMS`, `READ_SMS`, broad `QUERY_ALL_PACKAGES`, and Accessibility Service. Those are useful later only if the product requirements justify the policy and trust cost.

## Requirements

- Android Studio, or Android SDK plus Gradle.
- JDK 17.
- Android SDK platform 36.
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

1. Install and open AirLock Goose.
2. Tap `Grant Usage Access` and enable AirLock Goose.
3. Tap `Grant Display Over Other Apps` and allow overlays.
4. Enter:
   - Keyholder phone number: a test number you control
   - Master override PIN: at least four digits
5. Tap `Set Goose Limits!`, choose one non-critical app, continue, and set daily limit to `1`.
6. Tap `Start Goose Duty!` and enter the master PIN.
7. Open the selected app and keep it foregrounded for over one minute.
8. Confirm the goose overlay appears.
9. Enter requested extra minutes and tap `Text the Keyholder!`.
10. Return to the blocked app and enter the approval code for that request.
11. Confirm the goose animation plays and the overlay disappears for the requested duration.

For emergency-code testing, use the master PIN to generate a replacement set, save or share the five plaintext codes, then hide them. Each 8-digit code can be used once to pause all goose blocking for 24 hours; only salted hashes remain on the device.

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

docs/PRODUCT_LANGUAGE.md
  Product roles, terminology, voice, and approval-flow copy rules

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

- The SMS step opens the user's SMS app with the request code and requested minutes for the Keyholder. The local approval code is derived from the request code, and the requested minutes are stored against that pending approval code. This still is not production-grade accountability without a backend or companion app.
- Overlay blocking is friction, not hard security. A determined user can revoke special permissions, force-stop, or uninstall the app.
- The foreground app detector polls recent UsageEvents on a background thread. Full usage reconciliation runs separately once per minute, and OEM battery management can still affect reliability.
- No settings-change delay, unlock caps, audit trail, or backend SMS provider yet.
- Emergency day passes use the device clock and are intentional recovery access, not tamper-proof enforcement.
- Release signing is configured locally, but there is no CI, store listing, or Play policy submission package yet.

## Documentation

- [App Plan](docs/APP_PLAN.md)
- [Architecture](docs/ARCHITECTURE.md)
- [Development Guide](docs/DEVELOPMENT.md)
- [Test Plan](docs/TEST_PLAN.md)
- [Privacy Notes](PRIVACY.md)

## License

GPL-3.0-only. See [LICENSE](LICENSE).
