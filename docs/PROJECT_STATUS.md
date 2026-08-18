# Project Status

Last updated: August 18, 2026

## Current Stage

Airlock is an internal-testing candidate, not a release-verified product.
The native Java MVP, reliability hardening, dark Goose UI, debug smoke harness,
release signing, privacy disclosure, and Play submission draft are implemented.
Physical-device release qualification and Play Console owner actions remain.

Always inspect `git status` and preserve existing changes. This file records the
last reviewed repository state; it is not permission to discard newer work.

## Release Identity

| Item | Current value |
| --- | --- |
| Play application ID | `com.dankhole.airlock` |
| Java namespace | `com.dankhole.airlockandroid` |
| Version | `versionCode 1`, `versionName 0.1.0` |
| SDK | min 26, compile/target 36 |
| Runtime stack | Platform Java views; no AndroidX, Compose, Kotlin, or third-party runtime dependency |
| Release certificate SHA-256 | `0A:AB:51:C0:4B:D6:A5:13:EC:67:52:59:15:B7:8A:30:AA:78:E6:E9:55:E3:C5:B3:A8:58:FB:99:80:33:9E:7B` |
| Developer verification | New Play package accepted with the existing release certificate; old sideload package remains a separate registration |
| Play App Signing | Enabled; Google Play signs delivered releases |

The current upload artifact is
`releases/Airlock-0.1.0-internal-1.aab`; its checksum is recorded in
[`PLAY_CONSOLE_SUBMISSION.md`](PLAY_CONSOLE_SUBMISSION.md). It was copied from
the verified signed output under `app/build/outputs`, was built from an
uncommitted release-preparation worktree, and has not been uploaded.

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
fixed. The signed artifact from that period is obsolete. No complete
physical-device release record exists yet.

On August 18, 2026, after migrating the Play application ID to
`com.dankhole.airlock` and the visible title to `Airlock`, the batched Gradle
validation passed 35 unit tests, debug lint with zero findings, debug/release
assembly, and release bundle generation. Artifact inspection confirmed version
`0.1.0` (`versionCode 1`), target SDK 36, title `Airlock`, the expected Java
component names, and local certificate SHA-256 ending `80:33:9E:7B`. The AAB
SHA-256 is `9fbd3e66295acb6f716d5b9b74903e72cf7dd32ef2b243c7706c1dbce38421d4`.
The package-migration emulator smoke run was stopped before completion at the
owner's direction and is not release evidence.

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
3. Upload the newly built `com.dankhole.airlock` AAB to Internal testing and
   confirm the app-signing and upload certificate roles under App integrity.
4. Confirm tester accounts, support email,
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
- Store graphics, public support email, and the
  foreground-service demonstration video are owner/release tasks.
- Sideloaded APKs may trigger an unknown-source/Play Protect warning even when
  correctly signed and developer-verified; that warning is not proof that the
  APK is unsigned.
