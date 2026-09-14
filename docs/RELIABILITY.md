# Monitoring Reliability

Last updated: September 13, 2026

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
| Delayed or replayed UsageEvents | Ten-second overlap from the previous query end, widened across scheduler gaps to a five-minute maximum | Reduce delayed evidence chronologically; reject resumes at/before a session boundary or matching background, and treat conflicting same-millisecond resumes as ambiguous |
| Slow successful foreground query | Result age exceeds two seconds | Retain its reduced history to keep the query cursor consistent, but remove the overlay, skip usage increments, and report recovery until a fresh query completes |
| Reboot/runtime restart or shutdown | Current boot time, `DEVICE_STARTUP`/`DEVICE_SHUTDOWN`, and shutdown broadcast | Never replay an open activity from the previous boot; shutdown removes the window while preserving requested Duty |
| Overlay loses focus | Identity-guarded focus callback after initial attachment | Remove the window and require later activity evidence; retain form state |
| Access or selection changes during a query | Recheck current Duty, required access, and selected apps at completion and immediately before attaching/retaining a window | Discard authorization from the request-time snapshot before counting or attaching |
| Unknown foreground after service creation | Five-minute lifecycle lookback bounded by the current boot/session | Wait for unambiguous activity evidence and report recovery; aggregate last-used timestamps never authorize a blocker |
| Stuck foreground query | Ten-second main-thread watchdog | Use at most two process-wide workers with no queue; reject additional work and retry every 30 seconds until a worker returns or the process restarts |
| Unexpected foreground-loop exception | Poll and completion boundaries | Remove stale UI, mark monitoring unhealthy, abandon the query identity, and schedule a bounded recovery poll |
| Overlay attach or post-attach initialization failure | Window attachment check and runtime exception boundary | Retain authority over any attached view, detach it immediately, report unhealthy monitoring, and retry attachment with exponential backoff |
| Overlay detach failure or deferred removal | Attached-view check after every `removeViewImmediate` outcome, including success | Make the retiring root invisible, retain the authoritative view reference and retry removal from 200 ms to 30 seconds; never let keyboard cleanup or persistence failure skip removal |
| Navigation during a grant celebration | Continued lifecycle polling, focus-loss removal, and independent four-second watchdog | Keep the ordinary celebration only over the same confirmed foreground app; detach it on navigation, unknown foreground, or deadline |
| Explicit Leave App or successful SMS launch | Successful destination launch | Hide immediately and establish a foreground-exit boundary so delayed events from the old guarded-app session cannot reattach the blocker over Home or Messages |
| Screen off or keyguard visible | Broadcasts, lifecycle boundary events, and fresh screen/keyguard checks at query start and completion | Detach, flush usage, discard earlier session authority, and resume querying after unlock |
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
`queryUsageStats()` is interval-aggregated and never supplies foreground
authority, even at startup. A last-used timestamp does not prove an app is still
in front. A bounded lifecycle lookback may recover current-boot activity
evidence; otherwise monitoring reports that it is waiting to identify the app.
Screen, keyguard, shutdown, startup, explicit exits, and clock changes establish
inclusive timestamp boundaries that an overlapping old resume cannot cross.
Conflicting events in the same millisecond leave the candidate empty until a
strictly later unambiguous resume.

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
their guarded package is confirmed foreground. An independent four-second main
handler deadline removes either celebration even while a Binder query is stuck.
Delayed animation and keyboard callbacks validate attachment/current ownership
and are canceled when their view leaves. Keyboard requests follow explicit
input taps and never use the cross-app-sticky `SHOW_FORCED` flag. Emergency-pass celebrations preserve
the no-UsageStats-query rule while the day pass is active.

## Conservative Recovery And Remaining Device Limits

Daily usage imports accept only individual Android daily buckets wholly inside
the captured local day and query end, with plausible durations. Results keep
their original day and are rejected after a midnight/timezone/clock mismatch.
Android can expand queries to whole buckets that straddle local midnight, so
those buckets are skipped instead of assigning yesterday's time to today.
Local polling and existing batched totals continue; system reconciliation may
recover only part of a day on devices with misaligned buckets. Existing saved
totals from earlier builds are preserved, including any earlier overcount;
there is no destructive automatic usage reset. Polling intervals are clipped
at the first observed new-day boundary and at imported-through elapsed-time
watermarks, so a reconciliation callback cannot count the same slice twice.

Losing overlay focus removes the window before delayed lifecycle polling can
carry it onto another app or system surface. Some system surfaces (for example
a notification shade) may return focus without another activity resume; Airlock
then reports recovery and waits for a real app-opening event. Likewise,
conflicting same-millisecond events favor an empty candidate over guessing.
Physical OEM testing must measure these conservative delays.

Android 10+ multi-window can keep multiple activities resumed and transfer top
focus without a resume/pause event. UsageEvents is not a general window-focus
API, and the current single-candidate monitor cannot guarantee correct full-screen
blocking in split-screen or picture-in-picture. Do not claim those modes are
qualified; include them in physical-device acceptance. See Android's
[multi-resume contract](https://developer.android.com/develop/ui/views/layout/support-multi-window-mode#multi-resume)
and [usage interval contract](https://developer.android.com/reference/android/app/usage/UsageStatsManager#queryUsageStats(int,%20long,%20long)).

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
overlay attachment failures, and stale-query recovery under
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
