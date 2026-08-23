# Project Status

Last updated: August 23, 2026

## Current Stage

Airlock is active on the Google Play Internal testing track, not a
release-verified product.
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
| Version | Candidate `versionCode 2`, `versionName 0.1.1`; version 1 is on Internal testing |
| SDK | min 26, compile/target 36 |
| Runtime stack | Platform Java views; no AndroidX, Compose, Kotlin, or third-party runtime dependency |
| Release certificate SHA-256 | `0A:AB:51:C0:4B:D6:A5:13:EC:67:52:59:15:B7:8A:30:AA:78:E6:E9:55:E3:C5:B3:A8:58:FB:99:80:33:9E:7B` |
| Developer verification | New Play package accepted with the existing release certificate; old sideload package remains a separate registration |
| Play App Signing | Enabled; Google Play signs delivered releases |

The uploaded artifact is `releases/Airlock-0.1.0-internal-1.aab`; its checksum
is recorded in [`PLAY_CONSOLE_SUBMISSION.md`](PLAY_CONSOLE_SUBMISSION.md). Play
reports release `1 (0.1.0)` as available to internal testers. A tester has seen
a generic Play Store installation error, so delivery on a physical tester
device is not yet confirmed.

The current replacement candidates are
`releases/Airlock-0.1.1-internal-2.aab` (SHA-256
`5b69eee3d289866a1dddc5582e8c22e0f3a4588488297e61b8ad800c789b7ac5`)
and `releases/Airlock-0.1.1-internal-2.apk` (SHA-256
`4fea3ed5dc49608caf910dee6aa77df2a94ef03d3cbea06fa171da89933eb31e`).
They include the four-digit approval flow and decision-first blocker UI, match
their signed Gradle outputs, and remain local pending upload or distribution.

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

Later on August 18, the `0.1.1` overlay candidate passed 40 unit tests, debug
lint with zero code findings, debug/release assembly, release bundle generation,
and one consolidated Android 17 emulator run of both the broad flow and focused
navigation matrix. Gesture and three-button navigation both removed the blocker
through the normal foreground-event path in Recents, Home, and a directly
opened unguarded app, then restored it only after the guarded app actually
resumed. The broad rotation step also passed in this run. APK inspection and
APK/AAB signature verification matched version code 2 and the registered
certificate. This is emulator evidence only; the physical-device matrix is
still required.

On August 23, the four-digit platform-agnostic approval change passed all 57
JVM tests, debug lint, debug and release assembly, and release bundle
generation. Inspection of generated build inputs confirmed both variants set
`PLUS_FIVE_APPROVAL_OVERRIDE=true` and package `INTERNAL TEST OVERRIDE` SMS
copy while Airlock remains on Internal testing; the PIN-calculated path is
covered directly by unit tests. The complete broad
Android 17 Pixel 8 emulator smoke suite then
passed, including setup, blocker approval, retained state, rotation, and both
navigation modes. The report is under
`app/build/reports/android-smoke/20260823-120055`. This remains emulator
evidence; physical-device qualification is still required.

Later on August 23, the blocker decision-flow overhaul passed 60 JVM tests,
debug assembly, and debug lint. The complete Android 17 Pixel 8 emulator smoke
suite passed setup, real-service blocking, retained request/approval state,
active-request messaging, exact-duration redemption and celebration, and both
navigation modes. Its report is under
`app/build/reports/android-smoke/20260823-150158`. A final typography-only
refinement then passed the same Gradle batch and was reviewed through fresh
captures of the four blocker states under
`app/build/reports/blocker-ui-review`. This remains emulator evidence.

The final centered action-card refinement then passed 60 JVM tests, debug lint,
debug and release assembly, and release bundle generation. Artifact inspection
confirmed package `com.dankhole.airlock`, version code 2 (`0.1.1`), min SDK 26,
target SDK 36, and the registered release certificate. The signed APK and AAB
were copied to `releases/` with the checksums recorded above; physical-device
qualification is still required before treating them as release-verified.

