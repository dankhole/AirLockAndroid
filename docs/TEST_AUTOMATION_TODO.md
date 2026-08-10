# Test Automation TODO

This backlog converts the functional and visual checks in `docs/TEST_PLAN.md`
into repeatable tests without adding AndroidX, Compose, Kotlin, or runtime
dependencies. Automation should target an emulator explicitly with `adb -e` so
it cannot accidentally drive a connected physical phone.

## Test Cadence

- Fast loop, after each coherent code batch: JVM unit tests, debug compile, and
  lint. No emulator is required.
- UI batch checkpoint: run one emulator smoke suite after a group of UI,
  lifecycle, permission, or overlay changes. Do not rerun it for every small
  text or styling edit.
- Release candidate: run the complete emulator suite, then the physical-device
  scenarios in `docs/TEST_PLAN.md` on a Pixel and a current Samsung device.
- Reliability release: additionally run the multi-hour lifecycle and battery
  soak. Emulator success does not replace this physical-device check.

## P0: Stable Test Surface

- [x] Give workflow-critical views stable resource IDs: requirement statuses,
  permission actions, app search/results, wizard navigation, limit input,
  duty switch, blocker inputs/actions, errors, and emergency disclosure.
- [x] Add debug-only fixture controls that can seed selected apps, limits,
  observed usage, pending approval requests, and emergency-code batches without
  weakening release behavior.
- [x] Add a debug-only way to render the blocker for a known package and usage
  state. The production service must remain the only release entry point.
- [ ] Inject clocks and foreground/usage sources at the narrow policy boundary
  so midnight, expiry, delayed UsageEvents, and recovery can be deterministic.
- [ ] Keep test fixture data in the emulator app sandbox and clear it only when
  a test explicitly requests a clean-install scenario.

## P0: Black-Box Emulator Smoke Runner

- [x] Add `scripts/android-smoke.sh` using `adb -e`; fail when zero or multiple
  emulators are available.
- [x] Build and install `app-debug.apk`, establish Usage Access, overlay, and
  notification test permissions, then launch `MainActivity`.
- [x] Use the platform `uiautomator dump` output and resource IDs to locate
  controls. Avoid fixed screen coordinates except for system Settings screens
  that expose no stable identifier.
- [x] Capture Logcat per scenario and fail on app-process crashes, ANRs, or
  repeated overlay add/remove loops.
- [x] Always restore rotation, font scale, animation scale, display size, and
  test permissions in a cleanup trap.
- [x] Save XML hierarchies, screenshots, and filtered logs under
  `app/build/reports/android-smoke/` on failure.

## P0: Functional Scenarios

- [x] Fresh setup stays on the dedicated gate until Usage Access, overlay, and
  notifications are ready; revoking overlay access returns to the gate.
- [x] After Android access is ready, the dashboard duty switch stays locked and
  names the first missing Keyholder, master-PIN, or app-limit requirement.
- [x] An over-limit tracked app explicitly reports that it is not being blocked
  while goose duty is off.
- [ ] App catalog shows loading, retry, empty, and populated states without
  blocking the main thread; search and selection remain correct.
- [ ] Wizard step, selected apps, search query, and typed minutes survive
  rotation; process death loses edit authorization and fails closed.
- [ ] Starting and stopping duty through the switch always requests the master
  PIN; cancel and invalid PIN preserve the previous state.
- [ ] Foregrounding an over-limit guarded app shows the blocker promptly after
  launcher taps, recents gestures, and rapid app switching.
- [x] Empty and invalid approval codes show an error without dismissing the
  blocker.
- [x] Requested minutes and approval-code input survive leaving and reopening
  the blocked app.
- [ ] Generate multiple approval requests for different minute values and
  redeem them out of order; each code must grant only the minutes hashed into
  that request, regardless of the current form value.
- [ ] A successful grant announces `The goose is loose for X minutes!`, keeps
  the exact granted amount, and plays or skips celebration according to the
  system animation setting.
- [ ] Emergency instructions remain collapsed by default. Generating a batch
  returns exactly three codes, replacing the batch invalidates every old code,
  and consuming one leaves two valid codes.

## P1: Native Instrumentation Tests

- [ ] Configure the platform `android.test.InstrumentationTestRunner` for the
  `androidTest` source set; do not add AndroidX Test solely for this MVP.
- [ ] Add in-process tests for MainActivity requirement rendering and duty
  switch cancellation.
- [ ] Add AppSelectionActivity recreation tests for both wizard steps and the
  process-local authorization session.
- [ ] Add blocker-controller tests for field retention, disclosure state,
  validation errors, grant announcements, and reduced-motion completion.
- [ ] Keep UsageStats, overlay-window, boot, and process-death behavior in the
  black-box runner because those contracts depend on Android system services.

## P1: Visual And Accessibility Checks

- [ ] Capture deterministic screenshots for main, both wizard steps, blocker,
  invalid-code state, and grant celebration on one pinned emulator image.
- [ ] Exercise portrait, landscape, a wide display, default font scale, and a
  large font scale. Assert critical controls remain visible or scrollable and
  that content stays outside status, cutout, keyboard, and navigation insets.
- [ ] Assert interactive bounds are at least 48dp and labeled inputs expose
  their associated labels in the UI hierarchy.
- [ ] Add tolerant image comparisons only after fonts, locale, API image, and
  animation state are pinned. Store failure diffs, not just pass/fail output.
- [ ] Keep a short manual TalkBack pass in the release checklist; hierarchy
  assertions cannot validate announcement order and real navigation quality.

## P2: Reliability And Performance

- [ ] Script boot, app update, service process kill, Android Active apps Stop,
  permission revoke/regrant, battery saver, device idle, screen off/unlock, and
  timezone/midnight transitions.
- [ ] Add a rapid-switch stress run and assert the foreground executor remains
  capped at two threads with no unbounded query queue.
- [ ] Run a 2-hour emulator soak that alternates guarded and unguarded apps and
  verifies blocker recovery, usage growth, notification state, and no crashes.
- [ ] Export `dumpsys meminfo`, `procstats`, `batterystats`, and relevant
  AirLock counters before and after the soak for trend comparison.
- [ ] Keep the 8-24 hour battery comparison on physical devices; emulator power
  measurements are useful for regression signals, not real battery claims.

## CI Exit Criteria

- [ ] Run JVM tests, assemble, and lint on every pull request.
- [ ] Run the P0 emulator smoke suite once per UI/lifecycle change set or on a
  scheduled build, not on every tiny edit.
- [ ] Upload screenshots, hierarchy dumps, and logs when emulator tests fail.
- [ ] Block release candidates on P0 functional failures, crashes, ANRs, or a
  missing physical-device test record.
