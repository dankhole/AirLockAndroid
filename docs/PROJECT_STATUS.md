# Project Status

Last updated: August 11, 2026

## Current Stage

AirLock Goose is an internal-testing candidate, not a release-verified product.
The native Java MVP, reliability hardening, dark Goose UI, debug smoke harness,
release signing, privacy disclosure, and Play submission draft are implemented.
Physical-device release qualification and Play Console owner actions remain.

Always inspect `git status` and preserve existing changes. This file records the
last reviewed repository state; it is not permission to discard newer work.

## Release Identity

| Item | Current value |
| --- | --- |
| Package | `com.dankhole.airlockandroid` |
| Version | `versionCode 1`, `versionName 0.1.0` |
| SDK | min 26, compile/target 36 |
| Runtime stack | Platform Java views; no AndroidX, Compose, Kotlin, or third-party runtime dependency |
| Release certificate SHA-256 | `0A:AB:51:C0:4B:D6:A5:13:EC:67:52:59:15:B7:8A:30:AA:78:E6:E9:55:E3:C5:B3:A8:58:FB:99:80:33:9E:7B` |
| Developer verification | Package/certificate registered and verified in Android Developer Console |
| Play Console verification | External owner state; confirm in Console before upload |

Local release APK/AAB copies under `releases/` are Git-ignored. The existing
copies predate the navigation-lockout fix and are stale. Rebuild after all
current source changes, then update the checksum in
[`PLAY_CONSOLE_SUBMISSION.md`](PLAY_CONSOLE_SUBMISSION.md) before uploading.

## Last Verified Evidence

On August 11, 2026, source through commit `16bd88c` passed:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
NAVIGATION_ONLY=true scripts/android-smoke.sh --skip-build
```

The Gradle batch completed with one non-blocking wrapper/tool-version warning.
The focused Android 17 emulator regression passed under gesture and
three-button navigation in about 30 seconds, including Recents/Home escape,
navigation-inset clearance, the real foreground-sanity path, blocker relaunch,
and Android Back escape.

The complete broad smoke runner was also attempted but did not finish: its
app-limit-wizard rotation step intermittently fails to restore a dumpable
portrait hierarchy on the Android 17 emulator. No product crash was observed,
but the broad runner is not a green release signal until that harness race is
fixed. The prior signed release assembly and AAB certificate verification were
performed before `16bd88c`; current release artifacts must be rebuilt and
reverified. No complete physical-device release record exists yet.

## Implemented Product Contract

- Dedicated three-step access gate for Usage Access, overlay access, and visible
  silent notifications; revoked access returns to the gate.
- Dashboard begins with Duty state and tracked-app usage, clearly distinguishes
  off, paused, recovering, and active enforcement, and exposes settings below.
- Two-step app picker and per-app limits with critical-app exclusions and
  master-PIN authorization while Duty is active.
- Foreground-service monitoring with overlapping UsageEvents, gesture recovery,
  periodic UsageStats reconciliation, bounded workers, batched persistence,
  health diagnostics, boot/update restart, and screen-off suspension.
- Full-screen dark blocker that does not auto-open the keyboard, retains both
  numeric fields across temporary hide/reopen, supports multiple pending
  requests, reports exact granted minutes, plays a Goose celebration, leaves
  safely on Back, and cannot be reattached over known Home/Recents state by the
  aggregate foreground sanity check.
- Three hashed one-time emergency codes per replacement batch; one code pauses
  blocking for 24 hours while keeping Duty requested.
- Guarded-app-only usage totals, local-only storage, disabled backup/transfer,
  no analytics/ads/backend, and user-initiated SMS compose with no SMS permission.

## Deliberate Test-Release Compromise

The first friend/internal test uses a transparent deterministic approval rule:
add 5 to every request-code digit modulo 10. A generated approval code is saved
locally with its requested minutes and a 10-minute expiry, so multiple requests
can remain in flight and changing the form cannot change an existing grant.

This is test plumbing, not strong accountability or authentication. Do not add
complex hardening around it. After the testing release proves the core Android
flow, design the real Keyholder authorization path, likely a backend or
companion app with server-generated one-time approvals, rate limits, expiry,
and a migration away from the deterministic rule.

## Next Sequence

1. Fix or replace the Android 17 rotation step in the broad smoke runner, then
   run both the broad and focused emulator paths from the release candidate.
2. Complete the physical-device matrix in `TEST_PLAN.md`, including 48-72 hour
   reliability and battery checks on a Pixel and a current Samsung.
3. Resolve the Play App Signing choice before the first upload: enroll the
   existing key for direct/Play update compatibility, or accept that
   Google-generated Play signing requires uninstalling when switching channels.
4. Confirm Play Console developer verification, tester accounts, support email,
   and complete the `specialUse` foreground-service declaration/video before
   the first Play rollout.
5. Increment `versionCode` if a different bundle has already been uploaded,
   rebuild from the exact release commit, verify signing and checksum, and roll
   out to the Internal testing track. Do not upload the current stale local AAB.
6. Collect reliability and UX feedback before implementing real approval auth.

## Known Release Gaps

- No CI; local JVM/build/lint and the batched emulator smoke script are the
  automated gates.
- The broad emulator smoke runner currently has an Android 17 rotation/UI-dump
  race. The focused blocker-navigation matrix passes independently.
- The complete physical-device release matrix has not been recorded as passed.
- Large-font, landscape, tablet, TalkBack heading/navigation, and physical
  cutout behavior are not yet recorded as passed across the core flows.
- OEM force-stop, Active apps Stop, Restricted battery mode, and uninstall
  cannot be self-corrected without user action; this is an Android/platform
  limit, not a promised security boundary.
- Multi-window, picture-in-picture, midnight/timezone changes, and aggressive
  OEM background controls still require broader device evidence.
- Keyholder validation currently accepts exactly 10 digits, so international
  phone-number support is not implemented.
- Master-PIN, approval, and emergency-code entry are not attempt-throttled. The
  app remains intentional friction; address rate limits with the real auth and
  wider-release hardening work instead of implying brute-force resistance.
- Store graphics, public support email, Play App Signing enrollment, and the
  foreground-service demonstration video are owner/release tasks.
- Sideloaded APKs may trigger an unknown-source/Play Protect warning even when
  correctly signed and developer-verified; that warning is not proof that the
  APK is unsigned.
