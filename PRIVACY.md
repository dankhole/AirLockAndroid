# Airlock Privacy Policy

Effective date: October 31, 2026

Airlock is a local-first app blocker. This policy explains what the app
accesses, what it stores, and when information leaves the app.

## Information Accessed

- Launchable app names and package identifiers, used to show the app picker.
- Android foreground-app and usage history, used to count time for apps the
  user chooses to guard.
- A Keyholder phone number entered directly by the user. Airlock does not read
  the device's contacts.
- With separate consent and optional Accessibility access, supported selected
  apps' interface metadata (package names, view identifiers, visibility, selection,
  bounds, and limited Facebook accessibility descriptions), used to recognize
  selected short-video or Stories screens and automatically navigate Back.
  Accessibility descriptions may contain displayed post or message content;
  Airlock compares bounded labels transiently, not their meaning. It does not
  request text-change events, read text-field values, capture screenshots, or
  save screen content.

Airlock processes this information on the device. It does not upload app lists,
usage history, or settings to an Airlock server.

## Data Stored On Device

- Selected app package names.
- Daily usage counters for selected apps.
- Per-app daily limits.
- Enabled content-filter choices and the accepted disclosure version. Interface
  metadata used for filtering is processed transiently and is not stored or sent.
- Keyholder phone number.
- Master PIN hash, salt, and a PIN-derived approval lookup table. The plaintext
  PIN is not stored.
- Temporary unlock expiration timestamps.
- Short-lived approval codes and their requested extra-minute duration.
- Salted hashes of one-time emergency codes and the active emergency-pause
  deadline.
- Local monitoring health and recovery timestamps.

Android cloud backup and device-to-device transfer are disabled for Airlock's
app-private data. Uninstalling or clearing app storage removes this local state.

## Information Shared

Airlock does not include analytics, advertising, tracking SDKs, or an
Airlock backend.

When the user taps `Text the Keyholder`, Android opens the user's chosen SMS app
with a prefilled message to the configured Keyholder number. The message
includes the numeric request code and requested extra minutes, not the stored
approval code. This transfer is initiated by the user. Sending the message is
controlled by the user and by the selected SMS app and carrier.

## Sensitive Permissions

- Usage Access: used to detect foreground app usage for selected app limits.
- Display Over Other Apps: used to show the blocking wall.
- Foreground Service: used to keep monitoring active while Airlock is not open.
- Notifications: used to keep foreground-service and recovery status visible;
  Android 13+ requires a runtime permission.
- Optional Accessibility Service: used only for consented content filters in
  selected supported apps, not for ordinary app-time monitoring. Airlock inspects
  the active interface to recognize selected feeds and performs Android Back to
  leave them. Filtering follows Goose Duty and the emergency day pass. You can
  withdraw consent in the PIN-protected content settings or disable the service
  at any time in Android Accessibility settings; Airlock never obstructs that.

Airlock does not request direct SMS read/send permissions.

## Retention And Deletion

Local information remains until it expires, is replaced by the user, or the
user clears Airlock's storage. Uninstalling Airlock removes its local
information. Airlock has no user account or server-side profile requiring a
separate deletion request.

## Security

The plaintext Master PIN is not stored. Airlock stores its salted hash and a
locally derived table of the approval result for each possible four-digit
request so replies can be checked without an app, website, or server. Emergency
codes are stored as salted hashes.
Ordinary approval codes are short-lived values stored in app-private storage
with their requested minutes and expiration so Airlock can validate more than
one pending request; they expire after 1 hour or are removed when used.
The simple approval calculation is intended as a behavioral deterrent, not
secure authentication. No local-only design can prevent a device owner from
clearing app storage, revoking Android access, or uninstalling the app.

## Children

Airlock is not directed to children and is intended for adults managing
their own device use.

## Changes And Contact

Policy changes will be published at this URL with an updated effective date.
Privacy questions should be sent to the developer contact address shown on the
Airlock Google Play listing. Technical issues can also be reported at
<https://github.com/dankhole/AirLockAndroid/issues>; do not include private or
sensitive information in a public issue.
