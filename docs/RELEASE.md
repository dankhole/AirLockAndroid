# Release Guide

Last updated: August 18, 2026

This guide covers the two supported ways to share Airlock builds:

- Direct distribution: a signed APK shared privately with testers after package
  and certificate verification in Android Developer Console.
- Google Play Internal testing: signed Android App Bundle (AAB) installed from
  the Play Store through a tester opt-in link.

Use a real Android device for release validation. Usage Access, overlay access,
the foreground service, and OEM battery policies cannot be meaningfully
validated by an emulator alone.

Copy-ready listing text and policy declarations are maintained in
`docs/PLAY_CONSOLE_SUBMISSION.md`.

Current release stage, unresolved owner decisions, and last-known artifact facts
are maintained in `docs/PROJECT_STATUS.md`. The app is not release-qualified
until the physical-device matrix is recorded as passed.

## Signing Configuration

The Play listing uses application ID `com.dankhole.airlock` and has Play App
Signing enabled. Google Play signs APKs delivered from an uploaded bundle. The
existing local release key signs the upload bundle and direct-distribution APK;
confirm its final **upload key** role and the Google-managed **app-signing key**
fingerprint under **App integrity** after the first bundle is processed.

Assume direct APKs and Play-delivered APKs are signed by different app-signing
keys unless App integrity proves otherwise. Android will not install one as an
update over the other when their signing certificates differ.

Keep these roles separate after Play enrollment:

| Key | Use |
| --- | --- |
| App-signing key | Google-managed key that signs APKs Play delivers. Record its public fingerprint from App integrity. |
| Upload key | Local key that signs AABs uploaded to Play and the separately distributed release APK. Keep it offline and backed up. |

The local release certificate was accepted while registering the Play package.
Do not change application ID `com.dankhole.airlock` or regenerate the local
release/upload key during this process. The Java namespace intentionally remains
`com.dankhole.airlockandroid` and does not affect the Play identity.

The registered release certificate SHA-256 is:

```text
0A:AB:51:C0:4B:D6:A5:13:EC:67:52:59:15:B7:8A:30:AA:78:E6:E9:55:E3:C5:B3:A8:58:FB:99:80:33:9E:7B
```

## Prepare a Release

1. Finish the manual scenarios in `docs/TEST_PLAN.md` on a physical device.
2. Review `git status` and commit the exact changes intended for testers.
3. Update `versionCode` and `versionName` in `app/build.gradle`.
   `versionCode` must increase for every later update. When the same release is
   sent through both channels, use the same version code and content.
4. Run the required checks and create both artifacts:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug \
  :app:assembleRelease :app:bundleRelease
