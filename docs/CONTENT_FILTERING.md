# Content Filtering — Implementation Plan

## Scope and decisions

The owner requested selective native short-form blocking and a master-PIN-protected
settings submenu, and explicitly approved the optional Accessibility scope change.
This is intentional friction, not secure device control or universal content filtering.
Selective content filtering requires Android 13/API 33 or later, where public
Accessibility cache controls let Airlock obtain uncached authority. The app's
minSdk remains 26; ordinary app limits continue on older supported devices.

- Initial targets: YouTube Shorts, Instagram Reels, Facebook Reels, Snapchat
  Spotlight, plus an optional Instagram Stories filter. Safety exclusions always
  win: a supported app classified as a camera/messaging/other critical handler
  cannot be filtered and must be shown as unavailable. Facebook detection is
  best-effort and locale/version dependent. Instagram Explore is deferred because
  inspected selectors were explicitly unverified and overlap Search.
- Prefer resource IDs and active-player structure over generic words such as
  “Reels”. A visible navigation tab, recommendation tile, ordinary video, message,
  or search result must not by itself trigger blocking.
- All filters default off. A versioned prominent disclosure and affirmative
  consent precede opening Android's Accessibility settings. Enabling the Android
  service alone is not consent.
- Content filters live in one separate settings activity, accessible through a
  master-PIN prompt regardless of whether Duty is on. Authorization is process-local,
  survives configuration recreation, expires after a bounded background interval,
  and is never restored from a saved boolean after process death.
- Filters run only with Goose Duty requested, ordinary prerequisites present,
  the device unlocked/interactive, and no emergency day pass. Existing Duty setup
  requirements (including at least one app limit) remain unchanged.
- Detection navigates Back out of a supported feed; it does not replace the
  existing usage monitor or create another full-screen overlay. Repeated actions
  are rate-limited and stale/ambiguous detections do nothing.
- Ordinary app-time approvals do not disable content filters. The emergency day
  pass pauses both. The UI must explain this distinction.
- No browser filtering, VPN, DNS filtering, keyword inspection, semantic adult/
  violent-content classification, downloaded blocklists, or promise to block every
  short video. Unsupported app versions/locales/surfaces remain explicit limitations.

## Architecture and ownership

### Rules and settings

`ContentFilter` is the fixed catalog. `ContentDetectionPolicy` contains pure-Java
recognition and action-budget logic using bounded, ephemeral node evidence.
`ContentFilterSettings` stores only allowlisted enabled filter IDs and the current
consent version in app-private preferences. Unknown/corrupt settings fail safely.
No screen text, tree, screenshot, viewed item, URL, or viewing history persists.
Tests cover defaults, invalid IDs, consent versioning, defensive copies, supported
matches, benign near-matches, package isolation, stale evidence and action budgets.

### Optional platform service

`ContentFilterService` is a platform Java AccessibilityService, declared with
BIND_ACCESSIBILITY_SERVICE and narrowly scoped event types and package names.
It is not an accessibility tool for disability assistance. No gestures,
key interception, screenshot capability, generic package visibility, networking,
or additional foreground service is added.

The service listens only to selected supported packages, and performs no tree
inspection without consent and current enforcement gates. Work is event-driven,
coalesced, and bounded: one worker/no unbounded queue, bounded node count/depth/time,
minimal UI-thread work, recycled nodes, and no permanent polling. In-flight work
is invalidated on configuration, lifecycle, screen, and permission changes.
Immediately before Back, revalidate current consent/settings, Duty/pause/access,
critical-package exclusions, fresh active focused application window/package,
and positive feed evidence. Never fall back to Home or repeated blind Back actions.

`ContentFilterAccess` exposes a separate content-filter status: disabled, Duty off,
paused, consent required, access required, prerequisites unavailable, service
unavailable, or ready. An enabled Android setting alone is not a live service.
Connection, interrupt, failed action, exhausted work and teardown are reflected
conservatively. No content detail is logged.

