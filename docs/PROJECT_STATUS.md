# Project Status

Last updated: September 14, 2026

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
| Version | Internal testing: `versionCode 10006`, `versionName 0.1.7`, published by CI on September 14 |
| SDK | min 26, compile/target 36 |
| Runtime stack | Platform Java views; no AndroidX, Compose, Kotlin, or third-party runtime dependency |
| Release certificate SHA-256 | `0A:AB:51:C0:4B:D6:A5:13:EC:67:52:59:15:B7:8A:30:AA:78:E6:E9:55:E3:C5:B3:A8:58:FB:99:80:33:9E:7B` |
| Developer verification | New Play package accepted with the existing release certificate; old sideload package remains a separate registration |
| Play App Signing | Enabled; Google Play signs delivered releases |

The initial uploaded artifact was `releases/Airlock-0.1.0-internal-1.aab`; its checksum
is recorded in [`PLAY_CONSOLE_SUBMISSION.md`](PLAY_CONSOLE_SUBMISSION.md). Play
previously reported release `1 (0.1.0)` as available to internal testers. A tester has seen
a generic Play Store installation error, so delivery on a physical tester
device is not yet confirmed.

The previous local replacement candidate was
`releases/Airlock-0.1.7-internal-8.aab` (SHA-256
`a9e78424b40ea236bdaad71e4dac8b77949d7441cf14160eb9139dfeb21d12a9`).
It includes the overlay lifecycle and navigation hardening, documented PIN
calculation, hidden per-digit override, independent one-hour request expiry,
and decision-first blocker UI. It byte-matches the signed Gradle output and
is superseded by CI release 10006. The version-7 and version-8 artifacts must
not be uploaded as updates.

The September 13 unexpected-blocker fixes are source changes described in
[`BLOCKER_INVESTIGATION.md`](BLOCKER_INVESTIGATION.md). The existing version-8
bundle above predates this investigation and does **not** contain these fixes.
No replacement distributable has been generated or copied for this task; a
newly versioned, validated bundle is needed before shipping the updated source.

## Last Verified Evidence

The September 14 overlay follow-up prepares each blocker before a separate
foreground confirmation, expires attached windows independently of stalled
queries, and tracks activity classes through same-app screen handoffs. The
service now carries one reducer state instead of duplicated foreground fields;
empty or stale state cannot clear recovery health. See `RELIABILITY.md` for
watchdog budgets and `BLOCKER_INVESTIGATION.md` for scope and limitations.

Local validation passed all 122 JVM tests, debug assembly/lint, release-source
Java compilation, and standalone target assembly/lint. Lint has no errors;
existing warnings cover the Gradle wrapper, the Back dispatcher's `TargetApi`,
and the standalone target's missing icon. That initial validation generated
no distributable release. The requested replacement is prepared as `0.1.8`,
with its version code allocated by the Internal-publishing CI workflow.

Release run `34905421762` stopped before build or upload because SDK setup
could not find the legacy `tools` package. Both SDK setup steps now explicitly
request `platform-tools`; a new publishing run will execute all normal checks.

The expanded gesture/three-button navigation matrix passed on the Android 17
Pixel 8 emulator in `app/build/reports/android-smoke/20260914-182241`, including
delayed-result Home navigation, independent removal during an eight-second
query stall, a never-focused window beneath the notification shade, and the
two-activity handoff. After bounding accelerated attachment retries, the final
APK passed the gesture matrix again in `20260914-182814` and the complete
setup/retained-input/approval/celebration flow in `20260914-183324`.

Two harness issues were isolated: the first navigation attempt read a window
inventory without Android 17's focus fields, so the shade check now reads the
full WindowManager dump; the first broad attempt used a two-word ADB search
query, so the successful rerun used CI's `TARGET_QUERY=Smoke`. Physical Pixel
and Samsung qualification, battery impact, and multi-window/PiP remain open.
Task-started emulator, ADB, Gradle, and smoke-runner processes were stopped;
process exit was verified.

GitHub-hosted Android CI passed on September 14 for commit `6911362`:
<https://github.com/dankhole/AirLockAndroid/actions/runs/34871293494>.
All six release-helper tests, 99 JVM tests, visible-text audit, debug lint,
debug/release builds, and standalone target build/lint passed. The complete
API 36 Linux emulator smoke suite passed, including gesture/three-button
navigation, recovery, approval input, and celebration scenarios.

