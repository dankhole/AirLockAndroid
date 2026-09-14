# Unexpected Blocker Investigation

September 13, 2026. Investigated against `7eb82d8`; fixes are uncommitted local
source changes. Existing build, release, CI, and standalone smoke-target work
was preserved. This investigation did not publish or install a phone release.

## Scope And Findings

Traced every production overlay attachment/removal path, foreground reduction,
query scheduling/completion, boot/update/shutdown recovery, permissions, critical
app filtering, usage reconciliation, persisted limits/unlocks, SMS/Home exits,
retained forms, celebration callbacks, and service teardown. Separate reviews
covered the integrated fixes, followed by a fresh second investigation pass.

| Confirmed code path | Trigger and consequence | Fix |
| --- | --- | --- |
| Startup five-minute activity history ignored shutdown/startup | An unmatched guarded-app resume from before reboot can become the new candidate | Bound history to the current boot; consume shutdown/startup, screen-off, and keyguard boundary events, including events without package names |
| Aggregate foreground seeding | A recently used app can be selected despite no proof it is still foreground | Remove aggregate foreground authority entirely; expose recovery when activity evidence is unavailable |
| Query-start `Math.max` | A polling gap longer than ten seconds skips intervening departure events | Overlap the previous query end, with a bounded five-minute catch-up |
| Same-millisecond event ambiguity | Replayed resumes can win against an indistinguishable pause or another foreground app | Background/exit evidence wins; conflicting foreground events remain empty until a later unambiguous resume |
| Slow query completion | A result can describe an app left seconds ago | Retain successful reduced history but prohibit attachment from a result older than two seconds |
| Repeated slow-result rejection | Discarded departures plus an advancing query cursor can resurrect an older guarded candidate | Keep reduced evidence and cursor consistent through every successful result; regression covers multiple stale snapshots |
| Cached device/access/request state | A query finishes after locking, revoking access, stopping Duty, or changing selection | Recheck current gates at completion, and again after package lookup/view construction immediately before attaching or retaining a window |
| Overlay focus loss | An attached full-screen window can outlive navigation while lifecycle delivery catches up | Retire the window on focus loss; preserve the event boundary at the time focus was lost |
| Delayed Home/SMS launch completion | Setting the exit boundary after a slow launch can discard a legitimate later return | Capture the boundary immediately before starting the destination |
| Deferred/failed window removal | Clearing references too early loses cleanup authority, or a retained visible window keeps covering other apps | Make retiring roots invisible, verify actual detachment after every outcome, retain ownership and bounded retries |
| Celebration/keyboard callback lifetime | Delayed callbacks can fire after their view is hidden, detached, or replaced | Cancel pending animation completions; require current generation, attachment, visibility, and appropriate focus; remove keyboard `SHOW_FORCED` that can keep the IME visible across apps on older Android versions |
| Celebration deadline tied to polling | A stuck foreground query can postpone the maximum celebration lifetime | Add an independent four-second removal deadline, including emergency celebration |
| Notification gate omitted from service | Duty can continue blocking after visible notifications are disabled | Apply the same notification visibility requirement in service polling and attachment gates |
| Partial critical-app discovery refresh | A failed lookup can discard previously discovered protected handlers | Preserve prior critical discoveries on incomplete refresh; retire them only after a complete refresh |
| Daily aggregate usage buckets | Android may return a whole bucket containing yesterday's usage, producing an early limit that persists across reboot | Import only plausible individual daily buckets wholly inside the captured local day |
| Usage date and interval attribution | A query spanning midnight or an imported partial polling interval can count against the wrong day or count twice | Keep query day/elapsed-time metadata; reject day/timezone mismatches and clip ledger increments at day/import boundaries |

The first review found the repeated-stale-result and ambiguous-health issues;
the second pass found the final-attachment gate, focus-boundary timing, retiring
window visibility, and critical-cache failure paths. Those findings were
implemented before final validation.

