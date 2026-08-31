# Monitoring Reliability

Last updated: August 31, 2026

## Reliability Contract

Airlock provides intentional blocking friction through Usage Access and an
application overlay. It is not device-owner software and cannot guarantee
enforcement after the user force-stops it, revokes required special access, or
places it under manufacturer-specific background restrictions.

Within those Android limits, Airlock should:

- Keep the user's duty intent until a PIN-authorized stop.
- Report actual service health instead of equating a saved toggle with a live
  service.
- Recover from process recreation, reboot, app update, delayed UsageEvents,
  temporary UsageStats errors, and overlay attachment failures.
- Back off while degraded and avoid UsageStats work while the display is off
  or locked.
- Never use a permanent wake lock, exact-alarm restart loop, Accessibility
  Service, or broad package visibility as a reliability shortcut.

The current implementation satisfies these design boundaries in code and local
tests, but multi-day reliability and real battery impact are not considered
verified until the physical Pixel and Samsung runs in `docs/TEST_PLAN.md` are
recorded. Do not turn an emulator pass into a device-reliability claim.

## Failure And Recovery Matrix

| Failure | Detection | Recovery |
| --- | --- | --- |
| Normal process reclaim | Android recreates a `START_STICKY` foreground service | Rebuild workers and reconcile today's stored usage |
| Reboot | `BOOT_COMPLETED` | Start duty if it was requested |
| App update | `MY_PACKAGE_REPLACED` | Start duty if it was requested |
| Usage Access revoked or temporarily unavailable | AppOps check and query result | Keep service/notification alive; retry every 30 seconds |
| Overlay access revoked | Settings check | Hide blocker, show required state, retry every 30 seconds |
| Delayed or replayed UsageEvents | Ten-second overlapping query window plus timestamped foreground/background evidence | Clear a backgrounded candidate immediately, accept genuinely delayed lifecycle evidence in chronological order, and prevent stale overlap resumes from resurrecting an app after newer foreground or matching-background evidence |
| Unknown foreground after service creation | Five-minute lifecycle lookback plus 30-second aggregate sanity check | Seed only a never-observed initial candidate; aggregate stats never override a known launcher, System UI, app, or explicit background transition |
| Stuck foreground query | Ten-second main-thread watchdog | Use at most two process-wide workers with no queue; reject additional work and retry every 30 seconds until a worker returns or the process restarts |
| Unexpected foreground-loop exception | Poll and completion boundaries | Remove stale UI, mark monitoring unhealthy, abandon the query identity, and schedule a bounded recovery poll |
| Overlay attach or post-attach initialization failure | Window attachment check and runtime exception boundary | Retain authority over any attached view, detach it immediately, report unhealthy monitoring, and retry attachment with exponential backoff |
| Overlay detach failure | Attached-view check around `removeViewImmediate` | Retain the authoritative view reference and retry removal from 200 ms to 30 seconds; never let keyboard cleanup or persistence failure skip removal |
| Navigation during a grant celebration | Continued lifecycle polling and four-second watchdog | Keep the ordinary celebration only over the same confirmed foreground app; detach it on navigation, unknown foreground, or deadline |
| Explicit Leave App or successful SMS launch | Successful destination launch | Hide immediately and establish a foreground-exit boundary so delayed events from the old guarded-app session cannot reattach the blocker over Home or Messages |
| Screen off or keyguard visible | Screen/user-present broadcasts plus state check | Flush usage, stop querying, resume immediately after unlock |
| Android background mode is Restricted | `ActivityManager.isBackgroundRestricted()` | Show a required warning, continue best-effort checks while alive, and re-promote the service when Airlock is reopened after the restriction is removed |
| Android 13+ Active apps Stop or force-stop | No callback; later visible through `ApplicationExitInfo` | User must reopen Airlock; requested duty starts again |

## Efficiency Boundaries

- Normal foreground query: starts once per second while the device is
  interactive and unlocked; Binder query duration is included in that cadence
  rather than added after it.
- Gesture recovery: 200 ms for at most three seconds, then at most 500 ms
  through 15 seconds only for an already-blocked app.
- Full-day usage reconciliation: once per minute while interactive and
  unlocked.
- Usage persistence: dirty app totals in one batch every 30 seconds and on
  suspension/shutdown.
- Health persistence: at most once per minute unless the state changes.
- Android background-restriction state: checked at most once every 30 seconds
  during routine polling and immediately on an explicit service start.
- Foreground re-promotion after a battery restriction: immediately when
  Airlock is opened, with background attempts no more than once every 30
  seconds while the existing service remains alive.
