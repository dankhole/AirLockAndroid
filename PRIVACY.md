# AirLock Goose Privacy Policy

Effective date: August 11, 2026

AirLock Goose is a local-first app blocker. This policy explains what the app
accesses, what it stores, and when information leaves the app.

## Information Accessed

- Launchable app names and package identifiers, used to show the app picker.
- Android foreground-app and usage history, used to count time for apps the
  user chooses to guard.
- A Keyholder phone number entered directly by the user. AirLock does not read
  the device's contacts.

AirLock processes this information on the device. It does not upload app lists,
usage history, or settings to an AirLock server.

## Data Stored On Device

- Selected app package names.
- Daily usage counters for selected apps.
- Per-app daily limits.
- Keyholder phone number.
- Master PIN hash and salt.
- Temporary unlock expiration timestamps.
- Short-lived approval codes and their requested extra-minute duration.
- Salted hashes of one-time emergency codes and the active emergency-pause deadline.
- Local monitoring health and recovery timestamps.

Android cloud backup and device-to-device transfer are disabled for AirLock's
app-private data. Uninstalling or clearing app storage removes this local state.

## Information Shared

AirLock Goose does not include analytics, advertising, tracking SDKs, or an
AirLock backend.

When the user taps `Text the Keyholder`, Android opens the user's chosen SMS app
with a prefilled message to the configured Keyholder number. The message
includes the numeric request code and requested extra minutes, not the stored
approval code. This transfer is initiated by the user. Sending the message is
controlled by the user and by the selected SMS app and carrier.

## Sensitive Permissions

- Usage Access: used to detect foreground app usage for selected app limits.
- Display Over Other Apps: used to show the blocking wall.
- Foreground Service: used to keep monitoring active while AirLock is not open.
- Notifications: used to keep foreground-service and recovery status visible;
  Android 13+ requires a runtime permission.

AirLock Goose does not request direct SMS read/send permissions in the MVP.

## Retention And Deletion

Local information remains until it expires, is replaced by the user, or the
user clears AirLock's storage. Uninstalling AirLock Goose removes its local
information. AirLock has no user account or server-side profile requiring a
separate deletion request.

## Security

Master PINs and emergency codes are stored as salted hashes rather than
plaintext. Ordinary approval codes are short-lived test-release values stored
in app-private storage with their requested minutes and expiration so AirLock
can validate more than one pending request; they expire after 10 minutes or are
removed when used. No local-only design can prevent a device owner from
clearing app storage, revoking Android access, or uninstalling the app.

## Children

AirLock Goose is not directed to children and is intended for adults managing
their own device use.

## Changes And Contact

Policy changes will be published at this URL with an updated effective date.
Privacy questions should be sent to the developer contact address shown on the
AirLock Goose Google Play listing. Technical issues can also be reported at
<https://github.com/dankhole/AirLockAndroid/issues>; do not include private or
sensitive information in a public issue.