## Validation

All 99 JVM tests, debug assembly, and lint passed. The expanded navigation
matrix and full UI smoke flow passed, followed by a focused final-APK keyboard
and reboot check. Report paths and exact validation sequence are recorded in
`PROJECT_STATUS.md`. Focused JVM
coverage exercises delayed/replayed events, inclusive reboot/exit boundaries,
ambiguous timestamps, query gaps/freshness, repeated stale snapshots, midnight,
timezone/DST boundaries, impossible usage buckets, and reconciliation overlap.

The expanded `android-smoke.sh` matrix checks gesture and three-button
navigation, Home/Settings service recreation, sleep/wake, actual WindowManager
detachment, and real notification-channel revocation/restoration. Background
restart tests receive a ten-second emulator-only temporary allowance because a
debug broadcast does not inherit Android's authorized boot/sticky-start context.
A shell notification app-op was rejected as a test substitute after device
evidence showed it did not disable the current Android notification channel.

## Limits Of The Conclusion

These are reproducible code paths and regression fixes, not proof that each one
caused the owner's particular phone symptom. No physical phone trace or current
installed-version evidence was available during this task.

Android's UsageEvents API does not provide a general current-window-focus
query. Split-screen/PiP multi-resume and missing or unusually delayed OEM events
remain device qualification gaps. A conservative empty candidate can delay
blocking after an ambiguous transition or a system surface that returns without
an activity resume. The service exposes recovery instead of silently claiming
active enforcement in those states.

Rejecting a usage bucket that straddles midnight can recover less historical
time on devices whose Android bucket boundaries differ from the local day.
Local polling and persisted totals continue. Existing saved totals were not
reset, so any earlier overcount remains until that stored day's normal expiry.
See `RELIABILITY.md` and the physical Pixel/Samsung matrix in `TEST_PLAN.md`.

## September 14 Follow-Up

The follow-up closes three gaps from the review: initial attachment could reuse
the snapshot captured before view construction; an attached blocker had no
independent two-second evidence expiry while a query stalled; and package-only
activity reduction could clear B after `A.pause`, `B.resume`, `A.stop`.

The blocker now prepares its view before a separate foreground confirmation,
uses a per-window lightweight watchdog for expired evidence and absent focus,
and carries activity-class identity through reduction and replay deduplication.
An irrelevant older activity stop does not rebuild the current window. Empty
foreground state and stale cached state cannot restore healthy monitoring.
The reducer state also replaces the service's duplicated foreground fields and
long query argument lists. Timing and resource budgets live in `RELIABILITY.md`.

Focused tests cover the lifecycle handoff, deadline boundaries, and ambiguous
identity. The debug navigation harness adds delayed-result navigation, stalled
query removal, never-focused attachment, and a real two-activity smoke target.
Final validation evidence is recorded in `PROJECT_STATUS.md`. These source
changes do not resolve the platform multi-window/PiP qualification limits above.

## Platform Evidence

- [Android activity and device event semantics](https://developer.android.com/reference/android/app/usage/UsageEvents.Event#DEVICE_SHUTDOWN): shutdown stops activities without individual stop events; old open sessions cannot cross startup.
- [Android UsageStats query intervals](https://developer.android.com/reference/android/app/usage/UsageStatsManager#queryUsageStats(int,%20long,%20long)): query ranges may expand to whole intervals.
- [Android keyguard state](https://developer.android.com/reference/android/app/KeyguardManager#isKeyguardLocked()): checking current keyguard visibility is distinct from retaining a previously unlocked boolean.
- [Android multi-resume](https://developer.android.com/develop/ui/views/layout/support-multi-window-mode#multi-resume): focus can change while several activities stay resumed.

- [Android keyboard flags](https://developer.android.com/reference/android/view/inputmethod/InputMethodManager#SHOW_FORCED): forced-show requests can keep the keyboard visible after the calling app closes.
