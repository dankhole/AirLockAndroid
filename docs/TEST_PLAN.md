# Test Plan

Last updated: August 7, 2026

## Static Checks

Run after Android tooling is available:

```sh
./gradlew :app:assembleDebug
./gradlew :app:lintDebug
adb shell dumpsys package com.dankhole.airlockandroid
```

Confirm the installed package reports `targetSdk=36`.

Checks that were run before Android tooling was available:

- XML well-formedness with `xmllint`.
- Sensitive permission scan for direct SMS and broad package visibility.

## Manual MVP Tests

### Permission Onboarding

Expected result: the app accurately reports whether Usage Access and overlay permission are enabled.

1. Open app fresh.
2. Confirm both permissions show `off`.
3. Grant Usage Access.
4. Return to app and confirm Usage Access shows `on`.
5. Grant overlay permission.
6. Return to app and confirm Overlay shows `on`.

### App Limit Setup

Expected result: app limits persist.

1. Open `Set Goose Limits!`.
2. Select one safe test app.
3. Continue to the limit step.
4. Enter daily limit `1`.
5. Save the limit.
6. Force-close AirLock Goose.
7. Reopen and confirm configured app count is retained.

### Limit Enforcement

Expected result: selected app is blocked after its daily budget is exhausted.

1. Set daily limit to `1`.
2. Set a Keyholder phone number and master PIN.
3. Start goose duty with the master PIN.
4. Open the test app.
5. Wait at least 65 seconds.
6. Confirm the overlay appears.

### Recents And App Switching

Expected result: a blocked app never becomes interactive after a gesture or app switch; its overlay returns within one monitoring poll.

1. Put an over-limit app in recents, open an unblocked app, and use the bottom-edge horizontal gesture to quick-switch into the over-limit app.
2. Confirm the blocking overlay appears in well under one second and the app behind it cannot be tapped.
3. Partially swipe up toward recents, then cancel the gesture back into the blocked app.
4. Confirm the overlay is visible again in well under one second and the app behind it cannot be tapped.
5. Open recents fully and confirm AirLock does not cover the recents screen or prevent selecting another app.
6. Switch to a different app, then return to the blocked app.
7. Confirm the overlay is rebuilt and still contains any unfinished request minutes or approval code.
8. Go Home, confirm the launcher remains usable, relaunch the blocked app, and confirm the overlay returns.
9. Repeat while the number keyboard is open and confirm the overlay still covers the entire app after returning.

### Extra-Time Code

Expected result: valid approval code removes the overlay for the minutes requested when the request code was generated.

1. Enter a Keyholder phone number.
2. Trigger the blocking overlay.
3. Enter requested extra minutes, such as `1`.
4. Tap `Text the Keyholder!`.
5. Confirm the SMS compose screen says `The Goose is asking for X minutes of extra time`, identifies the app, and includes the numeric request code.
6. Return to the overlay and confirm the requested minutes field is still populated and editable.
7. Change the requested minutes and tap `Text the Keyholder!` again.
8. Confirm either valid approval code grants the minutes requested when that specific code was generated.
9. Enter a partial approval code, leave the overlay, return, and confirm the approval code entry is still populated.
10. Enter an incorrect approval code and confirm it is rejected.
11. Enter a valid approval code, tap `Loose the Goose!`, and confirm the goose animation plays before the overlay disappears with `The goose is loose for X minutes!`.
12. Keep the blocked app foregrounded and confirm the overlay returns after the extra time expires.

### Stop Goose Duty

Expected result: blocking stops when goose duty is disabled.

1. Trigger the overlay.
2. Use `Leave App`.
3. Open AirLock Goose.
4. Tap `Stop Goose Duty!` and enter the master PIN.
5. Open the selected app.
6. Confirm no overlay appears.

### Emergency Day Pass

Expected result: the Keyholder can prepare one-time recovery codes, and one valid code pauses every guarded app for exactly 24 hours without permanently disabling goose duty.

1. With goose duty on, open AirLock Goose and tap `Generate 5 New Codes`.
2. Enter an incorrect master PIN and confirm no codes are generated.
3. Enter the correct master PIN, confirm replacement, and verify five distinct 8-digit numeric codes appear.
4. Share or record the codes, tap `Hide Codes`, reopen AirLock Goose, and confirm plaintext codes cannot be displayed again.
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
4. Confirm AirLock Goose's monitoring notification appears with `The goose is on duty!`.
5. Open a selected over-limit app and confirm blocking still works.

### Monitoring Performance And Battery

Expected result: foreground detection stays responsive without continuous main-thread work or per-second preference writes.

1. Start goose duty, background AirLock Goose, and use the device normally for at least 30 minutes.
2. Confirm the service remains active and no crashes, ANRs, or repeated overlay rebuild loops appear in Logcat.
3. Confirm UsageStats work runs on the `AirLockForeground` and `AirLockReconciliation` worker threads rather than the main thread.
4. Confirm the usage preferences file changes on the 30-second batch boundary, not on every one-second foreground poll.
5. Leave a blocked app through recents for more than 15 seconds, return, and confirm the overlay still returns within the normal one-second poll.
6. Run an 8-24 hour physical-device battery comparison with goose duty on and off, recording CPU time, wakeups, and battery drain.
7. Repeat the long battery run on at least one Pixel and one current Samsung device.

## Regression Areas

- Permission revocation while service is running.
- App force-stop.
- Device idle and battery saver.
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
