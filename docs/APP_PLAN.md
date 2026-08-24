# Airlock Product Plan

Last updated: August 23, 2026

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
  emergency codes, and edit limits while Duty is active. The same exact
  four-digit PIN other than `0000` is held by the Keyholder for ordinary
  approval calculations.
- A Keyholder phone number and user-initiated SMS compose intent; Airlock never
  reads or sends SMS directly.
- Guarded-app-only usage totals with explicit active, off, paused, and unhealthy
  enforcement states.
- A foreground monitoring service with boot/update restart, health reporting,
  bounded recovery, gesture-switch detection, and conservative persistence.
- A dark, safe-area-aware Goose UI and full-screen blocker with a decision-first
  home, focused request/approval/emergency forms, retained input state, and no
  automatic keyboard opening.
- A navigation-safe blocker that clears for Home and Recents, keeps system
  controls reachable, and treats Android Back as an explicit safe exit.
- Multiple pending extra-time requests. Each approval grants the exact minutes
  saved when its request was created, even if the form changes later.
- Four-digit request and approval codes. The platform-agnostic PIN calculation
  is implemented, while current debug and signed Internal-testing builds keep
  the simpler `(request + 5656) mod 10000` override enabled.
- Three one-time emergency codes per replacement batch. Replacing a batch
  revokes the old one; each valid code pauses all blocking for 24 hours.

See `docs/ARCHITECTURE.md` for mechanics and `docs/PRODUCT_LANGUAGE.md` for the
user-facing story.

## Platform-Agnostic Approval Rule And Internal Override

The implemented platform-agnostic flow uses exactly four digits for the request,
shared Master/Keyholder PIN, and approval. For request `4321` and PIN `6789`,
the product is `29335269`; discarding its last two digits and taking the last
four digits left produces approval `3352`. Leading zeroes are significant.

Airlock precomputes the 10,000 possible approval results when the PIN is set,
then stores that PIN-derived lookup locally alongside the salted PIN hash. It
never stores the plaintext PIN. `0000` is excluded because it would make every
PIN-calculated reply identical. Changing the PIN revokes pending ordinary
approvals. Each pending approval stores its requested minutes and a 10-minute
expiry so requests can coexist and remain independent of the editable form.
Request values may recur after their pending records expire or are redeemed;
only collisions with currently pending replies are skipped.

This deliberately simple calculation removes the need for a Keyholder app,
browser, account, or backend. It is human-scale friction, not cryptographic
authorization: someone who observes enough examples or controls the device can
bypass it. Product copy and store claims must preserve that distinction.

While Airlock remains on the Play Internal testing track, debug and release
builds replace the PIN-calculated result with `(request + 5656) mod 10000`.
Their composed SMS includes only the request details and never explains how the
reply is derived. The normal
calculation remains implemented and covered by pure unit tests, but is not the
accepted reply in current internal builds. Retiring the override is an explicit
post-test decision, not an automatic consequence of assembling a release APK.

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
- Release the four-digit internal-test build with the additive override,
  to friends through Play Internal testing and, where useful, a correctly
  signed direct APK.
- Collect reliability, battery, setup clarity, approval-flow usability, and
  blocker-return feedback.
- After Internal testing, explicitly decide when to disable the override and
  qualify the shared-PIN calculation in a new signed candidate.

### 2. Accountability Hardening

- Optional delayed setting reductions, cooldowns, daily grant caps, and a local
  audit trail.
- Consider attempt throttling only if testing shows it improves deterrence
  without presenting the local calculation as a security boundary.
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
