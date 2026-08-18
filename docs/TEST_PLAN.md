# Test Plan

Last updated: August 11, 2026

## Static Checks

Run after Android tooling is available:

```sh
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
./gradlew :app:lintDebug
adb -e shell dumpsys package com.dankhole.airlock
```

Confirm the installed package reports `targetSdk=36`.

The local unit suite verifies edit-authorization expiry, process-local editor
sessions, bounded query execution, foreground event classification,
approval-code duration policy, atomic approval redemption and rollback with
multiple pending requests, three-code emergency-batch replacement,
notification visibility policy, and monitoring-exit recovery classification.

See `docs/TEST_AUTOMATION_TODO.md` for the staged work to automate this manual
matrix without running a full emulator pass after every small change.

Current automation evidence and known harness failures live in
`docs/PROJECT_STATUS.md`. A focused navigation pass does not imply the broad
smoke flow or physical matrix passed, and an automation hierarchy/rotation
failure must be triaged separately from an app crash or state-loss failure.

## Manual MVP Tests

### Permission Onboarding

Expected result: required Android access is unmistakable and cannot be skipped.

1. Open app fresh.
2. Confirm the dedicated permission screen appears instead of the dashboard.
3. Confirm the introduction says foreground usage is processed locally and app
   lists or usage history are not uploaded.
4. Tap `Read Privacy Policy` and confirm the public policy opens in a browser.
5. Confirm Usage Access, Display Over Other Apps, and Notifications each show
   `NOT DONE`, with a `0 of 3` progress state.
6. Grant Usage Access.
7. Return to app and confirm Usage Access shows `DONE`, its action is hidden,
   and progress shows `1 of 3`.
8. Grant overlay permission.
9. Return and confirm Overlay shows `DONE` and progress shows `2 of 3`.
10. Grant notifications and confirm the permission screen automatically gives
   way to the dashboard.
11. Confirm the duty switch is at the top, remains locked, and names the first
   unfinished product setting below it.
12. Confirm the compact Android-access review section and working privacy-policy
    link are at the bottom of the dashboard.
13. Revoke Usage Access, overlay access, or notifications and return to Airlock.
    Confirm the permission screen replaces the dashboard and identifies the
    exact missing item.
14. Restore the revoked access and confirm the dashboard returns automatically.
15. Start duty and confirm the ongoing `Airlock goose watch` notification makes
    no sound and does not vibrate on first creation or service status updates.
16. Disable only the `Airlock goose watch` notification channel. Confirm the
    permission screen reports Notifications as `NOT DONE` and opens that
    channel's settings.
17. On Android 8-12, disable app notifications and confirm the permission screen
    reports Notifications as `NOT DONE` instead of assuming they are available.
18. Set Android battery use to Restricted and confirm Airlock reports the
    reliability warning on the dashboard without treating it as a permission.

### App Limit Setup

Expected result: app limits persist.

1. Open `Set Goose Limits!`.
2. Select one safe test app.
3. Continue to the limit step.
4. Press Back and confirm the wizard returns to app selection rather than
   exiting, including with predictive back on Android 13+.
5. Confirm the launcher, Settings, dialer/phone, messaging, camera, Airlock,
   and any installed autofill or Android credential-provider password manager
   are not selectable.
6. Search by app label and package name, confirm nonmatching rows disappear,
   and confirm existing limited apps sort before unlimited apps.
7. Select multiple apps, rotate the device, and confirm the search and
   selections remain.
8. Continue again, enter daily limit `1`, rotate the device, and confirm the
   second wizard step, selection, and typed limit remain.
9. Save the limit.
10. Force-close Airlock.
11. Reopen and confirm configured app count is retained.
12. With duty active, enter the master PIN and open the limit picker. Rotate
    the device and confirm the authorized editor and wizard state remain.
13. Put it in
    Recents for more than 30 seconds, return, and confirm editing is locked.
14. Open it again with the PIN, background it, kill the Airlock process without
    removing its task, and restore the task. Confirm the old launch Intent does
    not restore editing authorization.

### Limit Enforcement

Expected result: selected app is blocked after its daily budget is exhausted.

1. Set daily limit to `1`.
2. Set a Keyholder phone number and master PIN.
3. Start goose duty with the master PIN.
4. Open the test app.
5. Wait at least 65 seconds.
6. Confirm the overlay appears.
7. Confirm the keyboard does not open until an input is explicitly tapped.
8. Confirm its content clears the status bar, camera cutout, gesture area, and
   keyboard; system-bar icons remain readable against the dark blocker.

### Recents And App Switching

Expected result: a blocked app never becomes interactive after a gesture or app switch; its overlay returns within one monitoring poll.

