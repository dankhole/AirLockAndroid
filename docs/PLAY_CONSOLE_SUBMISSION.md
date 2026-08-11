# Play Console Submission Draft

Last updated: August 11, 2026

This document contains copy-ready values for the first AirLock Goose internal
test. Re-check them against the shipped build whenever behavior or data handling
changes.

## Current Readiness

| Item | Status |
| --- | --- |
| Package name `com.dankhole.airlockandroid` | Registered and verified in Android Developer Console |
| Release certificate | `0A:AB:51:C0:4B:D6:A5:13:EC:67:52:59:15:B7:8A:30:AA:78:E6:E9:55:E3:C5:B3:A8:58:FB:99:80:33:9E:7B` |
| Target SDK | 36 |
| Version | `versionCode 1`, `versionName 0.1.0` |
| Signed AAB | Existing local copy is stale after `16bd88c`; rebuild before upload |
| Privacy policy | Published at the current raw GitHub URL and linked in-app; re-check after policy changes |
| Play Console developer verification | Owner must confirm completion |
| Play App Signing strategy | Owner must choose before first upload; see `docs/RELEASE.md` |
| Tester list | Owner must provide Google-account email addresses |
| Support email | Owner must provide a monitored public address |
| Physical-device release matrix | Not yet recorded as passed |
| Foreground-service declaration/video | Must be completed for the target-36 app before first Play rollout |
| Store graphics | Required before a public listing; not required to start an internal-only test |

The privacy policy URL is:

```text
https://raw.githubusercontent.com/dankhole/AirLockAndroid/master/PRIVACY.md
```

Replace this with a branded HTTPS page before public launch if one becomes
available. Keep the in-app URL and Play Console URL identical.

Historical AAB SHA-256 (do not upload this artifact):

```text
3779d53b53eb85f616f89dab939562d030b8daf2a6d16939aa91944c89053a07
```

This checksum describes the pre-navigation-fix local bundle. Rebuild from the
exact release candidate, verify its certificate, replace this checksum, and
only then upload the new bundle.

## Create App

- App name: `AirLock Goose`
- Default language: `English (United States) - en-US`
- App or game: `App`
- Free or paid: `Free`
- Package name: established by the first uploaded bundle as
  `com.dankhole.airlockandroid`
- Category: `Productivity`
- Tags: use relevant time-management or productivity tags offered by Console
- Countries for the first test: United States, unless a tester needs another
  country

The owner must supply the public support email and decide whether to show a
website or phone number. Use a dedicated support address rather than a personal
primary inbox.

## Store Listing Copy

### App Name

```text
AirLock Goose
```

### Short Description

```text
A silly accountability goose that guards distracting apps after daily limits.
```

### Full Description

```text
AirLock Goose adds intentional friction between you and distracting apps.

Choose the apps you want to guard and give each one a daily time limit. AirLock Goose counts usage locally on your device. When a selected app reaches its limit, the goose appears with a focused blocking screen.

Need more time? AirLock opens your chosen SMS app with a request for your Keyholder. The reply code is tied to the amount of extra time requested. AirLock does not read or send SMS messages itself.

Goose Duty includes clear permission checks, per-app usage totals, a persistent silent status notification, one-time emergency day passes, and recovery status when Android interrupts monitoring.

Your selected apps, usage totals, limits, phone number, PIN hashes, and access-code state remain on your device. AirLock includes no ads, analytics, trackers, or online account.

AirLock Goose is an accountability aid, not tamper-proof parental-control or device-security software. A device owner can revoke Android access, force-stop the app, clear its data, or uninstall it.
```

### Internal Release Notes

```text
First internal test build! Please test first-run access setup, per-app limits, Goose Duty, today's usage totals, the blocking screen, extra-time requests and reply codes, emergency codes, reboot recovery, and rapid gesture switching between apps.
```

## Internal Test Track

1. Create a Google-account email list or Google Group under **Test and release >
   Testing > Internal testing > Testers**.
2. Create a release and upload `app-release.aab`.
3. Configure Play App Signing before completing the first release. See the key
   decision in `docs/RELEASE.md`; do not regenerate the local release key.
4. Complete the `specialUse` foreground-service declaration and demonstration
   video below.
5. Use release name `0.1.0 internal 1` only if version code 1 has never been
   uploaded; otherwise increment and rebuild.
6. Review any Console warnings, then start rollout to Internal testing.
7. Copy the opt-in link and open it while signed into an allow-listed tester
   account.
8. Install from Google Play and run the physical-device scenarios in
   `docs/TEST_PLAN.md`.

Give internal testers the temporary reply-code rule: add 5 to each request-code
digit modulo 10. Do not present this rule as secure authentication; the internal
track is validating the Android product flow before real Keyholder auth is
built.