- Foreground query failure: at most two active worker calls process-wide and no
  waiting query queue, including across service recreation in the same process.
- Settings usage refresh: at most one process-wide worker and no waiting queue,
  including across activity recreation.
- No UsageStats query while the display is off, the keyguard is visible, or an
  emergency day pass is active.

## Android Limits

Do not add a periodic exact alarm to relaunch monitoring. Android 12+ generally
blocks foreground-service starts from the background, and exact-alarm access
has user and Play policy implications. Repeatedly undoing the Android 13 Active
apps Stop action would also conflict with explicit user control.

Android's standard Restricted battery state can block foreground-service
starts, remove existing foreground services, suppress jobs/alarms, and delay
boot broadcasts. Manufacturer controls such as sleeping apps or auto-start
allowlists are not exposed through one portable API. Physical Pixel and Samsung
multi-day tests remain release requirements.

Retained blocker input is process-local UI state and does not participate in
foreground detection, UsageEvents queries, or polling cadence. Preserve it when
investigating blocker latency; gesture-switch regressions belong in the event
overlap/recovery path unless device evidence shows otherwise.

`UsageEvents` activity lifecycle transitions are the authoritative foreground
signal once any lifecycle state exists. A foreground event names the candidate;
a matching `PAUSED` or `STOPPED` event clears it until another foreground event
arrives. Polls overlap by ten seconds. The reducer retains the newest foreground
timestamp and each package's recent background timestamp, so an event delivered
late can still be incorporated when it fits the chronology while an older
guarded-app resume cannot override a newer foreground event or its own later
background event. Exact duplicates at the newest timestamp are skipped and
recent background evidence is bounded to the overlap window. This explicit
empty transition is different from having no startup information.
`queryUsageStats()` is interval-aggregated and may seed only the latter during a
bounded sanity check, after a wider lifecycle lookback. It must never replace a
known launcher, Recents, app, or empty transition state, because doing so can
attach the blocker after the guarded app has left the foreground.

The five-minute sticky-blocker record retains form state and identifies which
blocked package may need rebuilding after a temporary interruption. It never
supplies foreground authority. When the blocked app backgrounds, or Home,
Recents, launcher, or System UI is the lifecycle candidate, the overlay must be
removed while the service polls briefly for an actual return event. Query
failure or timeout also removes the overlay and reports unhealthy monitoring
rather than leaving a stale system-wide window attached. Back on a focused
blocker must provide the same Home escape as `Leave App!` so a focusable overlay
cannot trap all navigation. A successful explicit Home or messaging launch also
creates an event-time exit boundary: overlap events from the session being left
cannot reattach the blocker, while a later real resume of the guarded app can.

Window cleanup is authoritative over keyboard and storage cleanup. Airlock
clears its overlay reference only after the view is detached (or Android reports
that it is already absent); otherwise it retains the reference, reports degraded
health, and retries removal with bounded backoff. The same rule applies when
`addView` succeeds but later styling or focus initialization fails. Ordinary
unlock celebrations continue foreground polling and remain visible only while
their guarded package is confirmed foreground, with a four-second watchdog for
a lost animation callback. Emergency-pass celebrations use the same watchdog
but preserve the no-UsageStats-query rule while the day pass is active.

## Diagnostics

Useful device checks:

```sh
adb -e shell dumpsys activity services com.dankhole.airlock
adb -e shell dumpsys activity exit-info com.dankhole.airlock
adb -e shell appops get com.dankhole.airlock
adb -e shell dumpsys usagestats
adb -e shell dumpsys deviceidle whitelist
```

Debug builds log foreground transitions, recovery windows, query timeouts,
overlay attachment failures, and aggregate candidate seeding under
`AirlockMonitor`. They do not log access codes, phone numbers, or app usage
totals. `NAVIGATION_ONLY=true scripts/android-smoke.sh --skip-build` uses a
debug-only immediate trigger for the real sanity path; production polling
intervals remain unchanged.

## Android References

- Foreground-service overview and lifecycle:
  <https://developer.android.com/develop/background-work/services/fgs>
- `START_STICKY` service behavior:
  <https://developer.android.com/reference/android/app/Service#START_STICKY>
- Background foreground-service start restrictions and boot/update exemptions:
  <https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start>
- Android 13 Active apps Stop behavior:
  <https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping>
- User background restriction behavior:
  <https://developer.android.com/topic/performance/background-optimization>
- UsageStats and locked-device behavior:
  <https://developer.android.com/reference/android/app/usage/UsageStatsManager>
- Historical process-exit diagnostics:
  <https://developer.android.com/reference/android/app/ApplicationExitInfo>