CI fixes selected the available `pixel_2` profile and moved API 33+ blocker
Back handling to the platform dispatcher. The IME consumes the first Back;
closed-keyboard Back exits safely. Local Android 17 navigation/recovery and
approval-form regressions also passed in `20260914-125322` and
`20260914-125715`. Task-started emulator, ADB, and Gradle processes exited.

All five `play-internal` secret names are configured. `PLAY_AUTO_PUBLISH`
remains unset. The first manual publishing test, version code `10006`, passed
all build/smoke checks, upload-certificate verification, signed release build,
and bundle verification. The signed bundle is saved in its Actions artifacts:
<https://github.com/dankhole/AirLockAndroid/actions/runs/34875154023>.
The initial attempt was denied before upload because the service account lacked
Play Console access. After the owner invited
`airlock-ci@airlock-508603.iam.gserviceaccount.com`, rerunning only the failed
publishing job succeeded: one bundle uploaded and the Internal-track edit
committed at 18:01 UTC on September 14. The published source is `6acc0b4`,
release name `Airlock 10006`, status `completed`. The signed bundle remains in
the run's `play-bundle-10006` artifact. Tester Play Store install/update and
multi-day physical-device qualification remain unverified; automatic publishing
on future pushes remains disabled until `PLAY_AUTO_PUBLISH=true` is set.


The September 13 unexpected-blocker investigation passed all 99 JVM tests,
debug assembly, and debug lint (one existing Gradle-wrapper update warning;
no code findings). The expanded gesture/three-button recovery matrix passed at
`app/build/reports/android-smoke/20260913-184526`; the final service code also
passed both navigation modes in `20260913-185013` before that run stopped on
an ADB search-text harness issue. After correcting search input and explicitly
closing the keyboard before navigating the long approval form, the complete
setup/permissions/retained-state/approval/celebration UI flow passed at
`app/build/reports/android-smoke/20260913-185927`.

The subsequent removal of forced keyboard-show flags passed the same Gradle
batch and a focused final-APK keyboard/reboot regression in
`app/build/reports/blocker-reboot-check`. That check verified no automatic
keyboard on initial/reopened blockers, explicit-tap opening, first-Back
keyboard dismissal without leaving the blocker, and no keyboard on Home. A
reboot from the visible blocker restarted monitoring, left Home and Settings
free of attached blocker windows, and blocked only after the guarded target
actually reopened. Emulator usage diagnostics also confirmed a daily bucket
beginning at 10:30 AM rather than local midnight, supporting the conservative
bucket filtering described in `RELIABILITY.md`. The emulator, smoke runners,
Gradle daemon, and task-started ADB server were stopped and process exit was
verified. These are local Android 17 Pixel 8 emulator results; physical-device
and multi-day qualification remain outstanding. No release bundle was built,
copied, uploaded, or installed on a physical phone for this investigation.

On September 13, the GitHub Actions preparation passed all 74 JVM tests, debug
lint, debug/release assembly, release bundle generation, and the separate
debug-only smoke-target build/lint. A fresh source-only copy with no signing
configuration passed the same build checks using CI version code 10001;
inspection confirmed package `com.dankhole.airlock`, min SDK 26, and target SDK
36. The existing locally signed AAB passed signature and upload-certificate
verification. Missing/partial signing configuration, version code 0, and an
unsigned AAB were rejected by the publishing gates as intended. Six Python
release-helper tests, the visible-text audit, and actionlint also passed.
The full release-validation smoke suite passed with the new standalone target
on the local Android 17 Pixel 8 emulator at
`app/build/reports/android-smoke/20260913-103156`, including both navigation
modes. No Play upload or GitHub-hosted run was performed; the workflow's API 36
Linux emulator still needs its first hosted validation. No new distributable
was copied into `releases/`, and the previously recorded candidate remains
unchanged. This remains emulator evidence, not physical-device qualification.

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
were superseded by the explicitly bumped version-3 artifacts; physical-device
qualification is still required before treating the replacement as
release-verified.

The explicit release bump to version code 3 (`0.1.2`) passed the same 60 JVM
tests, debug lint, debug and release assembly, and release bundle generation.
Inspection confirmed package `com.dankhole.airlock`, min SDK 26, target SDK 36,
and the registered release certificate. The copied version-3 APK and AAB
byte-match their signed Gradle outputs and use the checksums recorded above.

