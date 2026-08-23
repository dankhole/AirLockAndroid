# Architecture

Last updated: August 23, 2026

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

### Ownership Map

| Area | Primary owner |
| --- | --- |
| Access checks and first-run gate | `AndroidPermissions`, `RequiredAccessPolicy`, `PermissionSetupScreen` |
| Dashboard and local settings | `MainActivity`, `MasterPinPrompt`, `UsageSummaryRenderer` |
| App discovery and limit wizard | `AppCatalogLoader`, `AppPickerAdapter`, `AppSelectionActivity`, `EditAuthorization` |
| Monitoring lifecycle and recovery | `MonitoringService`, `MonitoringHealth`, `BootReceiver` |
| Foreground-event decisions and bounded work | `ForegroundEventPolicy`, `BoundedTaskExecutor` |
| Usage accounting | `UsageLedger`, `UsageTracker`, `Preferences` |
| Blocking form and unlock UX | `BlockerOverlayController`, `ApprovalCodePolicy`, `GooseCelebrationView` |
| Safety exclusions | `CriticalApps`, `Preferences`, `MonitoringService` |
| Styling, insets, keyboard, and mascot | `UiStyle`, `KeyboardHelper`, `GooseMascotView` |
| Notification rendering | `MonitoringNotificationFactory`, `NotificationAccessPolicy` |
| External privacy link | `AppLinks` |

Pure policy and persistence behavior is covered by local JUnit tests. Android
windowing, UsageStats delivery, process lifecycle, and OEM behavior require the
debug smoke runner or physical-device plan.

### State Ownership

| Lifetime | State | Owner |
| --- | --- | --- |
| Installation-persistent | Duty intent, limits, usage totals, Keyholder number, PIN hash and derived approval table, pending approvals, unlock deadlines, emergency codes/pause, health history | `Preferences` / app-private `SharedPreferences` |
| Process-local | Service-running truth, foreground candidate, bounded worker state, sticky blocker recovery, retained blocker fields, app-editor authorization sessions | `MonitoringHealth`, `MonitoringService`, `BlockerOverlayController`, `EditAuthorization` |
| Android-derived | Access grants, foreground lifecycle events, aggregate usage, keyguard/display state, background restriction, process-exit reason | Platform services through the narrow owners above |

Do not persist process-local edit authorization or blocker form state merely to
survive process death; doing so changes the security and recovery contract.
Conversely, do not use a process-local boolean as proof of durable Duty intent
or saved approval metadata.

### `MainActivity`

The launcher activity. It switches between a dedicated required-access gate and
the dashboard on every resume. The dashboard places Duty state and guarded-app
usage first, then owns:

- Permission shortcuts.
- Monitoring on/off state.
- Keyholder phone number.
- Shared Master/Keyholder PIN setup with exactly four digits other than `0000`.
- Navigation to app limit setup.

Notification visibility is part of the product gate even though Android can
technically keep a foreground service running without the Android 13 runtime
notification permission. This prevents Airlock from presenting invisible
background monitoring as a fully configured experience. If notification access
is revoked while Duty is already running, the dashboard returns to the gate but
the service remains best-effort active; notification visibility does not
silently clear the saved Duty intent.

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
the ordinary request flow remains focused. Initial attachment focuses the
window root and hides the keyboard; the keyboard opens only after explicit
input interaction. Requested minutes, approval entry, errors, and emergency
disclosure state survive temporary overlay removal for the same package. Back
with the keyboard closed routes to the same Home exit as `Leave App!`; Recents,
Home, and navigation surfaces must remain usable while the blocker is hidden.

### `UiStyle`

The programmatic design-system layer for plain platform Views. It owns the dark
palette, semantic status roles, typography, 52+ dp controls, cards, buttons,
inputs, responsive width hosts, system/cutout inset padding, and overlay
system-bar scrims. `GooseMascotView` and `GooseCelebrationView` are decorative
Canvas views and stay outside the accessibility tree. See `docs/DESIGN.md` for
the screen and accessibility contract.

### `ForegroundEventPolicy`

Contains the pure UsageEvents lifecycle classification used by foreground
detection and overlay interruption recovery. Keeping these decisions outside
the service makes rapid-switch and gesture behavior directly unit-testable.

### `MonitoringNotificationFactory`

Creates the foreground-service channel and notification from monitoring health
state. The service decides health; the factory only renders it.

### `CriticalApps`

Builds a short-lived cached set containing Airlock, Android/system surfaces,
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
- Generates four-digit request codes and validates short-lived four-digit
  approval codes.
- Accepts hashed one-time emergency codes and suppresses blocking during an active 24-hour pause.
- Publishes a throttled health heartbeat and a visible recovery reason.

