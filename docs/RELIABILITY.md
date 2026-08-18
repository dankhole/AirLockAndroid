# Monitoring Reliability

Last updated: August 11, 2026

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
| Delayed UsageEvents | Ten-second overlapping query window | Reprocess recent lifecycle events without double-counting elapsed time |
| Unknown foreground after service creation | 30-second aggregate sanity check | Seed an empty candidate only when no lifecycle event supplied one; aggregate stats never override a known launcher, System UI, or app candidate |
| Stuck foreground query | Ten-second main-thread watchdog | Use at most two process-wide workers with no queue; reject additional work and retry every 30 seconds until a worker returns or the process restarts |
| Overlay attach failure | `WindowManager.addView` exception | Exponential retry from one to 30 seconds |
| Screen off or keyguard visible | Screen/user-present broadcasts plus state check | Flush usage, stop querying, resume immediately after unlock |
| Android background mode is Restricted | `ActivityManager.isBackgroundRestricted()` | Show a required warning, continue best-effort checks while alive, and re-promote the service when Airlock is reopened after the restriction is removed |
| Android 13+ Active apps Stop or force-stop | No callback; later visible through `ApplicationExitInfo` | User must reopen Airlock; requested duty starts again |

## Efficiency Boundaries

- Normal foreground query: once per second while the device is interactive and
  unlocked.
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
signal once a candidate exists. `queryUsageStats()` is interval-aggregated and
is used only to seed an unknown candidate during a bounded sanity check. It must
never replace a known launcher or Recents candidate, because doing so can attach
the blocker to a system-navigation surface after the guarded app has left the
foreground.

The five-minute sticky-blocker record is only a recovery hint for canceled
Recents/launcher transitions and temporary overlay interruption. It does not
authorize drawing over a known system surface. When Home, Recents, launcher, or
System UI is the lifecycle candidate, the overlay must be removed while the
service polls briefly for a return to the guarded app. Back on a focused blocker
must also provide the same Home escape as `Leave App!` so a focusable overlay
cannot trap all navigation.

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
