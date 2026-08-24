# Play Console Submission Draft

Last updated: August 23, 2026

This document contains copy-ready values for the first Airlock internal
test. Re-check them against the shipped build whenever behavior or data handling
changes.

## Current Readiness

| Item | Status |
| --- | --- |
| Play application ID `com.dankhole.airlock` | App created and release certificate accepted in Play Console |
| Java namespace `com.dankhole.airlockandroid` | Intentionally unchanged; it is not the installed package ID |
| Release certificate | `0A:AB:51:C0:4B:D6:A5:13:EC:67:52:59:15:B7:8A:30:AA:78:E6:E9:55:E3:C5:B3:A8:58:FB:99:80:33:9E:7B` |
| Target SDK | 36 |
| Version | Candidate `versionCode 5`, `versionName 0.1.4`; version 1 is on Internal testing |
| Signed AAB | Version 1 uploaded; version 5 is the current verified local candidate |
| Privacy policy | GitHub Pages source and in-app URL prepared; Pages must be enabled and the URL checked before rollout |
| Play Console developer verification | Sufficient to create the app and Internal testing release; monitor any remaining dashboard task |
| Play App Signing strategy | Enabled; Google Play signs delivered releases |
| Tester list | Owner must provide Google-account email addresses |
| Support email | Owner must provide a monitored public address |
| Physical-device release matrix | Not yet recorded as passed |
| Foreground-service declaration/video | Must be completed for the target-36 app before first Play rollout |
| Store graphics | Upload-ready icon, feature graphic, and four phone screenshots are under `play-store/` |

The privacy policy URL is:

```text
https://dankhole.github.io/AirLockAndroid/privacy/
```

GitHub Pages must deploy the repository's `docs` directory before this URL is
entered in Play Console. Keep the in-app URL and Play Console URL identical.

Uploaded version-1 AAB SHA-256:

```text
9fbd3e66295acb6f716d5b9b74903e72cf7dd32ef2b243c7706c1dbce38421d4
```

This checksum describes `releases/Airlock-0.1.0-internal-1.aab`, which was
prepared from commit `2afe9c3` and uploaded as version 1.

Current candidate version-5 artifact:

| Artifact | SHA-256 |
| --- | --- |
| `releases/Airlock-0.1.4-internal-5.aab` | `f1f58de5d46d6c4521233e6710812cd12689925f6cd967585fe07c8dcd2a56d7` |

The copied bundle byte-matches the signed Gradle output. Package inspection
confirmed `com.dankhole.airlock`, version code 5, version name `0.1.4`, min SDK
26, and target SDK 36. The companion release APK matched the registered upload
certificate.

## Create App

- App name: `Airlock`
- Default language: `English (United States) - en-US`
- App or game: `App`
- Free or paid: `Free`
- Package name: `com.dankhole.airlock`
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
Airlock
```

### Short Description

```text
A silly accountability goose that guards distracting apps after daily limits.
```

### Full Description

```text
Airlock adds intentional friction between you and distracting apps.

Choose the apps you want to guard and give each one a daily time limit. Airlock counts usage locally on your device. When a selected app reaches its limit, the goose appears with a focused blocking screen.

Need more time? Airlock opens your chosen SMS app with a request for your Keyholder. The reply code is tied to the amount of extra time requested. Airlock does not read or send SMS messages itself.

Goose Duty includes clear permission checks, per-app usage totals, a persistent silent status notification, one-time emergency day passes, and recovery status when Android interrupts monitoring.

Your selected apps, usage totals, limits, phone number, PIN hashes, and access-code state remain on your device. Airlock includes no ads, analytics, trackers, or online account.