1. Put an over-limit app in recents, open an unblocked app, and use the bottom-edge horizontal gesture to quick-switch into the over-limit app.
2. Confirm the blocking overlay appears in well under one second and the app behind it cannot be tapped.
3. Partially swipe up toward recents, then cancel the gesture back into the blocked app.
4. Confirm the overlay is visible again in well under one second and the app behind it cannot be tapped.
5. Open recents fully and confirm Airlock does not cover the recents screen or prevent selecting another app.
6. Remain in Recents for at least 35 seconds and confirm the blocker does not
   reattach after the aggregate foreground sanity check.
7. Dismiss the blocked app's task, go Home, and confirm Recents, Home, and the
   launcher remain usable.
8. Return to the blocked app and confirm the overlay is rebuilt and still
   contains any unfinished request minutes or approval code.
9. With the keyboard closed, press Android Back from the blocker and confirm it
   performs the same safe exit as `Leave App!` rather than trapping navigation.
10. Repeat while the number keyboard is open and confirm the first Back closes
    the keyboard and a following Back leaves the guarded app.
11. Repeat steps 5-10 with gesture navigation and three-button navigation.

### Extra-Time Code

Expected result: valid approval code removes the overlay for the minutes requested when the request code was generated.

1. Enter a Keyholder phone number.
2. Trigger the blocking overlay.
3. Enter requested extra minutes, such as `1`.
4. Tap `Text the Keyholder!`.
5. Confirm the SMS compose screen says `The Goose is asking for X minutes of extra time`, identifies the app, and includes the numeric request code.
6. Return to the overlay and confirm the requested minutes field is still populated and editable.
7. Change the requested minutes and tap `Text the Keyholder!` again.
8. For this internal-test build, derive each approval by adding 5 to every
   request-code digit modulo 10. Confirm either valid approval grants the
   minutes requested when that specific code was generated.
9. Enter a partial approval code, leave the overlay, return, and confirm the approval code entry is still populated.
10. Enter an incorrect approval code and confirm it is rejected.
11. Confirm emergency instructions are hidden by default, reveal them with
    `Use emergency code`, then hide them again without losing either input.
12. Enter a valid approval code, tap `Loose the Goose!`, and confirm the goose animation plays before the overlay disappears with `The goose is loose for X minutes!`.
13. Keep the blocked app foregrounded and confirm the overlay returns after the extra time expires.

### Responsive And Accessible UI

Expected result: core flows remain readable and operable without relying on
color or animation.

1. Test the permission screen, main screen, both app-limit steps, and blocker in portrait,
   landscape, and a tablet-width emulator. Confirm content is centered at a
   readable width and no text or controls overlap.
2. Set Android font size to its largest setting and repeat the core flow.
   Confirm text wraps, controls remain at least 48 dp tall, and every action is
   reachable by scrolling.
3. Enable TalkBack and confirm requirement states, app selection state,
   labeled inputs, validation errors, and the granted-minute announcement are
   read in a logical order. Record that section-title heading navigation is
   currently a known gap if it has not been implemented before this run.
4. Disable system animations and confirm a successful grant completes without
   waiting for or requiring the goose animation.

### Stop Goose Duty

Expected result: blocking stops when goose duty is disabled.

1. Trigger the overlay.
2. Use `Leave App`.
3. Open Airlock.
4. Tap `Stop Goose Duty!` and enter the master PIN.
5. Open the selected app.
6. Confirm no overlay appears.

### Emergency Day Pass

Expected result: the Keyholder can prepare one-time recovery codes, and one valid code pauses every guarded app for exactly 24 hours without permanently disabling goose duty.

1. With goose duty on, open Airlock, expand emergency access, and tap `Generate 3 New Codes`.
2. Enter an incorrect master PIN and confirm no codes are generated.
3. Enter the correct master PIN, confirm replacement, and verify three distinct 8-digit numeric codes appear.
4. Share or record the codes, tap `Hide Codes`, reopen Airlock, and confirm plaintext codes cannot be displayed again.
5. Enter an invalid 8-digit emergency code and confirm it is rejected without reducing the remaining-code count.
6. Enter one generated code and confirm the app reports an emergency pause with the exact automatic-resume date and time.
7. Open multiple over-limit guarded apps and confirm no blocking overlay appears during the pause.
8. Re-enter the consumed code after the pause has ended or test data has been reset and confirm it is rejected.
9. Use a second valid code from the blocking overlay and confirm the overlay closes after the emergency-day-pass animation.
10. Reboot during an active pause and confirm the paused notification returns and guarded apps remain unblocked.
11. Generate a replacement set and confirm every unused code from the old set is rejected.
12. Advance a test pause to expiration, without changing the enabled flag, and confirm normal polling resumes and an over-limit app is blocked again.