The app-limit monitor continues working if the optional service is unavailable.
Dashboard and silent notification must not imply every configured protection is
healthy when content filtering is unavailable. Preserve usage accounting, overlap
polling, watchdogs, approval redemption, overlay state, and recovery backoff.

### Settings UI

Use platform Views and `UiStyle`, with text in `strings.xml` and stable view IDs.
The submenu has a concise status, master switch/consent controls, per-surface
choices, supported-app limitations, permission setup/recovery, and explicit Back.
Returning from Android settings rechecks authorization and current service state.
No external intent, rotation, process recreation, canceled PIN, or background
expiry may expose editable filters without authorization. Every mutation rechecks
authorization. Consent can be withdrawn, immediately preventing future inspections.
The main dashboard shows a non-editable summary and the PIN-gated entry point.

## Shared implementation interfaces

These are the worker integration contract; coordinate changes through the lead.
All classes are in `com.dankhole.airlockandroid`.

- `ContentFilter` enum values: `YOUTUBE_SHORTS`, `INSTAGRAM_REELS`,
  `FACEBOOK_REELS`, `SNAPCHAT_SPOTLIGHT`, `INSTAGRAM_STORIES`.
  Accessors `id()` and `packageName()`; `fromId(String)` returns null for unknown.
  UI owns mapping enum values to string resources.
- `ContentFilterSettings.configuredFilters(Context)` returns a defensive,
  allowlisted set. `enabledFilters(Context)` additionally removes critical
  packages. `setEnabled(Context, ContentFilter, boolean)` rejects unsafe enabling;
  `hasConsent(Context)`, `setConsent(Context, boolean)` use versioned consent.
  Use `Preferences.prefs(context)`, with SharedPreferences overloads for JVM tests.
  No dependency from these classes on service or UI.
- `ContentDetectionPolicy.NodeEvidence(String packageName, String viewId,
  String description, boolean visible, boolean positiveBounds, boolean selected)`;
  `matches(ContentFilter, List<NodeEvidence>)` is pure and package-exact.
  Do not read node text. Only Facebook needs bounded description matching.
- `ContentFilterAccess.State`: `DISABLED`, `DUTY_OFF`, `PAUSED`,
  `CONSENT_REQUIRED`, `ACCESS_REQUIRED`, `PREREQUISITES_REQUIRED`,
  `UNSUPPORTED`, `PARTIAL`, `UNAVAILABLE`, `READY`. `state(Context)`, `statusText(Context)`,
  `warning(Context)` (empty unless a configured protection needs attention),
  `androidAccessState(Context)` (`UNKNOWN`, `ENABLED`, `DISABLED`), and
  `openSettings(Context)` (boolean success).
  Service owner implements. UI owner provides `content_filter_status_*` strings
  using lowercase state names; plus `content_filter_service_label`,
  `content_filter_service_description`, `content_filter_blocked_notice`.
- `ContentFilterSettingsActivity.EXTRA_AUTHORIZATION_TOKEN` is used by the
  dashboard after PIN verification. Reuse process-local `EditAuthorization`
  tokens/sessions, but never allow unverified editing merely because Duty is off.
- The platform owner owns service XML and the main manifest (including the new
  non-exported settings activity), `MonitoringService` notification-refresh hook,
  and `MonitoringNotificationFactory`. UI owner owns `MainActivity`, the new
  activity, `strings.xml`, `ids.xml`, and any necessary `UiStyle` additions.
- Rule owner owns catalog/settings/detection and their tests. Platform owner owns
  service/access/action-policy tests. Lead owns documentation and integration.

### Verified selector baseline

Scrolless commit `9bc0dd0edc022c7be2a8a9af1181064c6d73014c`,
`core/domain/src/main/java/com/scrolless/app/core/model/BlockableApp.kt`:

- YouTube: `com.google.android.youtube:id/reel_player_page_container`.
- Instagram Reels: `com.instagram.android:id/clips_viewer_view_pager`.
- Instagram Stories: `com.instagram.android:id/reel_viewer_root`.
- Snapchat Spotlight: `com.snapchat.android:id/spotlight_container`.
- Facebook package `com.facebook.katana`: visible exact case-insensitive
  description `FbShortsComposerAttachmentComponentSpec_STICKER` or
  `FbShortsComposerAttachmentComponentSpec_GIF`, or a visible selected node
  whose description starts `Reels,` case-insensitively. Require positive bounds.
  Do not copy the generic RecyclerView/Button/SurfaceView fallback: it can match
  ordinary video. Unselected Reels tabs must never match.

These are source-backed, not device-qualified selectors. Include attribution to
Scrolless (Copyright (C) 2026 Scrolless) and GPL-3.0-or-later for adaptations. Airlock already
ships GPLv3 text; do not import upstream runtime dependencies.

## Implementation sequence and parallel work

1. Read implementation/tests, verify upstream sources/licenses, and freeze shared
   interfaces and supported surface choices.
2. In parallel: rules/settings/tests; platform service/manifest/health integration;
   PIN-gated settings/UI/resources. Files have disjoint owners. The lead integrates
   docs, privacy, provenance and validation; only the lead runs aggregate Gradle.
3. While implementation runs, an independent reviewer checks this plan for privacy,
   Android lifecycle, false positives, authorization, performance and policy gaps.
   Incorporate findings through the owning workers before integration.
4. Inspect actual diffs and run the standard Gradle batch. Add feature-specific
   debug/emulator checks without shipping debug fixtures in release.
5. Once coherent, run independent parallel reviews for runtime safety/correctness
   and authorization/privacy/UI/tests. Address findings, then rerun affected checks.

## Validation and release gates

- `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`.
- Existing emulator smoke suite once at the integrated checkpoint; focused content
  checks for PIN cancellation/wrong PIN, process recreation, expiry, default-off,
  decline/consent, Android-enable-without-consent, revocation, Duty off, emergency
  pause, normal app screens, stale detections, and bounded action behavior.
- Review merged release manifest/source/resource boundaries; no new runtime
  dependency, Internet/SMS/QUERY_ALL_PACKAGES permission, backup, signing, or
  identity change. Do not publish or copy distributable artifacts. The owner
  subsequently authorized a local source commit after validation; no push.
- Actual supported app versions/locales and Pixel/Samsung behavior need physical
  device qualification; synthetic fixtures and pure tests cannot prove them.
- Update privacy policy and hosted mirror, architecture/product/reliability/design,
  test matrix, Play accessibility declaration/disclosure/video requirements, and
  project status. Play Console actions and approval remain owner release gates.
- Shut down and verify all task-started emulators/build/smoke processes; preserve
  anything that was running before the task.

## Plan review decisions

The independent concurrent plan review required these refinements, routed to
implementation owners before integration:

- Process-wide worker limits survive service reconnects; canceling a Future is
  not evidence that a blocked Binder call exited. No replacement-worker leak.
- Reject obscuring/system/IME, multi-app and PiP ambiguity before global Back;
  use monotonic freshness including traversal and a bounded repeated-action fuse.
- Critical-app discovery must be complete and fresh, not merely missing a target
  from a partial set. An additive `CriticalApps.hasReliableSnapshot()` exposes
  uncertainty without changing app-limit behavior; content filtering fails closed.
  Preserve configured intent separately from currently eligible filters.
- Validate editor expiry on every mutation and delayed dialog callback, not just
  session existence. Never reuse the app-limit editor's Duty-off bypass.
- Optional failures stay outside `MonitoringHealth.currentIssue()`; rendering
  combines health independently and refreshes on optional-service state changes.
- Never use empty/null event package filters as an off switch; Android interprets
  those as all packages. Gate before obtaining sources/roots and inspect Facebook
  descriptions only for that package. Consent withdrawal invalidates work.

## Final review follow-ups

