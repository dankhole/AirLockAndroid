# Airlock Product Plan

Last updated: August 11, 2026

## Purpose

Airlock adds intentional friction before using distracting Android apps
after a user-configured daily limit. It is a private accountability aid for an
adult managing their own device, not hard parental control or device security.

The core promise is simple:

1. Choose launchable apps to guard.
2. Give each app a daily usage budget.
3. Show a blocking Goose after that app reaches its budget.
4. Require a deliberate Keyholder approval step for extra time.
5. Keep all settings and usage data on the device.

## Current Product Contract

The MVP currently includes:

- A required first-run gate for Usage Access, Display Over Other Apps, and
  visible silent notifications. Revoking any of them returns to the gate.
- A two-step app-selection and per-app-limit wizard with safety-critical app
  exclusions and search.
- A master PIN required to start/stop Goose Duty, change the PIN, replace
  emergency codes, and edit limits while Duty is active.
- A Keyholder phone number and user-initiated SMS compose intent; Airlock never
  reads or sends SMS directly.
- Guarded-app-only usage totals with explicit active, off, paused, and unhealthy
  enforcement states.
- A foreground monitoring service with boot/update restart, health reporting,
  bounded recovery, gesture-switch detection, and conservative persistence.
- A dark, safe-area-aware Goose UI and full-screen blocker that retains both
  inputs, stays readable on narrow phones, and does not open the keyboard until
  the user touches an input.
- A navigation-safe blocker that clears for Home and Recents, keeps system
  controls reachable, and treats Android Back as an explicit safe exit.
- Multiple pending extra-time requests. Each approval grants the exact minutes
  saved when its request was created, even if the form changes later.
- Three one-time emergency codes per replacement batch. Replacing a batch
  revokes the old one; each valid code pauses all blocking for 24 hours.

See `docs/ARCHITECTURE.md` for mechanics and `docs/PRODUCT_LANGUAGE.md` for the
user-facing story.

## Deliberate Internal-Test Rule

The first testing release uses a six-digit request code and a deterministic
approval code produced by adding 5 to each digit modulo 10. Airlock stores the
approval value, requested minutes, and a 10-minute expiry locally so more than
one request can be pending and each grant is independent of the editable form.

This is intentionally transparent test plumbing. It validates monitoring,
blocking, SMS handoff, return navigation, local one-time redemption, and unlock
UX before investing in a real remote authorization system. It must not be
described as secure authentication or production-grade accountability.

## Target User And Claims

The primary user is an adult who wants help interrupting social media, video,
games, shopping, adult content, or another distraction loop. Do not market the
current build as a child-safety product. A device owner can revoke access,
force-stop, clear, or uninstall Airlock, and OEM background controls can delay
monitoring.

## Non-Goals For This MVP

- iOS, cloud accounts, remote dashboards, or cross-device sync.
- Uninstall prevention, Device Owner enrollment, or tamper-proof enforcement.
- Blocking specific in-app feeds, URLs, Reels, or Shorts.
- Direct SMS permissions, broad installed-app visibility, or Accessibility
  Service.
- Analytics, advertising, trackers, cloud backup, or app-usage uploads.
- Settings-change delays, daily unlock caps, or a permanent audit history.

## Product And Safety Invariants

- Never allow Airlock, launchers, phone/emergency, Settings, messaging, camera,
  autofill, or credential-provider apps to become blocked.
- Keep unmet requirements visible and name the exact recovery action. Never
  equate a saved Duty toggle with a healthy running service.
- Usage and approval state must survive ordinary process recreation without
  letting stale authorization bypass the master PIN.
- Errors and requirement states must remain understandable without color,
  animation, or Goose jokes.
- Emergency codes are recovery access, not ordinary Keyholder approvals.
- Overlay enforcement is friction. Product copy and store claims must say so.

## Roadmap Order

### 1. Internal Testing

- Finish the physical Pixel/Samsung matrix and multi-day reliability run.
- Resolve Play App Signing, Play verification, tester list, support email, and
  foreground-service declaration tasks.
- Release the current `+5` build to friends through Play Internal testing and,
  where useful, a correctly signed direct APK.
- Collect reliability, battery, setup clarity, and blocker-return feedback.

### 2. Real Keyholder Authorization

- Choose a backend SMS provider or Keyholder companion app.
- Generate approvals outside the limited device.
- Bind app, minutes, expiry, request identity, and one-time redemption to the
  authorization record.
- Add rate limits, attempt throttling, delivery/retry UX, privacy/security
  review, and migration away from the deterministic test rule.

### 3. Accountability Hardening

- Optional delayed setting reductions, cooldowns, daily grant caps, and a local
  audit trail.
- Better timezone/midnight handling and broader OEM evidence.
- Consider Device Owner only for a separate family/enterprise deployment; do
  not quietly turn the consumer app into device-management software.

## Distribution Posture

Google Play Internal testing is the primary friends-and-family path. Direct
signed APK distribution remains useful for early testing, but Play Protect may
warn about unknown-source installs. Package ownership and certificate
verification do not remove that sideload warning.

Before wider Play release, keep the privacy policy, Data safety answers,
foreground-service declaration, screenshots, and store claims synchronized
with the exact bundle. See `docs/RELEASE.md` and
`docs/PLAY_CONSOLE_SUBMISSION.md`.

## Reference Implementations

These informed the original MVP shape; do not copy code without reviewing
license and architecture fit:

- TapBlok: <https://github.com/cajdata/TapBlok>
- Curbox: <https://github.com/curbox-app/curbox-android>
- Open TimeLimit: <https://f-droid.org/en/packages/io.timelimit.android.open/>
- Mindful: <https://github.com/akaMrNagar/Mindful>

Relevant Android API and policy links are maintained in
`docs/ARCHITECTURE.md`, `docs/RELIABILITY.md`, and `docs/RELEASE.md` rather than
duplicated here.