## Implemented Product Contract

- Dedicated three-step access gate for Usage Access, overlay access, and visible
  silent notifications; revoked access returns to the gate.
- Dashboard begins with Duty state and tracked-app usage, clearly distinguishes
  off, paused, recovering, and active enforcement, and exposes settings below.
- Two-step app picker and per-app limits with critical-app exclusions and
  master-PIN authorization while Duty is active.
- Foreground-service monitoring with overlapping UsageEvents, explicit
  foreground/background transition state, gesture recovery, startup-only
  UsageStats seeding, bounded workers, batched persistence, health diagnostics,
  boot/update restart, and screen-off suspension.
- Full-screen dark blocker with a decision-first home and separate request,
  approval, and emergency forms. It makes active requests and additive new
  requests explicit, does not auto-open the keyboard, retains flow/input state
  across temporary hide/reopen, reports exact granted minutes, plays a Goose
  celebration, leaves safely on Back, and cannot be reattached over known
  Home/Recents state by the aggregate foreground sanity check.
- Four-digit requests and replies with a shared four-digit Master/Keyholder PIN
  other than `0000`.
  The platform-agnostic multiplication rule is implemented, but current debug
  and signed Internal-testing releases intentionally accept the labeled `+5`
  test override. Pending requests keep their original minutes for 10 minutes,
  and changing the PIN revokes them.
- Three hashed one-time emergency codes per replacement batch; one code pauses
  blocking for 24 hours while keeping Duty requested.
- Guarded-app-only usage totals, local-only storage, disabled backup/transfer,
  no analytics/ads/backend, and user-initiated SMS compose with no SMS permission.

## Local Approval Model And Test Override

The implemented offline calculation requires no Keyholder app, browser,
account, or backend: multiply the four-digit request by the shared four-digit
Master PIN, discard the product's final two digits, and return the last four
digits left, including leading zeroes. The Master PIN may not be `0000` because
that would map every request to the same reply. Airlock stores a locally derived
lookup rather than the plaintext PIN and keeps each pending result with its
original minutes and a 10-minute expiry. Request values may recur after expiry
or redemption; generation skips reply values that would collide with another
active request for the same app.

This is a deterrent, not strong authentication. A person who controls the
device, knows the PIN, or collects enough examples can bypass it. During
Internal testing, both debug and release builds intentionally replace the PIN
calculation with the per-digit `+5` rule. Their SMS copy labels the override;
switching the signed release to the PIN calculation remains an explicit later
decision.

## Next Sequence

1. Complete the physical-device matrix in `TEST_PLAN.md`, including the
   Recents/app-switch timing checks and 48-72 hour
   reliability and battery checks on a Pixel and a current Samsung.
2. Diagnose the current tester installation error and confirm a clean install
   from the Internal testing opt-in link on a physical device.
3. Confirm remaining tester accounts, support email,
   and complete the `specialUse` foreground-service declaration/video before
   the first Play rollout.
4. Upload the verified version-2 bundle through the Internal testing track and
   confirm Play-delivered installation.
5. Collect reliability, approval-flow, and deterrence feedback, then explicitly
   decide when to retire the signed-release `+5` override and qualify the
   shared-PIN calculation.

## Known Release Gaps

- No CI; local JVM/build/lint and the batched emulator smoke script are the
  automated gates.
- The broad emulator smoke runner has intermittently shown an Android 17
  rotation/UI-dump race, although the consolidated candidate run passed it.
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
  app remains intentional friction; add throttling only if testing justifies
  the usability cost, without implying brute-force resistance.
- Play-ready graphics and listing copy are under `play-store/`. The public
  support email and foreground-service demonstration video remain owner tasks.
- Sideloaded APKs may trigger an unknown-source/Play Protect warning even when
  correctly signed and developer-verified; that warning is not proof that the
  APK is unsigned.
