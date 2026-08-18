# Airlock Android

Airlock Android is a goose-themed native Android MVP for adding Airlock-style friction to distracting phone apps. Users choose apps, set a daily usage budget, and get a blocking wall after the limit is reached. Extra time requires an approval code requested from a trusted Keyholder.

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
- Master-PIN-protected generation of three hashed, one-time emergency codes; each pauses all blocking for 24 hours.
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
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

## First Device Test

1. Install and open Airlock.
2. Complete the dedicated setup screen by granting Usage Access, Display Over
   Other Apps, and goose notifications. The dashboard opens after all three are
   ready.
3. Enter:
   - Keyholder phone number: a test number you control
   - Master override PIN: at least four digits
4. Tap `Set Goose Limits!`, choose one non-critical app, continue, and set daily limit to `1`.
5. Turn on Goose Duty and enter the master PIN.
6. Open the selected app and keep it foregrounded for over one minute.
7. Confirm the goose overlay appears.
8. Enter requested extra minutes and tap `Text the Keyholder!`.
9. For this internal-test build, derive the reply by adding 5 to each request
   digit modulo 10, then return and enter that approval code.
10. Confirm the goose animation plays and the overlay disappears for the requested duration.

For emergency-code testing, use the master PIN to generate a replacement set, save or share the three plaintext codes, then hide them. Each 8-digit code can be used once to pause all goose blocking for 24 hours; only salted hashes remain on the device.

## Project Map

Start with [the documentation map](docs/README.md) and
[current project status](docs/PROJECT_STATUS.md). The core runtime owners are:

- `MainActivity`: access gate, dashboard settings, health, usage, and Duty controls.
- `AppSelectionActivity`: two-step guarded-app and limit editor.
- `MonitoringService`: foreground detection, usage accrual, recovery, and overlay lifecycle.
- `BlockerOverlayController`: blocker form state, validation, and celebrations.
- `Preferences`: local state, per-app limits, approval records, and emergency codes.
- `UiStyle`: reusable dark visual system, controls, status styles, and safe areas.

## Current Limitations

- The internal-test approval code is a transparent per-digit `+5` transform of
  the request code. Requested minutes are stored against that pending code for
  10 minutes. This proves the Android flow but is not production-grade
  accountability; a backend or Keyholder companion app is the next auth phase.
- Overlay blocking is friction, not hard security. A determined user can revoke special permissions, force-stop, or uninstall the app.
- The foreground app detector polls recent UsageEvents on a background thread. Full usage reconciliation runs separately once per minute, and OEM battery management can still affect reliability.
- No settings-change delay, unlock caps, audit trail, or backend SMS provider yet.
- Emergency day passes use the device clock and are intentional recovery access, not tamper-proof enforcement.
- Release signing and a Play submission draft exist, but there is no CI and the
  complete physical-device release matrix has not passed yet. The focused
  blocker-navigation emulator path passes; the broad Android 17 smoke path has
  a known rotation/UI-dump harness race.

## Documentation

- [Documentation Map](docs/README.md)
- [Current Project Status](docs/PROJECT_STATUS.md)
- [App Plan](docs/APP_PLAN.md)
- [Architecture](docs/ARCHITECTURE.md)
- [UI And Accessibility Design](docs/DESIGN.md)
- [Monitoring Reliability](docs/RELIABILITY.md)
- [Development Guide](docs/DEVELOPMENT.md)
- [Product Language](docs/PRODUCT_LANGUAGE.md)
- [Test Plan](docs/TEST_PLAN.md)
- [Release Guide](docs/RELEASE.md)
- [Play Console Submission Draft](docs/PLAY_CONSOLE_SUBMISSION.md)
- [Privacy Policy](PRIVACY.md)

## License

GPL-3.0-only. See [LICENSE](LICENSE).