Airlock is an accountability aid, not tamper-proof parental-control or device-security software. A device owner can revoke Android access, force-stop the app, clear its data, or uninstall it.
```

### Internal Release Notes

```text
Refines Keyholder approval guidance and introduces a clearer blocker home with separate request, approval, and emergency flows. Active requests and additional requests are explicit.
```

## Internal Test Track

1. Create a Google-account email list or Google Group under **Test and release >
   Testing > Internal testing > Testers**.
2. Create a release and upload `app-release.aab`.
3. Confirm Play App Signing remains enabled and do not regenerate the local
   upload key.
4. Complete the `specialUse` foreground-service declaration and demonstration
   video below.
5. Upload the verified version-5 artifact using release name
   `0.1.4 internal 5`. Earlier local candidates are obsolete and must not be
   uploaded.
6. Review any Console warnings, then start rollout to Internal testing.
7. Copy the opt-in link and open it while signed into an allow-listed tester
   account.
8. Install from Google Play and run the physical-device scenarios in
   `docs/TEST_PLAN.md`.

Give internal testers the current reply rule separately from the request SMS:
add `5656` to the four-digit request as a number and keep only the last four
digits (`4321` becomes `9977`). The signed build's SMS does not explain how the
reply is derived or expose temporary test configuration. Explain that the
calculation provides deliberate friction rather than secure authentication. The shared-PIN
multiplication path is implemented but remains disabled until a later explicit
release change.

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
Airlock has no online account or login. The reviewer creates a local Master PIN and enters a test Keyholder phone number during setup. Usage Access, Display Over Other Apps, and notification access are requested through Android settings because they are core to app-limit monitoring and blocking.
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
- No SDK or Airlock server receives data.
- The SMS-app handoff occurs only after the user taps the text action and is a
  user-initiated transfer the user expects.

Confirm the final answers in Console against the current Data safety definitions
instead of copying this draft blindly. Any future backend, crash reporter,
analytics SDK, advertising SDK, cloud backup, or companion app changes the
answer.

The current local storage detail must also remain accurate: the plaintext
Master PIN is not stored, but its salted hash and a PIN-derived table of
four-digit approval results are app-private. Pending approval values, requested
minutes, and expiry remain temporarily. Emergency codes remain salted hashes.
The approval calculation is a deterrent, not secure authentication.

## Foreground Service Declaration

- Foreground service type: `specialUse`
- Suggested use case: `User-initiated app screen-time monitoring and limit enforcement`

Functionality description:

```text
When the user explicitly turns on Goose Duty, Airlock runs a foreground service with an ongoing silent notification. It checks which app is in the foreground, counts usage only for apps selected by the user, and shows the blocking overlay when a selected app reaches its configured daily limit. The user can stop Goose Duty from the app with the local Master PIN.
```

Impact if startup is deferred:

```text
Foreground app changes and selected-app usage may not be detected promptly, allowing a guarded app to remain available beyond its configured limit. The app reports that monitoring is recovering instead of claiming enforcement is active.
```

Impact if interrupted:

```text
Usage reconciliation and blocking are delayed until Android restarts the service or the user opens Airlock. The service persists local state, restarts after supported lifecycle events, and keeps recovery status visible in the ongoing notification.
```

Video checklist:

1. Open the release build and show the first-run access explanation.
2. Grant Usage Access, Display Over Other Apps, and notifications.
3. Set a test Keyholder number and Master PIN.
4. Select a harmless test app and set a one-minute limit.
5. Turn on Goose Duty and show the silent ongoing notification.
6. Open the selected app after its limit and show the Airlock blocking overlay.
7. Return to Airlock and stop Goose Duty with the Master PIN.

Upload the video as an accessible unlisted YouTube video or other link accepted
by Play Console. It must show the actual Android release behavior.

## Preview Assets

Upload-ready files and copy are in [`../play-store/`](../play-store/). The
`Airlock-Play-Listing.zip` bundle is only for convenient transfer; upload the
individual PNG files to their matching Play Console fields.

- 512 x 512 PNG Play Store icon, at most 1 MB
- 1024 x 500 feature graphic
- Four 1080 x 1920 truthful phone screenshots without test-only UI
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
