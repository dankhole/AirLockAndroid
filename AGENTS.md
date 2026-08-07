# AirLock Android Agent Notes

## Project Purpose

AirLock Android is a native Android MVP for blocking selected apps after a daily time limit and granting extra time through an accountability access code.

## Current Scaffold

- Plain Java Android app.
- No AndroidX, Compose, Kotlin, or third-party dependencies yet.
- Build files use Android Gradle Plugin 8.10.1, Gradle 8.11.1, compile/target SDK 36, min SDK 26, and Java 17 bytecode.
- The Gradle wrapper is checked in and is the required command-line build path.

## Important Constraints

- Do not add direct `SEND_SMS`, `READ_SMS`, or `RECEIVE_SMS` for the default MVP path.
- Do not add broad `QUERY_ALL_PACKAGES`; query launchable apps through intent visibility.
- Do not add Accessibility Service unless the feature cannot be done through UsageStats/overlay and the disclosure work is also added.
- Treat overlay blocking as intentional friction, not hard security.
- Keep all usage data local unless a backend feature is explicitly designed.
- Keep critical app exclusions in mind before adding stricter enforcement.

## Validation

After Android tooling exists, run:

```sh
./gradlew :app:assembleDebug
./gradlew :app:lintDebug
```

Before claiming release readiness, run the manual scenarios in `docs/TEST_PLAN.md` on a physical Android device.