Parallel runtime and authorization/privacy reviews identified five concrete issues:
edge-to-edge windows overlapping persistent system bars; framework-cached window
and node data mislabeled as fresh; one unsafe saved filter suppressing safe filters;
Facebook description disclosure broader than the actual categorical exclusions;
and unknown Android permission state being mislabeled as disabled. These were
corrected with focused policy tests and truthful independent states. Follow-up
review also required a distinct partial-protection warning when safe filters run
while another saved choice is excluded. Concurrent critical-app discovery retains
positive safety exclusions even when a newer optional lookup is stuck or fails.
Native-app/version/locale qualification remains a separate release gate, not a
code-review pass.

## Upstream evidence and implementation record

Pinned selectors and licenses were checked against the actual upstream files;
see [`../THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md). Play/Android references:

- <https://developer.android.com/guide/topics/ui/accessibility/service>
- <https://support.google.com/googleplay/android-developer/answer/10964491>
- <https://support.google.com/googleplay/android-developer/answer/16558241>

### Implemented runtime budgets

- One process-wide inspection/status worker, with no waiting queue; stuck work
  is never replaced by another worker. Settings discovery has its own process-wide
  single worker so UI recreation cannot accumulate Binder calls.
- Content events coalesce for 500 ms; rejected dispatch has at most three finite
  retries. A 1.5-second job watchdog invalidates late work.
- Each scan is capped at 256 nodes, depth 24, 32 children per node and 150 ms;
  window inventories are capped at 32. Evidence must be no more than 250 ms old.
- Back has a two-second global cooldown and at most three attempts per minute,
  including failed attempts. A matched window remains latched until a complete
  fresh non-feed scan rearms it. This intentionally favors avoiding navigation
  loops over uninterrupted enforcement during rapid repeated re-entry.
- Platform health probes are off-main, refresh after five seconds when requested,
  and expire after 30 seconds (at most one 1.5-second in-progress renewal grace).
  Action gates always sample current platform state rather than trusting that cache.
- Public cache bypass is required before inspecting window/root evidence; old
  cached accessibility facts are not retimestamped as fresh. Persistent system
  bars are validated against current WindowInsets rather than rejecting every
  overlap with an edge-to-edge app. Safe selected filters can run even if another
  saved choice becomes excluded, while overall configuration health warns.
- No inspection loop runs while idle. Android focus inspection and global Back
  are separate operations; a residual race cannot be eliminated by this API.

### Completed local validation

- Final aggregate: **211 JVM tests**, debug assembly/lint, release manifest merge
  and release Java compilation passed. Lint retains only the existing Gradle-version
  and blocker `TargetApi` advisory warnings. No release bundle was published.
- **20 Python host tests** passed, including 14 content-smoke helper tests.
- Existing integrated emulator smoke passed:
  `app/build/reports/android-smoke/20261031-112554/`.
- Current-debug-APK content settings smoke passed all 12 checkpoints with no
  restoration errors:
  `app/build/reports/content-filter-smoke/20261031-121744-492726/summary.json`.
  This covers wrong/canceled/correct PIN with Duty off, default-off choices,
  decline, enabled-without-consent, consent, exact service subscriptions/cache
  state, rotation, background expiry, process recreation, withdrawal, and rejected
  external activity launch. Native wrong-PIN error copy has screenshot evidence;
  XML assertions verify rejection plus correct-PIN entry through the same dialog.
- UIAutomator temporarily suppresses ordinary accessibility services. The Android
  access row therefore reads the configured secure setting independently from
  live connection health. Debug-only bounded metadata diagnostics check actual
  subscriptions without reading windows/nodes; they do not ship in release.
- Release copy audit, release-manifest boundary checks and `git diff --check`
  passed. The privacy mirror matches. Follow-up reviewers closed all findings.
- The task-started Pixel_8 emulator, ADB server and build/test processes were
  stopped; process checks found no remaining task processes.

These checks do **not** qualify real target-app selectors, Back actions, supported
app versions/locales, OEM geometry, battery cost or multi-day physical-device
reliability. Pixel/Samsung testing and the Play Accessibility declaration,
disclosure demonstration and review remain release gates.