### Boot Persistence

Expected result: goose duty resumes after reboot when enabled.

1. Start monitoring.
2. Reboot device.
3. Unlock device.
4. Confirm Airlock's monitoring notification appears with `The goose is on duty!`.
5. Open a selected over-limit app and confirm blocking still works.

### Monitoring Self-Recovery

Expected result: transient Android failures do not silently switch goose duty
off, and recovery work remains bounded.

1. Start goose duty and confirm the main status says `ON`, the service appears
   in `dumpsys activity services`, and the ongoing notification is visible.
2. Revoke Usage Access while the service runs. Confirm the notification and
   main status say duty needs attention, the saved duty switch remains on, and
   no blocker is attempted.
3. Restore Usage Access. Confirm normal monitoring resumes within 30 seconds,
   or immediately after returning to Airlock, without re-entering the master
   PIN.
4. Repeat by revoking and restoring Display Over Other Apps. Confirm the
   overlay is removed while access is missing and returns after recovery.
5. With duty on, reinstall the same APK using `adb -e install -r`. Confirm
   `MY_PACKAGE_REPLACED` restarts the foreground service and an over-limit app
   is blocked.
6. On Android 13+, run
   `adb -e shell cmd activity stop-app com.dankhole.airlock`. Confirm the
   process and notification stop, then reopen Airlock and confirm requested
   duty starts again. Do not expect automatic restart after this explicit
   Android user-stop path.
7. Force-stop Airlock from system App Info, reboot, and confirm Android does not
   deliver the boot receiver. Open Airlock once and confirm requested duty
   starts again. This is an Android platform limit, not an in-app recovery
   failure.
8. Set Airlock battery use to Restricted. Confirm the main screen shows a clear
   reliability warning and Android may demote the foreground service. Restore
   Optimized or Unrestricted, reopen Airlock, and confirm `dumpsys activity
   services` reports `isForeground=true` again.
9. Repeat steps 1-5 after at least eight hours of ordinary use, then repeat
   after 48-72 hours on a physical device.

Useful emulator commands for Usage Access recovery:

```sh
adb -e shell appops set --uid com.dankhole.airlock GET_USAGE_STATS ignore
adb -e shell appops set --uid com.dankhole.airlock GET_USAGE_STATS allow
```

### Screen And Keyguard Efficiency

Expected result: Airlock performs no UsageStats polling while the display is
off or the keyguard is visible and resumes promptly after unlock.

1. Start duty, turn the screen off for at least five minutes, then inspect
   thread CPU time and `AirlockMonitor` logs.
2. Confirm the foreground and reconciliation workers do not continuously query
   usage while the display is off.
3. Turn the screen on without unlocking and confirm no blocker appears over the
   keyguard.
4. Unlock directly into an over-limit guarded app and confirm the blocker
   appears promptly.
5. Confirm today's saved usage did not increase for time spent behind the
   keyguard.

### Monitoring Performance And Battery

Expected result: foreground detection stays responsive without continuous main-thread work or per-second preference writes.

1. Start goose duty, background Airlock, and use the device normally for at least 30 minutes.
2. Confirm the service remains active and no crashes, ANRs, or repeated overlay rebuild loops appear in Logcat.
3. Confirm UsageStats work runs on an `AirlockForeground-*` executor thread and
   the `AirlockReconciliation` worker rather than the main thread.
4. Confirm the usage preferences file changes on the 30-second batch boundary, not on every one-second foreground poll.
5. Leave a blocked app through recents for more than 15 seconds, return, and confirm the overlay still returns within the normal one-second poll.
6. Run an 8-24 hour physical-device battery comparison with goose duty on and off, recording CPU time, wakeups, and battery drain.
7. Repeat the long battery run on at least one Pixel and one current Samsung device.
8. Confirm foreground-query recovery never has more than two
   `AirlockForeground-*` threads and never queues additional queries. After two
   ten-second timeouts, retries should occur no more than once every 30 seconds.

## Regression Areas

- Permission revocation while service is running.
- App force-stop.
- Android 13+ Active apps Stop.
- App replacement/update while duty is on.
- Device idle and battery saver.
- Restricted battery mode and OEM sleeping-app controls.
- Screen off, keyguard, and first unlock after reboot.
- UsageStats query failure or delayed event delivery.
- Midnight reset.
- Timezone change.
- Multi-window mode.
- No SMS app installed.
- Empty Keyholder number.
- Blocking overlay while keyboard is visible.

## Device Matrix

Minimum useful manual coverage:

- Pixel device on current Android.
- Samsung Galaxy device on current Android.
- One older Android 10-12 device if available.

Samsung and other OEMs often have aggressive background restrictions, so Pixel-only testing is not enough for a blocker app.
