# Architecture

Last updated: August 9, 2026

## Runtime Flow

```text
MainActivity
  saves settings
  starts MonitoringService
  reports requested duty separately from actual runtime health

MonitoringService
  starts as a foreground service
  polls recent UsageEvents on a background worker
  reconciles full-day UsageStats once per minute on a separate worker
  tracks the active foreground package
  keeps today's selected-app usage in memory
  persists dirty usage totals in one batch every 30 seconds
  shows overlay when usage >= that app's daily limit and no extra-time unlock is active
  suspends expensive queries while the display is off or locked
  retries transient permission, UsageStats, and overlay failures without clearing duty intent

Overlay
  blocks interaction with the selected foreground app
  can open SMS compose for a Keyholder request tied to requested minutes
  preserves in-progress requested minutes and approval-code entry while temporarily hidden
  accepts the code and grants the minutes associated with that code
  reveals the pre-generated emergency-code path only when requested
  accepts an emergency code and starts a global 24-hour pause
  can send the user back home
```

## Components

### `MainActivity`

The settings surface. It owns:

- Permission shortcuts.
- Monitoring on/off state.
- Keyholder phone number.
- Master override PIN setup.
- Navigation to app limit setup.

### `AppSelectionActivity`

Loads launchable apps off the main thread through `AppCatalogLoader`, using an
`ACTION_MAIN` / `CATEGORY_LAUNCHER` intent. The picker has loading, retry,
empty, and search states, and sorts apps with existing limits first.
The user selects one app or a group of apps, confirms, then assigns a daily
limit to that selection. `CriticalApps` removes safety-sensitive handlers
before the list is shown or saved. This avoids requesting broad package
visibility permissions. While duty is active, MainActivity passes a one-use,
process-local authorization token after master-PIN verification. Consuming the
launch token creates a process-local editing session that survives activity
configuration recreation but not process death. The editing session expires
after more than 30 seconds in the background. Wizard step, selected packages,
search text, and minute input survive activity recreation independently of the
authorization decision.

### `BlockerOverlayController`

Owns blocker form rendering, per-app transient form state, validation,
accessibility announcements, and the unlock celebration. `MonitoringService`
retains enforcement and lifecycle ownership through a narrow listener
contract. Emergency-code instructions are hidden behind a secondary reveal so
the ordinary request flow remains focused.

### `ForegroundEventPolicy`

Contains the pure UsageEvents lifecycle classification used by foreground
detection and overlay interruption recovery. Keeping these decisions outside
the service makes rapid-switch and gesture behavior directly unit-testable.

### `MonitoringNotificationFactory`

Creates the foreground-service channel and notification from monitoring health
state. The service decides health; the factory only renders it.

### `CriticalApps`

Builds a short-lived cached set containing AirLock, Android/system surfaces,
home apps, phone/dial handlers, messaging handlers, camera handlers, Settings,
detected autofill services, and Android 14+ credential-provider services such
as password managers. The picker refreshes this set after returning from
another app, and the service refreshes it immediately before a new blocking
session. `Preferences` also filters every read/write so a stale or manually
modified selection cannot make these apps blockable. Narrow
`<queries>` intent declarations provide only the package visibility needed for
those safety checks; the app does not request `QUERY_ALL_PACKAGES`.

### `MonitoringService`

A foreground service with a persistent notification. It is the runtime core of the MVP:

- Reads selected packages and limits from `SharedPreferences`.
- Infers the current foreground package from recent UsageEvents on a dedicated background thread.
- Runs full-day UsageStats reconciliation once per minute on a separate background thread.
- Adds elapsed milliseconds to an in-memory day/package counter and bulk-persists dirty totals every 30 seconds.
- Displays a `TYPE_APPLICATION_OVERLAY` view when a selected app is over limit.
- Generates numeric request codes and validates short-lived approval codes.
- Accepts hashed one-time emergency codes and suppresses blocking during an active 24-hour pause.
- Publishes a throttled health heartbeat and a visible recovery reason.

Normal foreground detection uses a one-second cadence and a ten-second
UsageEvents overlap so delayed events are not permanently skipped. Android 15+
queries filter to lifecycle event types. A newly observed launcher or system
surface starts a bounded gesture-recovery window that polls at 200 ms for up to
three seconds. Recovery for an already-blocked app can continue at 500 ms
through 15 seconds before returning to normal cadence. A low-frequency
UsageStats sanity check repairs a stale foreground candidate every 30 seconds
if the event stream missed a transition.

UsageStats work pauses while the screen is off or the keyguard is showing and
resumes immediately on screen-on/unlock. Foreground queries have a ten-second
watchdog and use a process-wide executor with at most two workers and no task
queue. One timed-out Binder call can use the second worker; if both workers are
occupied, new work is rejected and retries back off to 30 seconds. Hung calls
therefore cannot create an unbounded thread or queued-work leak. Neither
UsageStats query path runs on the main thread. Battery impact still needs
physical-device testing.

