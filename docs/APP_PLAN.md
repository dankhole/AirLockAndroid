# AirLock Android Plan

Last updated: August 7, 2026

## Purpose

AirLock Android brings the same core idea as the AirLock browser extension to mobile apps: add intentional friction before using distracting apps after a configured amount of time. The goal is not absolute device lockdown. The goal is a practical, privacy-preserving accountability tool that interrupts automatic app-opening behavior and makes extra access require a deliberate action involving another person.

The first product shape is:

- Choose apps to limit.
- Set a daily usage budget per selected app.
- Show a full-screen blocking wall after the budget is exhausted.
- Allow an extra-time grant when the user enters a one-time approval code tied to requested minutes.
- Let a numeric request code be sent to a preset Keyholder phone number.

## Target User

The primary user is an adult who wants self-accountability for social media, video, games, adult content, shopping, or other distraction loops. A secondary user is a parent or guardian setting limits for a child, but the first MVP should avoid claiming hard parental-control guarantees until we have a hardened deployment mode.

## Non-Goals For MVP

- No iOS support.
- No cloud account.
- No remote dashboard.
- No content-specific blockers for Reels, Shorts, URLs, or in-app feeds.
- No direct SMS permission.
- No attempt to prevent uninstall or settings changes beyond normal Android affordances.
- No Play Store claim that this is an accessibility tool for disabled users.

## MVP Scope

The MVP should prove the core loop with the least Android-policy risk:

1. Onboarding screen explains the required special permissions.
2. User grants Usage Access.
3. User grants Display Over Other Apps.
4. User selects one launchable app or a group of apps.
5. User confirms that selection and sets its daily limit in minutes.
6. User enters a Keyholder phone number.
7. User sets a local master override PIN.
8. A foreground service polls the foreground app.
9. Usage time accrues only while a selected app is foregrounded.
10. When the daily limit is exceeded, an overlay appears.
11. The overlay asks how many extra minutes the Goose should request and can compose a text message to the Keyholder.
12. Entering the valid approval code grants the minutes bound to that generated request code.
13. The master PIN can create five one-time emergency codes for pre-authorized 24-hour recovery access.

## Recommended Architecture

### App Layer

- `MainActivity`: settings, permission shortcuts, monitoring start/stop.
- `AppSelectionActivity`: lists launchable apps and saves selected package names.
- `MonitoringService`: foreground service that polls the current foreground package and manages blocking.
- `BootReceiver`: restarts monitoring after reboot if the user left monitoring enabled.
- `Preferences`: small local storage helper around `SharedPreferences`.

### Data Stored Locally

- Monitoring enabled flag.
- Selected package names.
- Per-app daily limit minutes.
- Keyholder phone number.
- Master PIN hash and salt.
- Per-day usage counters keyed by date and package name.
- Temporary unlock expiration timestamps keyed by package name.
- Short-lived one-time approval codes keyed by package name and code, with requested minutes stored per code.
- Salted hashes for five one-time emergency codes and the active 24-hour pause deadline.

No data should leave the device in the MVP except text the user explicitly sends through their chosen SMS app.

## Android APIs

### Foreground App Detection

Use `UsageStatsManager` and `UsageEvents` to infer the active foreground app. Android requires `android.permission.PACKAGE_USAGE_STATS`; declaring the permission is not enough, because the user must grant Usage Access in Settings.

MVP behavior:

- Poll recent usage events every second on a dedicated background worker.
- Use a short adaptive 200/500 ms recovery burst after recents interrupts a blocking overlay, then return to the one-second cadence.
- Reconcile full-day UsageStats separately once per minute on another background worker.
- Treat `MOVE_TO_FOREGROUND` and `ACTIVITY_RESUMED` as foreground signals.
- Accrue elapsed time only when the same selected package remains active.
- Persist in-memory usage totals in a single batch every 30 seconds and prune usage keys older than seven days.

### Blocking UI

Use `WindowManager` with `TYPE_APPLICATION_OVERLAY` on Android 8.0+ and request `SYSTEM_ALERT_WINDOW`. The user must explicitly enable "Display over other apps." This is enough for a basic wall, but it is not hard security.

### Service Lifetime

Use a foreground service with a persistent notification. On Android 14+ foreground services require types and policy declarations if distributed through Google Play. For an MVP, `specialUse` is the closest fit, but this must be reviewed before Play submission.

### SMS Code

Avoid `SEND_SMS` in the MVP. Google Play heavily restricts SMS and Call Log permissions. Use an `ACTION_SENDTO` intent with an `smsto:` URI to open the user's SMS app with a prefilled message.

This has an accountability weakness: the request code and requested minutes are visible to the user before the message is sent to the Keyholder. The local approval code is derived from the request code, and the approved duration is stored against that pending approval code, but a production-grade version should use one of these instead:

