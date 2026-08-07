# Architecture

Last updated: August 7, 2026

## Runtime Flow

```text
MainActivity
  saves settings
  starts MonitoringService

MonitoringService
  starts as a foreground service
  polls UsageStatsManager every second
  tracks the active foreground package
  increments today's usage counter for apps with configured limits
  shows overlay when usage >= that app's daily limit and no extra-time unlock is active

Overlay
  blocks interaction with the selected foreground app
  can open SMS compose for an accountability code tied to requested minutes
  preserves in-progress requested minutes and approval-code entry while temporarily hidden
  accepts the code and grants the minutes associated with that code
  can send the user back home
```

## Components

### `MainActivity`

The settings surface. It owns:

- Permission shortcuts.
- Monitoring on/off state.
- Accountability phone number.
- Master override PIN setup.
- Navigation to app limit setup.

### `AppSelectionActivity`

Queries launchable apps through an `ACTION_MAIN` / `CATEGORY_LAUNCHER` intent. The user selects one app or a group of apps, confirms, then assigns a daily limit to that selection. This avoids requesting broad package visibility permissions.

### `MonitoringService`

A foreground service with a persistent notification. It is the runtime core of the MVP:

- Reads selected packages and limits from `SharedPreferences`.
- Infers the current foreground package from `UsageStatsManager`.
- Adds elapsed milliseconds to the current day/package counter.
- Displays a `TYPE_APPLICATION_OVERLAY` view when a selected app is over limit.
- Generates numeric request codes and validates short-lived approval codes.

The service deliberately uses a simple one-second poll because it is easier to reason about than a more complex scheduler. Battery impact needs device testing.

### `Preferences`

Small helper around `SharedPreferences`. Current keys:

- `enabled`
- `selected_packages`
- `limit_minutes_<packageName>`
- `accountability_number`
- `master_pin_hash`
- `master_pin_salt`
- `usage_<yyyyMMdd>_<packageName>`
- `unlock_until_<packageName>`
- `approval_codes_<packageName>`
- `approval_code_expiry_<packageName>_<approvalCode>`
- `approval_code_minutes_<packageName>_<approvalCode>`

This is acceptable for MVP. Move to Room only after usage history, analytics, or migrations become meaningful.

### `BootReceiver`

Restarts `MonitoringService` after `BOOT_COMPLETED` if monitoring was enabled. This is not enough by itself on every OEM; users may also need to exempt the app from aggressive battery management.

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

Known weakness: the request code and requested minutes are visible in the compose screen. The local approval code is derived from the request code, and the approved duration is stored against that pending approval code. A stronger production version should generate and deliver the approval code server-side, or use a companion-accountability app.

## Safety Rules

- Do not block the AirLock app itself.
- Keep phone, emergency, launcher, settings, messaging, camera, and password manager exclusions before any hardening work.
- Do not add direct SMS permissions without deciding distribution channel and policy posture.
- Do not add Accessibility Service unless the feature requires it and the disclosure flow is implemented.
- Keep all usage data local unless the user explicitly opts into a backend feature.

## MVP Edge Cases

- Usage counters reset by date string at local midnight.
- Timezone changes can create odd counter boundaries.
- Multi-window and picture-in-picture may confuse foreground detection.
- If Usage Access is revoked, monitoring cannot detect apps.
- If overlay permission is revoked, blocking cannot display.
- Force-stop prevents boot receivers until the app is manually opened again.

## Next Architecture Upgrades

- Add a repository layer and typed settings object.
- Add a safety exclusion list before any strict mode.
- Add setting-change delay for weakening limits.
- Add unlock attempt throttling.
- Add a backend SMS code provider if accountability becomes the core differentiator.
- Add instrumentation tests after the project compiles locally.