Later on August 23, the approval-copy and temporary-calculation update removed
test/override/track labels and all derivation instructions from the Master-PIN
guidance, composed SMS, and Play-facing release notes. The temporary reply now
uses `(request + 5656) mod 10000`; request `4321` produces `9977`, and request
`5000` preserves its leading zero as `0656`. Version code 5 (`0.1.4`) passed all
60 JVM tests, release APK assembly, release bundle generation, and release
lint-vital checks. Inspection confirmed package `com.dankhole.airlock`, min SDK
26, target SDK 36, the registered release certificate, and
`ADD_5656_APPROVAL_OVERRIDE=true`. The copied AAB byte-matches the signed Gradle
output and uses the checksum recorded above. Full lint and device tests were
intentionally not repeated for this focused change.

On August 24, the approval flow was adjusted so the SMS again explains the
Master-PIN multiplication rule while Internal-testing builds accept both that
PIN-derived reply and the hidden `(request + 5656) mod 10000` fallback. Both
replies resolve to one pending record, share its expiry and saved minutes, and
are protected against primary/alias collisions. Version code 6 (`0.1.5`) passed
all 62 JVM tests, release APK assembly, release bundle generation, and release
lint-vital checks. Inspection confirmed package `com.dankhole.airlock`, min SDK
26, target SDK 36, the registered certificate, and
`ACCEPT_ADD_5656_APPROVAL_OVERRIDE=true`. The copied AAB byte-matches the signed
Gradle output and uses the checksum recorded above. Full lint and device tests
were not repeated for this focused approval change.

On August 25, source changed only the hidden override to add 3 independently to
each request-code digit modulo 10; request `4321` now produces `7654`, and
request `7890` produces `0123`. The PIN calculation and user-facing SMS remain
unchanged. All 62 JVM tests, debug assembly, and debug lint passed, followed by
the complete Android 17 Pixel 8 emulator smoke suite, including real blocker
redemption and both navigation modes. Its report is under
`app/build/reports/android-smoke/20260825-095029`. At that checkpoint no release
artifact had been built or copied, so the version-6 bundle remained obsolete.

A follow-up audit removed the override-conditioned Master-PIN helper branches,
so the settings UI and composed SMS now describe only the shared-PIN method.
The override flag is confined to approval persistence/redemption and the
debug-only smoke fixture. All 62 JVM tests, debug assembly, debug lint, release
APK assembly, and release bundle generation passed. Inspection of the packaged
release resources found no override formula, marker, or example, while the SMS
still contains the request-times-PIN, drop-two, take-four instructions. These
Gradle outputs were validation artifacts only and were not copied into
`releases/`; a newly versioned distributable candidate is still required.

Later on August 25, newly generated ordinary approval requests were extended
from a 10-minute lifetime to 1 hour. Each request still keeps its own absolute
expiry and saved minutes, so multiple requests remain independently redeemable;
already-persisted requests retain the expiry saved when they were created. All
62 JVM tests, debug assembly, and debug lint passed. Full device smoke was not
repeated for this focused persistence-policy change.

The version-7 (`0.1.6`) Internal-testing candidate then passed all 62 JVM tests,
debug lint, debug and release assembly, release bundle generation, and the full
release-validation Android 17 Pixel 8 emulator smoke suite. The smoke report is
under `app/build/reports/android-smoke/20260825-104201`. Inspection confirmed
package `com.dankhole.airlock`, min SDK 26, target SDK 36, the registered upload
certificate, the enabled hidden override, and no override markers in packaged
user-visible resources. The copied AAB byte-matches the signed Gradle output;
its checksum is recorded above. Physical-device qualification remains open.

On August 31, the overlay lifecycle audit traced the monitoring history and
every foreground, navigation, celebration, window-attachment, permission, and
service-teardown path. Timestamped lifecycle reduction now accepts genuinely
delayed events without allowing overlap replay to resurrect a stale guarded
app. Explicit Home and messaging exits invalidate in-flight queries, success
celebrations are bounded and foreground-aware, and attached overlay references
are retained until verified removal with bounded retry. All 74 JVM tests,
debug lint, debug/release assembly, and release bundle generation passed. The
expanded Android 17 Pixel 8 emulator suite passed under gesture and three-button
navigation at `app/build/reports/android-smoke/20260831-091920`, followed by
three additional focused navigation matrices at `20260831-092538`,
`20260831-092639`, and `20260831-092732`. Inspection confirmed version code 8
(`0.1.7`), package `com.dankhole.airlock`, min SDK 26, target SDK 36, and the
registered upload certificate. The copied AAB byte-matches the signed Gradle
output and uses the checksum recorded above. Physical-device qualification
remains open.