During an emergency day pass, the service keeps the enabled state and foreground notification but skips foreground and full-day UsageStats queries. It checks the pause deadline once per minute, survives reboot through `BootReceiver`, and resumes normal validation and polling automatically.

### `Preferences`

Small helper around `SharedPreferences`. Current keys:

- `enabled`
- `selected_packages`
- `limit_minutes_<packageName>`
- `accountability_number`
- `master_pin_hash`
- `master_pin_salt`
- `emergency_code_salt`
- `emergency_code_hashes`
- `emergency_pause_until`
- `usage_<yyyyMMdd>_<packageName>`
- `unlock_until_<packageName>`
- `approval_codes_<packageName>`
- `approval_code_expiry_<packageName>_<approvalCode>`
- `approval_code_minutes_<packageName>_<approvalCode>`
- `notification_permission_requested`

Usage keys older than seven days are pruned once per day. This remains acceptable for the MVP; move to Room only after usage history, analytics, or migrations become meaningful.

Android cloud backup and device-to-device transfer are disabled. This keeps
the Keyholder number, usage history, PIN material, and access-code state on the
current installation instead of restoring security-sensitive state onto a new
device.

Emergency-code plaintext is returned only to the generation screen for immediate display or sharing. Each replacement creates three codes. Only salted hashes are persisted. Replacing a set changes the salt and hashes in one committed write, revoking all old codes; consuming a code removes its hash before activating the pause.

Ordinary extra-time request records are synchronously persisted before the SMS
composer opens. Redemption removes the one-time approval metadata and writes
the package unlock deadline in one committed preference transaction, so a
process cannot persist only half of the grant.

### `BootReceiver`

Restarts `MonitoringService` after `BOOT_COMPLETED` or
`MY_PACKAGE_REPLACED` if monitoring was requested. These are Android-supported
background foreground-service start exemptions for this service type. This is
not enough by itself on every OEM; users may also need to set AirLock battery
use to Unrestricted.

### `MonitoringHealth`

Tracks whether the service is alive in the current process, persists a
once-per-minute heartbeat, records the last healthy poll and active recovery
reason, and reads `ApplicationExitInfo` on Android 11+ to explain a recent
service-process recovery. Health diagnostics never start or stop monitoring.

The persisted `enabled` preference means the user requested goose duty. It is
not treated as proof that the service is alive. Only an explicit PIN-authorized
stop clears that intent; missing access or transient Android failures leave it
set so the service can recover.

## Android API Choices

### Usage Access

`UsageStatsManager` gives enough information to infer which app is foregrounded. The manifest declares `PACKAGE_USAGE_STATS`, but users must enable the permission in Android Settings.

Why not Accessibility Service for MVP:

- It adds policy review and prominent disclosure requirements.
- It increases trust risk.
- It is not required for whole-app usage limits.

Accessibility can become useful later for blocking specific in-app surfaces like Reels or Shorts.

### Overlay Blocking

`SYSTEM_ALERT_WINDOW` plus `WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY` shows the blocking wall above normal apps. This is friction, not a security boundary. Apps and system surfaces can still prevent overlays or appear above them.

### SMS Access Code

The MVP uses `ACTION_SENDTO` with an `smsto:` URI and `sms_body`. That opens the user's messaging app and avoids direct SMS permissions.

Known weakness: the request code and requested minutes are visible in the compose screen. The local approval code is derived from the request code, and the approved duration is stored against that pending approval code. A stronger production version should generate and deliver the approval code server-side, or use a Keyholder companion app.

## Safety Rules

- Do not block the AirLock app itself.
- Keep phone/emergency, launcher, settings, messaging, camera, and detected
  password-manager packages unselectable. Extend `CriticalApps` when another
  safety-sensitive handler category is introduced.
- Do not add direct SMS permissions without deciding distribution channel and policy posture.
- Do not add Accessibility Service unless the feature requires it and the disclosure flow is implemented.
- Keep all usage data local unless the user explicitly opts into a backend feature.

## MVP Edge Cases

- Usage counters reset by date string at local midnight.
- Timezone changes can create odd counter boundaries.
- Multi-window and picture-in-picture may confuse foreground detection.
- If Usage Access is revoked, monitoring cannot detect apps.
- If overlay permission is revoked, blocking cannot display.
- AirLock remains alive in a degraded state and periodically retries when a
  requirement is temporarily unavailable.
- Force-stop and Android 13+'s Active apps Stop action kill the process without
  a callback. Android does not let an app guarantee an automatic restart after
  those explicit user actions; reopening AirLock restores requested duty.
- Android's Restricted battery mode can suppress foreground services and boot
  delivery. The app detects the standard restriction and restores foreground
  status when the user reopens AirLock after removing it, but OEM-specific
  auto-start and sleeping-app controls still require device testing.

## Next Architecture Upgrades

- Add a repository layer and typed settings object.
- Add setting-change delay for weakening limits.
- Add unlock attempt throttling.
- Add a backend SMS code provider if accountability becomes the core differentiator.
- Add platform device instrumentation when the project permits an appropriate
  runner without changing the no-AndroidX production constraint.