- Backend-generated code sent by Twilio or another SMS provider.
- Companion app for Keyholders.
- Direct `SEND_SMS` only for sideload/F-Droid builds, after accepting the distribution tradeoff.

## Hardening Options After MVP

- Add a setting-change delay so weakening limits takes effect later.
- Add a cool-down before granting an unlock.
- Add daily unlock caps.
- Add code expiration and attempt throttling.
- Exclude critical apps by default: dialer, emergency, launcher, settings, SMS, camera, password manager.
- Add tamper notices when permissions are revoked.
- Add Device Owner mode for family/enterprise installs where real policy enforcement is required.
- Add a local VPN only if website blocking becomes a goal.
- Add Accessibility Service only if we need in-app feature blocking or stronger foreground detection, and include the required disclosure/consent flow.

## Policy And Compliance Risks

- `PACKAGE_USAGE_STATS` exposes app usage behavior and should be requested only with clear user explanation.
- `SYSTEM_ALERT_WINDOW` is powerful and visually intrusive, so the app must be transparent about why it is used.
- `AccessibilityService` can trigger Play review and disclosure requirements. Use narrower APIs first.
- Direct SMS permissions are usually not allowed unless the app is the default SMS, Phone, or Assistant handler.
- Installed-app inventory can be sensitive. Prefer querying launchable apps through package visibility declarations instead of requesting broad `QUERY_ALL_PACKAGES`.

## Open-Source References

### TapBlok

URL: https://github.com/cajdata/TapBlok

Why it matters: smallest useful architectural reference. It is Kotlin, Apache 2.0, uses Usage Access, overlay blocking, a foreground monitoring service, QR/NFC unlock, schedules, and boot persistence. This is the best model for a focused MVP.

### Curbox

URL: https://github.com/curbox-app/curbox-android

Why it matters: most feature-complete open-source app/site blocker reference. It includes app limits, unlock mechanisms, granular UI blocking, usage insights, and settings-delay ideas. It is GPL-3.0-or-later, so copying code would require compatible licensing.

### Open TimeLimit

URL: https://f-droid.org/en/packages/io.timelimit.android.open/

Why it matters: mature open-source parental-control/time-limit reference. Its F-Droid listing documents usage access, overlays, notification access, accessibility, extra time, multi-user support, and device-admin anti-uninstall concepts.

### Mindful

URL: https://github.com/akaMrNagar/Mindful

Why it matters: open-source screen-time app with Play Store presence, Flutter UI, Kotlin platform code, app limits, blocking, local VPN, notifications, and parental controls. Useful for product ideas, less ideal for this MVP if we want the smallest native scaffold.

## Official References

- UsageStatsManager: https://developer.android.com/reference/android/app/usage/UsageStatsManager
- PACKAGE_USAGE_STATS permission: https://developer.android.com/reference/android/Manifest.permission#PACKAGE_USAGE_STATS
- SYSTEM_ALERT_WINDOW permission: https://developer.android.com/reference/android/Manifest.permission#SYSTEM_ALERT_WINDOW
- TYPE_APPLICATION_OVERLAY: https://developer.android.com/reference/android/view/WindowManager.LayoutParams#TYPE_APPLICATION_OVERLAY
- SMS compose intents: https://developer.android.com/guide/components/intents-common#Messaging
- Google Play SMS and Call Log policy: https://support.google.com/googleplay/android-developer/answer/10208820
- Google Play AccessibilityService policy: https://support.google.com/googleplay/android-developer/answer/10964491
- AccessibilityService API: https://developer.android.com/reference/android/accessibilityservice/AccessibilityService
- DevicePolicyManager: https://developer.android.com/reference/android/app/admin/DevicePolicyManager

## Milestones

### Milestone 1: Local MVP

- Native Android project builds.
- Permission prompts work.
- User can select apps.
- Foreground service starts and stops.
- Usage time accrues for selected apps.
- Overlay appears after limit.
- Code unlock grants extra time.

### Milestone 2: Reliability

- Better foreground detection fallback.
- Boot persistence.
- Daily reset edge cases.
- Battery optimization guidance.
- App exclusions and safety list.
- Attempt throttling.

### Milestone 3: Accountability

- Replace the deterministic request-code conversion with backend-generated SMS.
- Add Keyholder contact verification.
- Add rate limits and audit trail stored locally.
- Add unlock caps and optional delay.

### Milestone 4: Distribution

- Decide Play Store vs F-Droid/sideload.
- Add privacy policy and prominent disclosures.
- Review foreground service type declaration.
- Avoid Accessibility unless necessary.
- Prepare demo video for Play review if sensitive APIs are used.
