# Privacy Notes

Last updated: August 9, 2026

AirLock Android is designed as a local-first app blocker.

## Data Stored On Device

- Selected app package names.
- Daily usage counters for selected apps.
- Per-app daily limits.
- Accountability phone number.
- Master PIN hash and salt.
- Temporary unlock expiration timestamps.
- Short-lived approval codes and their requested extra-minute duration.
- Salted hashes of one-time emergency codes and the active emergency-pause deadline.
- Local monitoring health and recovery timestamps.

Android cloud backup and device-to-device transfer are disabled for AirLock's
app-private data. Uninstalling or clearing app storage removes this local state.

## Data Shared

The MVP does not send data to an AirLock server and does not include analytics, ads, or trackers.

When the user taps `Text Request Code`, Android opens the user's chosen SMS app with a prefilled message to the configured accountability number. The message includes the numeric request code and requested extra minutes, not the stored approval code. Sending that SMS is controlled by the user and by their SMS app/carrier.

## Sensitive Permissions

- Usage Access: used to detect foreground app usage for selected app limits.
- Display Over Other Apps: used to show the blocking wall.
- Foreground Service: used to keep monitoring active while AirLock is not open.
- Notifications: used for the foreground service notification on Android 13+.

AirLock Android does not request direct SMS read/send permissions in the MVP.