Internal testing supports up to 100 testers and has no 12-tester/14-day access
requirement. For a new personal account, moving toward production later requires
a closed test with at least 12 continuously opted-in testers for 14 days.

## App Content Answers

These answers describe the current source and must be revised if analytics, a
backend, ads, account creation, direct SMS permissions, or another data flow is
added.

### Ads

`No, the app does not contain ads.`

### App Access

Select that all functionality is available without login, membership, or
pre-existing credentials. Suggested reviewer note:

```text
AirLock Goose has no online account or login. The reviewer creates a local Master PIN and enters a test Keyholder phone number during setup. Usage Access, Display Over Other Apps, and notification access are requested through Android settings because they are core to app-limit monitoring and blocking.
```

### Target Audience

Select adults, `18 and over`. The app is not designed or marketed for children.

### Content Rating

Answer for a productivity utility with no user-generated content, social
features, gambling, sexual content, violence, drugs, or unrestricted web access.
The prefilled SMS request is a user-initiated communication to a phone number
the user configures; the app does not provide public messaging.

### Other Declarations

- News app: `No`
- Government app: `No`
- Financial features: `No`
- Health features: `No`
- Account creation: `No`
- Data deletion URL: not applicable because there is no account or server-side
  profile; clearing app storage or uninstalling deletes local state

## Data Safety Draft

An app active exclusively on the internal testing track is currently exempt
from completing the Data safety form. Complete it before closed, open, or
production testing.

For the current build, the likely top-level answer is that the app does not
collect or share user data as those terms are defined by the Data safety form:

- App inventory, usage events, settings, phone number, and code state are
  processed and stored on device.
- No SDK or AirLock server receives data.
- The SMS-app handoff occurs only after the user taps the text action and is a
  user-initiated transfer the user expects.

Confirm the final answers in Console against the current Data safety definitions
instead of copying this draft blindly. Any future backend, crash reporter,
analytics SDK, advertising SDK, cloud backup, or companion app changes the
answer.

The current local security detail must also remain accurate: master PINs and
emergency codes are salted hashes, while pending internal-test approval values,
their requested minutes, and expiry remain temporarily in app-private storage.

## Foreground Service Declaration

- Foreground service type: `specialUse`
- Suggested use case: `User-initiated app screen-time monitoring and limit enforcement`

Functionality description:

```text
When the user explicitly turns on Goose Duty, AirLock Goose runs a foreground service with an ongoing silent notification. It checks which app is in the foreground, counts usage only for apps selected by the user, and shows the blocking overlay when a selected app reaches its configured daily limit. The user can stop Goose Duty from the app with the local Master PIN.
```

Impact if startup is deferred:

```text
Foreground app changes and selected-app usage may not be detected promptly, allowing a guarded app to remain available beyond its configured limit. The app reports that monitoring is recovering instead of claiming enforcement is active.
```

Impact if interrupted:

```text
Usage reconciliation and blocking are delayed until Android restarts the service or the user opens AirLock Goose. The service persists local state, restarts after supported lifecycle events, and keeps recovery status visible in the ongoing notification.
```

Video checklist:

1. Open the release build and show the first-run access explanation.
2. Grant Usage Access, Display Over Other Apps, and notifications.
3. Set a test Keyholder number and Master PIN.
4. Select a harmless test app and set a one-minute limit.
5. Turn on Goose Duty and show the silent ongoing notification.
6. Open the selected app after its limit and show the AirLock blocking overlay.
7. Return to AirLock and stop Goose Duty with the Master PIN.

Upload the video as an accessible unlisted YouTube video or other link accepted
by Play Console. It must show the actual Android release behavior.

## Preview Assets Before Wider Release

- 512 x 512 PNG Play Store icon, at most 1 MB
- 1024 x 500 feature graphic
- At least two truthful phone screenshots without debug fixtures or test-only UI
- Optional short demonstration video

Keep the dark green Goose visual system consistent, center important feature
graphic content for cropping, and avoid claims that the app is uninstall-proof
or impossible to bypass.

## Official References

- Internal and closed testing:
  <https://support.google.com/googleplay/android-developer/answer/9845334>
- New personal-account testing requirements:
  <https://support.google.com/googleplay/android-developer/answer/14151465>
- Android App Bundle upload:
  <https://developer.android.com/studio/publish/upload-bundle>
- App content declarations:
  <https://support.google.com/googleplay/android-developer/answer/9859455>
- Data safety:
  <https://support.google.com/googleplay/android-developer/answer/10787469>
- Foreground-service declaration:
  <https://support.google.com/googleplay/android-developer/answer/13392821>
- Store preview assets:
  <https://support.google.com/googleplay/android-developer/answer/9866151>