scripts/android-smoke.sh --skip-build
NAVIGATION_ONLY=true scripts/android-smoke.sh --skip-build
```

The smoke commands require a suitable running emulator. If the broad runner
hits the documented Android 17 rotation harness race, do not mark the automated
release gate green; capture the artifacts, triage it, and complete the
corresponding physical-device wizard/rotation flow.

5. Confirm the artifacts exist:

```text
app/build/outputs/apk/release/app-release.apk
app/build/outputs/bundle/release/app-release.aab
```

The locally copied tester artifacts under `releases/` are intentionally ignored
by Git. Publish signed binaries through a controlled distribution channel, not
as source-repository files.

Never share a debug APK with external testers. `keystore.properties`, keystore
files, and passwords must remain outside Git. Keep an encrypted backup of the
local upload/signing key; losing it prevents normal signed updates outside Play.

## Direct Distribution

The Android Developer Console verifies developer and package ownership for
distribution outside Google Play. It does not create a Play Store listing or
host tester downloads.

1. Use the signed `app-release.apk` artifact.
2. Upload it to a private file host or otherwise share it directly with the
   tester group.
3. Send testers the APK link and the setup checklist below.
4. For updates, build with the same app-signing key and a higher version code.

Testers may need to allow installation from the app they used to download the
APK. If Android reports a signature conflict, they have an older build signed
with a different key and must uninstall it before installing this release.
The former `com.dankhole.airlockandroid` build is a separate installed app;
remove it before testing `com.dankhole.airlock` so two monitoring services do
not run at once.
Play Protect or the installer may also warn that a sideloaded app is unknown or
potentially unsafe. That reputation warning can appear on a correctly signed,
developer-verified APK and is not proof that the artifact is unsigned.

### Tester Setup Checklist

1. Install Airlock.
2. Grant Usage Access for Airlock.
3. Grant Display Over Other Apps access.
4. Set a Keyholder number and Master PIN.
5. Select a non-critical app and set a one-minute daily limit.
6. Start Goose Duty, open the selected app, and confirm the blocking overlay
   appears after the limit.
7. Exercise the extra-time request and approval-code flow.

For the first internal test, the Keyholder reply code is produced by adding 5
to each request-code digit modulo 10. Example: request `123456` becomes approval
`678901`. The mapping is transparent test plumbing; it is not the planned real
authorization mechanism.

## Google Play Internal Testing

Use Internal testing when testers should install and update through the normal
Play Store. This track is private, supports up to 100 testers, and is intended
for friends-and-family testing. It does not require production access or the
new-personal-account closed-test requirement.

### One-Time Play Console Setup

1. Wait for Play Console developer verification to complete.
2. Complete any device-verification prompt in the Play Console mobile app.
3. In Play Console, select **Create app** with English (United States), App,
   Free, and the required policy acknowledgements.
4. Create `Airlock` as an app with application ID
   `com.dankhole.airlock`.
5. In **Testing > Internal testing > Testers**, create a tester email list or
   Google Group and add the intended tester Google accounts.
6. Add the countries or regions required by the test track.
7. Confirm Play App Signing is enabled. After the first upload, record the
   app-signing and upload certificate fingerprints shown under App integrity.
8. Complete the App content foreground-service declaration for `specialUse`,
   including the functionality, user impact, and demonstration video in
   `docs/PLAY_CONSOLE_SUBMISSION.md`. Apps targeting Android 14+ must declare
   their foreground-service types; do not assume Internal testing is exempt.

### Ship an Internal Test Build

1. Open **Testing > Internal testing > Releases**.
2. Select **Create new release**.
3. Upload `app-release.aab`.
4. Set the release name to `0.1.0 internal 1` only if `versionCode 1` has never
   been uploaded. Otherwise increment `versionCode`, rebuild, and use a matching
   release name.
5. Use the release notes from `docs/PLAY_CONSOLE_SUBMISSION.md`.
6. Save the release, review it, and select **Start rollout to Internal testing**.
7. Copy the tester opt-in link from the Testers page and send it to the group.

Testers must open the opt-in link while signed into a Google account on the
tester list. They can then install and update Airlock through Play Store.
Internal releases are normally available within minutes, though first-time
processing can take longer.

## Before Wider Release

Internal testing can begin before the full public store listing is complete.
Before a closed, open, or production release, complete the Play Console policy
tasks and keep them consistent with the app:

- Keep the hosted privacy policy and in-app privacy-policy link current.
- Complete Data safety, ads, target audience, content rating, and app-access
  declarations.
- Add a 512 x 512 Play listing icon, a 1024 x 500 feature graphic, and at least
  two truthful phone screenshots.
- Keep the foreground-service declaration for `specialUse` synchronized with
  the shipped service. Explain that Goose Duty is user-started, has a
  persistent notification, can be stopped by the user, and enforces limits for
  only user-selected apps.
- Keep user-facing claims accurate: the overlay is intentional friction, not
  uninstall-proof security.

New personal Play accounts need a closed test with at least 12 opted-in testers
for 14 continuous days before applying for production access. Internal testing
does not have that requirement.

## Official References

- Google Play testing tracks:
  <https://support.google.com/googleplay/android-developer/answer/9845334>
- New personal-account production access:
  <https://support.google.com/googleplay/android-developer/answer/14151465>
- Play App Signing:
  <https://support.google.com/googleplay/android-developer/answer/9842756>
- App content and review declarations:
  <https://support.google.com/googleplay/android-developer/answer/9859455>
- Foreground-service declaration requirements:
  <https://support.google.com/googleplay/android-developer/answer/13392821>
- Google Play target API policy:
  <https://support.google.com/googleplay/android-developer/answer/11926878>
- Android App Bundle upload guidance:
  <https://developer.android.com/studio/publish/upload-bundle>