Normal foreground detection uses a one-second cadence and a ten-second
UsageEvents overlap so delayed events are not permanently skipped. Android 15+
queries filter to lifecycle event types. A newly observed launcher or system
surface starts a bounded gesture-recovery window that polls at 200 ms for up to
three seconds. Recovery for an already-blocked app can continue at 500 ms
through 15 seconds before returning to normal cadence. Poll starts stay on that
cadence instead of adding Binder query duration after every interval. A
`PAUSED` or `STOPPED` event for the current candidate immediately creates a
known transition state and removes its overlay; only a later foreground event
can name the next candidate. A low-frequency UsageStats sanity check runs every
30 seconds but may seed only the service's initial, never-observed state. It
cannot replace a lifecycle candidate or an explicit transition state,
especially around launcher, Recents, or System UI.

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
- `master_approval_table_v1`
- `emergency_code_salt`
- `emergency_code_hashes`
- `emergency_pause_until`
- `usage_<yyyyMMdd>_<packageName>`
- `unlock_until_<packageName>`
- `approval_codes_<packageName>`
- `approval_code_expiry_<packageName>_<approvalCode>`
- `approval_code_minutes_<packageName>_<approvalCode>`
- `notification_permission_requested`
- `last_usage_prune_day`
- `health_*` service heartbeat, issue, exit, and recovery keys

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

The platform-agnostic approval rule implemented behind the current test
override is
`floor((request * Master PIN) / 100) mod 10000`, formatted to four digits. The
SMS describes the same operation in human terms: multiply the two four-digit
numbers, discard the product's last two digits, and return the last four digits
left, adding leading zeroes. Example: request `4321` and PIN `6789` produce
`29335269`, then approval `3352`.

The app must validate a reply without keeping the plaintext PIN. When a
four-digit PIN other than `0000` is saved, `Preferences` derives the approval
for all 10,000 requests and persists the resulting fixed-width table beside the
salted PIN hash. Request values may recur after redemption or expiry; generation
only skips candidates whose reply would collide with a currently pending reply
for that package. Changing the PIN atomically replaces the hash and table and
revokes all pending ordinary approvals. An upgraded install with an existing
valid four-digit PIN creates the table after the next successful PIN
verification; a legacy PIN of another length or `0000` must be changed before
Duty can be healthy.

Every generated approval value is stored in a per-package pending set with the
requested minutes and a 10-minute expiry. Several requests can coexist and can
be redeemed out of order. The current minutes field is never consulted during
redemption. Pending approval values and the PIN-derived lookup are app-private
records, not cryptographic authorization. Legacy single-code keys remain
readable only for upgrade compatibility.

### `BootReceiver`

Restarts `MonitoringService` after `BOOT_COMPLETED` or
`MY_PACKAGE_REPLACED` if monitoring was requested. These are Android-supported
background foreground-service start exemptions for this service type. This is
not enough by itself on every OEM; users may also need to set Airlock battery
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

### Test Surfaces And Internal Approval Override

The debug manifest alone exports `DebugFixtureReceiver` and
`DebugBlockerActivity`. They seed deterministic local state, render the real
blocker controller, and can ask an already-running debug service to execute the
production foreground-sanity path immediately with a log token. Release builds
contain neither exported component.

While Airlock remains on Internal testing, both debug and release build types
set `PLUS_FIVE_APPROVAL_OVERRIDE=true`, replacing the PIN-calculated result with
the per-digit `+5` transform. Main resources contain copy for both modes, and
the same BuildConfig flag selects the accepted result, SMS instructions, and
Master-PIN helper. Current test copy says `INTERNAL TEST OVERRIDE`. This release
override is temporary product configuration, not an exported debug component.
Retiring it for signed releases requires changing only the release flag; the
behavior and all related copy switch together.

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

The request code and requested minutes are intentionally visible in the compose
screen. After the internal override is retired, the Keyholder combines the
request with the shared PIN by hand, so no app, browser, account, or backend is
required. The current internal build instead uses its labeled `+5` test rule.
Neither calculation is cryptographic authentication; a device owner or someone
who observes enough examples may infer or bypass it. Airlock promises
accountable friction, not resistance to a determined attacker.

## Safety Rules

- Do not block the Airlock app itself.
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
- Airlock remains alive in a degraded state and periodically retries when a
  requirement is temporarily unavailable.
- Force-stop and Android 13+'s Active apps Stop action kill the process without
  a callback. Android does not let an app guarantee an automatic restart after
  those explicit user actions; reopening Airlock restores requested duty.
- Android's Restricted battery mode can suppress foreground services and boot
  delivery. The app detects the standard restriction and restores foreground
  status when the user reopens Airlock after removing it, but OEM-specific
  auto-start and sleeping-app controls still require device testing.

## Next Architecture Upgrades

- Add a repository layer and typed settings object.
- Split `MainActivity` and `MonitoringService` only at tested ownership
  boundaries; both remain large MVP orchestrators and high-regression areas.
- Inject clocks and foreground/usage sources at narrow policy boundaries for
  deterministic midnight, expiry, delayed-event, and recovery tests.
- Add setting-change delay for weakening limits.
- Consider unlock attempt throttling only after measuring whether the added
  friction improves the intended behavior.
- Retire the signed-release `+5` override after Internal testing, then qualify
  the PIN-calculated SMS and redemption flow as one coherent release change.
- Add platform device instrumentation when the project permits an appropriate
  runner without changing the no-AndroidX production constraint.