## Implemented Product Contract

- Dedicated three-step access gate for Usage Access, overlay access, and visible
  silent notifications; revoked access returns to the gate.
- Dashboard begins with Duty state and tracked-app usage, clearly distinguishes
  off, paused, recovering, and active enforcement, and exposes settings below.
- Two-step app picker and per-app limits with critical-app exclusions and
  master-PIN authorization while Duty is active.
- Foreground-service monitoring with overlapping UsageEvents, timestamped
  foreground/background evidence, delayed-event reduction, gesture recovery,
  current-boot lifecycle authority, bounded workers, batched persistence, health
  diagnostics, boot/update restart, and screen-off suspension.
- Full-screen dark blocker with a decision-first home and separate request,
  approval, and emergency forms. It makes active requests and additive new
  requests explicit, does not auto-open the keyboard, retains flow/input state
  across temporary hide/reopen, reports exact granted minutes, plays a Goose
  celebration, leaves safely on Back, bounds celebration windows, retains
  attached-window authority through removal failures, and cannot be reattached
  over known Home/Recents/explicit-exit state by stale lifecycle or aggregate
  foreground evidence.
- Four-digit requests and replies with a shared four-digit Master/Keyholder PIN
  other than `0000`.
  The platform-agnostic multiplication rule is implemented, explained in the
  request SMS, and accepted. Current debug and release build configurations
  also accept the hidden per-digit fallback: add 3 to each request-code digit
  modulo 10. Pending requests keep their original minutes for 1 hour, and
  changing the PIN revokes them.
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
original minutes and a 1-hour expiry. Request values may recur after expiry
or redemption; generation skips reply values that would collide with another
active request for the same app.

This is a deterrent, not strong authentication. A person who controls the
device, knows the PIN, or collects enough examples can bypass it. During
Internal testing, both debug and release builds accept the PIN result explained
in the SMS and the per-digit `+3` fallback, which remains hidden from release
copy. Removing the fallback remains an explicit later decision.

## Next Sequence

1. Complete the physical-device matrix in `TEST_PLAN.md`, including the
   Recents/app-switch timing checks and 48-72 hour
   reliability and battery checks on a Pixel and a current Samsung.
2. Diagnose the current tester installation error and confirm a clean install
   from the Internal testing opt-in link on a physical device.
3. Confirm remaining tester accounts, support email,
   and complete the `specialUse` foreground-service declaration/video before
   the first Play rollout.
4. When a replacement release is requested, prepare and validate a newly
   versioned bundle containing the September 13 blocker fixes, then upload it
   through Internal testing and confirm Play-delivered installation.
5. Collect reliability, approval-flow, and deterrence feedback, then explicitly
   decide when to retire the signed-release per-digit override and qualify the
   shared-PIN calculation.

## Known Release Gaps

- GitHub Actions build, smoke, and opt-in Internal publishing are prepared in
  `.github/workflows/android.yml`; all five GitHub environment secret names are
  configured, but a successful hosted smoke run and live publishing validation
  remain outstanding. Activation and signing-secret setup are in
  [`RELEASE.md`](RELEASE.md#github-actions-automation).
- The broad emulator smoke runner has intermittently shown an Android 17
  rotation/UI-dump race, although the consolidated candidate run passed it.
- The complete physical-device release matrix has not been recorded as passed.
- Large-font, landscape, tablet, TalkBack heading/navigation, and physical
  cutout behavior are not yet recorded as passed across the core flows.
- OEM force-stop, Active apps Stop, Restricted battery mode, and uninstall
  cannot be self-corrected without user action; this is an Android/platform
  limit, not a promised security boundary.
- Multi-window/PiP focus cannot be reliably inferred from a single UsageEvents
  candidate; those modes remain unqualified. Midnight/timezone changes and
  aggressive OEM background controls still need physical-device evidence.
- Conservative foreground and usage recovery can delay blocking when Android
  supplies ambiguous events or daily buckets that straddle local midnight.
  Existing saved usage totals are preserved, including prior overcounts; see
  `RELIABILITY.md` for the limits of the September 13 fixes.
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
