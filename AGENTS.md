# AirLock Android Agent Notes

## Project Purpose

AirLock Android is a native Android MVP for blocking selected apps after a daily time limit and granting extra time through an accountability access code.

## Current Scaffold

- Plain Java Android app.
- No AndroidX, Compose, Kotlin, or third-party runtime dependencies. JUnit 4 is
  used only by local unit tests and is not packaged in the app.
- Build files use Android Gradle Plugin 8.10.1, Gradle 8.11.1, compile/target SDK 36, min SDK 26, and Java 17 bytecode.
- The Gradle wrapper is checked in and is the required command-line build path.

## Important Constraints

- Do not add direct `SEND_SMS`, `READ_SMS`, or `RECEIVE_SMS` for the default MVP path.
- Do not add broad `QUERY_ALL_PACKAGES`; query launchable apps through intent visibility.
- Do not add Accessibility Service unless the feature cannot be done through UsageStats/overlay and the disclosure work is also added.
- Treat overlay blocking as intentional friction, not hard security.
- Keep all usage data local unless a backend feature is explicitly designed.
- Keep critical app exclusions in mind before adding stricter enforcement.

## Product Engineering Priorities

Use this order when requirements or implementation tradeoffs conflict:

1. **Long-running reliability:** Monitoring must continue across process death,
   service recreation, device reboot, app updates, and ordinary multi-day use.
   Prefer self-correcting state and explicit health checks over assumptions that
   an Android callback or worker will run forever.
2. **Fail-visible behavior:** Never present monitoring as healthy when required
   access is missing or the service is not actually running. Preserve user data,
   explain the exact recovery action, and recover automatically when Android
   permits it.
3. **Fast enforcement:** Once a tracked app is over its limit, show the blocker
   promptly, including during gesture navigation and rapid app switching.
4. **Battery and performance efficiency:** Keep normal polling and disk writes
   conservative. Any faster polling or wider event scan must be adaptive,
   bounded, measurable, and stopped as soon as foreground state settles.
5. **Behavioral and data integrity:** Preserve per-app limits, usage totals,
   override-code semantics, permission gates, and critical-app exclusions. Do
   not trade correctness for a visually successful flow.
6. **Maintainability and presentation:** Keep platform-native Java code,
   centralized styling, clear ownership boundaries, and accessible UI. Visual
   polish and goose theming must not obscure status, requirements, or errors.

For monitoring changes, test both immediate transitions and multi-day lifecycle
conditions. Document remaining Android/OEM limitations instead of implying
that overlay-based blocking is guaranteed hard security.

## Validation Cadence

Match validation cost to the risk and scope of the change:

- Use targeted compilation or static checks during small implementation steps.
- Do not start an emulator for every minor code, copy, documentation, or style
  adjustment.
- Batch related UI and Android behavior checks into one emulator session at a
  meaningful implementation checkpoint.
- Prioritize emulator or physical-device testing when changes affect overlays,
  permissions, foreground-service lifecycle, UsageStats detection, navigation,
  system insets, or other behavior that JVM/static checks cannot validate.
- Run the required Gradle build and lint checks after the implementation batch,
  then run the broader manual test plan before a release candidate.

## Validation

After Android tooling exists, run:

```sh
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
./gradlew :app:lintDebug
```

Before claiming release readiness, run the manual scenarios in `docs/TEST_PLAN.md` on a physical Android device.
