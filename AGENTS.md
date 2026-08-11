# AirLock Android Agent Guide

## Start Here

AirLock Goose is a local-first Android app that counts usage for user-selected
apps, shows an overlay after a per-app daily limit, and grants extra time with a
Keyholder approval code. It is intentional friction, not hard device security.

Before changing code:

1. Run `git status --short --branch`; preserve all existing worktree changes.
2. Read [`docs/README.md`](docs/README.md) and the task-specific source of truth
   it points to. Read [`docs/PROJECT_STATUS.md`](docs/PROJECT_STATUS.md) for the
   current release stage and unresolved decisions.
3. Trace the implementation and tests. Documentation describes intent, but
   code and tests establish current behavior.
4. Do not commit, push, publish, or copy distributable release artifacts unless
   the user asks.

## Non-Negotiable Constraints

- Plain Java Android views only: no AndroidX, Compose, Kotlin, or third-party
  runtime dependencies. JUnit 4 is test-only.
- Keep `minSdk 26`, package `com.dankhole.airlockandroid`, and the existing
  signing identity unless the user explicitly approves a migration.
- Do not add `SEND_SMS`, `READ_SMS`, `RECEIVE_SMS`, `QUERY_ALL_PACKAGES`, or an
  Accessibility Service for the current product.
- Keep usage, settings, phone number, PIN material, and code state local. Cloud
  backup and device transfer must remain disabled unless a reviewed migration
  changes the privacy model.
- Never make phone, launcher, Settings, messaging, camera, AirLock, autofill,
  or credential-provider apps blockable. Preserve defense in depth in
  `CriticalApps`, the picker, `Preferences`, and the service.
- Do not claim uninstall prevention, tamper-proof enforcement, parental-control
  security, or guaranteed OEM background execution.

## Product Invariants

- Usage Access, overlay access, and visible silent notifications form the
  required first-run gate. Revocation returns the user to that gate.
- Keyholder number, master PIN, and at least one app limit are required before
  Goose Duty can start. Starting, stopping, and editing active limits require
  the master PIN.
- Usage summaries show guarded apps only and must state when an over-limit app
  is not blocked because duty is off, paused, or unhealthy.
- The blocker opens without the keyboard. Requested minutes and approval entry
  survive temporary hide/reopen. Multiple requests may be pending; each grant
  uses the minutes saved when its request was generated, not the current field.
- The current internal-test approval rule is the deterministic per-digit `+5`
  transform with a 10-minute local record. Treat it as temporary test plumbing;
  the next accountability phase replaces it with real authorization.
- Emergency access is exactly three random one-time 8-digit codes per batch.
  Replacing a batch revokes all old codes; only salted hashes persist. One code
  pauses all blocking for 24 hours without turning duty off.
- Foreground-service notifications remain silent. The successful extra-time
  state announces the exact granted minutes and plays the goose celebration
  when system animation settings permit it.

## Engineering Priorities

Use this order when tradeoffs conflict:

1. Multi-day monitoring reliability and self-recovery.
2. Accurate, visible health and permission state; never silently fail open.
3. Prompt blocking during gesture navigation and rapid app switching.
4. Bounded battery, CPU, thread, and storage cost.
5. Limit, usage, authorization, and persisted-state integrity.
6. Maintainable native Java, accessibility, and polished dark goose theming.

Do not casually rewrite `MonitoringService`, `Preferences`, or
`BlockerOverlayController`. Preserve overlap polling, bounded executors,
batched writes, atomic code redemption, retained form state, and recovery
backoff unless tests and device evidence justify a change. See
[`docs/RELIABILITY.md`](docs/RELIABILITY.md).

## Implementation Conventions

- Put reusable colors, spacing, typography, cards, buttons, inputs, status
  treatments, safe-area handling, and responsive width rules in `UiStyle`.
  Keep the dark palette and consolidated green family; primary green buttons
  use high-contrast light text. Follow [`docs/DESIGN.md`](docs/DESIGN.md) for
  screen hierarchy, accessibility, system-bar, and Goose visual rules.
- Put user-visible text and plurals in `strings.xml`. Follow
  [`docs/PRODUCT_LANGUAGE.md`](docs/PRODUCT_LANGUAGE.md) for Goose/Keyholder
  roles and keep requirement/error copy clear before making it playful.
- Keep UsageStats, package loading, and other Binder/disk work off the main
  thread. Concurrency must be bounded with no unbounded queues.
- Prefer narrow, behavior-preserving changes and focused pure-Java tests. Debug
  fixtures belong under `app/src/debug` and must never enter the release
  manifest.
- Update the relevant source-of-truth document in the same change when product
  behavior, architecture, privacy, reliability, testing, or release facts move.

## Validation

For a coherent code batch, run:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Also run `:app:assembleRelease :app:bundleRelease` for release/signing changes.
Run `scripts/android-smoke.sh` once at a meaningful UI, permission, service, or
overlay checkpoint, not after every small edit. For blocker navigation work,
run `NAVIGATION_ONLY=true scripts/android-smoke.sh --skip-build`. Before
release readiness, run [`docs/TEST_PLAN.md`](docs/TEST_PLAN.md) on a physical
Pixel and Samsung device; emulator success does not validate multi-day
reliability or battery impact.

## Code Review Rules

- Flag any path that can show Duty as healthy when service/access is unhealthy.
- Flag unbounded polling, queued Binder calls, per-second preference writes,
  restart loops, wake locks, or exact alarms proposed as reliability fixes.
- Flag approval grants that read current form minutes instead of persisted
  request metadata, non-atomic one-time-code consumption, or stale critical-app
  selections that could survive filtering.
- Flag any aggregate `UsageStats` fallback that can replace a foreground
  candidate already established by lifecycle events, especially launcher,
  Recents, or System UI. It may seed only an unknown candidate.
- Flag new sensitive permissions, data egress, backup enablement, release-key
  changes, or Play declarations that no longer match the shipped build.
