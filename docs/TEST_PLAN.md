# Test Plan

Last updated: July 20, 2026

## Static Checks

Run after Android tooling is available:

```sh
./gradlew :app:assembleDebug
./gradlew :app:lintDebug
```

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

1. Open `Set App Limits`.
2. Select one safe test app.
3. Continue to the limit step.
4. Enter daily limit `1`.
5. Save the limit.
6. Force-close AirLock Android.
7. Reopen and confirm configured app count is retained.

### Limit Enforcement

Expected result: selected app is blocked after its daily budget is exhausted.

1. Set daily limit to `1`.
2. Set an accountability phone number and master PIN.
3. Start monitoring with the master PIN.
4. Open the test app.
5. Wait at least 65 seconds.
6. Confirm the overlay appears.

### Extra-Time Code

Expected result: valid approval code removes the overlay for the minutes requested when the request code was generated.

1. Enter an accountability phone number.
2. Trigger the blocking overlay.
3. Enter requested extra minutes, such as `1`.
4. Tap `Text Request Code`.
5. Note the generated request code and requested minutes from the SMS compose screen.
6. Convert the request code by adding 5 to each digit with wraparound.
7. Return to the overlay.
8. Enter an incorrect approval code and confirm it is rejected.
9. Enter the valid approval code and confirm the overlay disappears.
10. Keep the blocked app foregrounded and confirm the overlay returns after the extra minute expires.

### Stop Monitoring

Expected result: blocking stops when monitoring is disabled.

1. Trigger the overlay.
2. Use `Leave App`.
3. Open AirLock Android.
4. Tap `Stop Monitoring` and enter the master PIN.
5. Open the selected app.
6. Confirm no overlay appears.

### Boot Persistence

Expected result: monitoring resumes after reboot when enabled.

1. Start monitoring.
2. Reboot device.
3. Unlock device.
4. Confirm AirLock's monitoring notification appears.
5. Open a selected over-limit app and confirm blocking still works.

## Regression Areas

- Permission revocation while service is running.
- App force-stop.
- Device idle and battery saver.
- Midnight reset.
- Timezone change.
- Multi-window mode.
- No SMS app installed.
- Empty accountability number.
- Blocking overlay while keyboard is visible.

## Device Matrix

Minimum useful manual coverage:

- Pixel device on current Android.
- Samsung Galaxy device on current Android.
- One older Android 10-12 device if available.

Samsung and other OEMs often have aggressive background restrictions, so Pixel-only testing is not enough for a blocker app.
