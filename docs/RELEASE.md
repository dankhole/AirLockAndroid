# Release Guide

Last updated: September 13, 2026

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
| Upload key | Existing key that signs AABs and direct-distribution APKs. Keep the local copy backed up; CI uses a protected secret and a temporary runner file. |

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
RELEASE_VALIDATION=true scripts/android-smoke.sh --skip-build
```

The smoke command requires a suitable running emulator. If the broad runner
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
4. Set a Keyholder number and four-digit Master PIN other than `0000`; give that
   same PIN to the Keyholder.
5. Select a non-critical app and set a one-minute daily limit.
6. Start Goose Duty, open the selected app, and confirm the blocking overlay
   appears after the limit.
7. Exercise the extra-time request and approval-code flow.

For the current signed Internal-testing candidate, use the PIN calculation shown
in the SMS. The build also accepts a hidden fallback: add 3 to each request-code
digit modulo 10, so `4321` becomes `7654`. Do not put that
fallback in release-facing copy.

The shared-PIN calculation is already implemented for the later
platform-agnostic flow: multiply the four-digit request by the four-digit Master
PIN, remove the product's last two digits, and send the last four digits left,
preserving leading zeroes. Request `4321` with PIN `6789` would produce
`29335269`, then approval `3352`. Both `3352` and the temporary `7654` fallback
are accepted for that request in the current internal build. Before retiring the
per-digit fallback, disable its release flag and qualify through
`docs/TEST_PLAN.md`.

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

### GitHub Actions Automation

`.github/workflows/android.yml` prepares the same application for automated
Internal testing. It never targets production or changes the listing, tester
list, privacy declarations, or signing identity. The workflow is ready in source;
credentials and the first successful hosted run are still required.

| Trigger | Behavior |
| --- | --- |
| Pull request | Release-helper tests, visible-text audit, JVM tests, debug lint, debug/release builds, and test-target build/lint; no secrets or upload. |
| Push/merge to `master` | All build checks, then the full emulator smoke suite including both navigation modes. Publishes only when repository variable `PLAY_AUTO_PUBLISH` is `true`. |
| Actions > Android CI > Run workflow | Runs build and emulator checks. The `publish` checkbox defaults to false; selecting it publishes only when the selected branch is `master`. |

Publishing depends on both build and smoke success. Missing secrets, a different
upload certificate, unsigned/tampered bundles, invalid version codes, and failed
copy audits stop publishing. An active master run finishes before another starts;
GitHub may replace pending runs with newer pushes. This ships the newest queued
changes rather than guaranteeing a separate release for every intermediate commit.

The emulator job uses an API 36 Google APIs x86_64 Pixel 2 on Ubuntu, installs
the separate `:smoke-target` debug APK, and runs:

```sh
TARGET_PACKAGE=com.dankhole.airlock.smoketarget TARGET_QUERY=Smoke \
  RELEASE_VALIDATION=true scripts/android-smoke.sh --skip-build
```

The target has no permissions or runtime dependencies, has no release variant,
and is never included in Airlock. This removes dependence on a preinstalled
third-party app. Reports, screenshots, UI dumps, and logs are saved as Actions
artifacts. Failed smoke checks must be investigated; they are not ignored or
automatically retried into a green release signal. Physical Pixel/Samsung and
multi-day checks remain required for release qualification and wider release;
this workflow distributes Internal-testing candidates.

#### One-Time Credentials And Activation

1. Commit and push the workflow and supporting changes when ready. Until then,
   none of this runs on GitHub. The credential-free jobs can run immediately
   afterward, with publishing disabled.
2. Enable the Google Play Android Developer API in a Google Cloud project and
   create a dedicated service account. Invite its email in Play Console's
   **Users and permissions**, scoped to `com.dankhole.airlock`. Grant app-view
   access and **Release apps to testing tracks**. Production-release, financial,
   account-admin, and tester-list management permissions are unnecessary for
   this workflow. The app's first Play upload already exists; account and app
   policy requirements must still be satisfied. See
   [Google's API setup](https://developers.google.com/android-publisher/getting_started)
   and [Play permission definitions](https://support.google.com/googleplay/android-developer/answer/9844686).
3. Create the GitHub environment **play-internal** under repository
   **Settings > Environments**, restricting deployment branches to `master`.
   Leave required reviewers off for automatic Internal-testing releases.
   Environment availability depends on the repository's visibility and GitHub
   plan; see [GitHub's environment setup](https://docs.github.com/en/actions/how-tos/deploy/configure-and-manage-deployments/manage-environments).
4. Add these **environment secrets**, keeping values out of source and logs:

   | Secret | Value |
   | --- | --- |
   | `AIRLOCK_KEYSTORE_BASE64` | Base64 contents of the existing upload keystore; do not generate a new key. Line wrapping is accepted. |
   | `AIRLOCK_KEYSTORE_PASSWORD` | Existing keystore password. |
   | `AIRLOCK_KEY_ALIAS` | Existing upload-key alias. |
   | `AIRLOCK_KEY_PASSWORD` | Existing key password. |
   | `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` | Complete service-account JSON key from Google Cloud. |

   The public upload fingerprint is pinned in `scripts/ci-release.py` to the
   certificate documented above. Signing material is decoded with private file
   permissions under `RUNNER_TEMP`, checked before building, then deleted even
   after failure. The signing job disables Gradle caching. No secret file is
   uploaded as an artifact. Restrict changes to the workflow and scripts to
   trusted contributors, since they execute in the signing job.
5. Confirm the first computed CI version code exceeds every version code already
   uploaded to Play. The default is `10000 + GITHUB_RUN_NUMBER`. If necessary,
   set repository variable `PLAY_VERSION_CODE_BASE` to a higher integer before
   the run. This must be a repository variable, not an environment variable,
   because the credential-free build allocates it.
6. Run **Android CI** manually on `master` with `publish` off, confirm both jobs
   pass on GitHub, then start a new manual run with `publish` on. Verify the
   resulting Internal release and install/update through a tester's Play Store.
7. Set repository variable **PLAY_AUTO_PUBLISH=true** under **Settings > Secrets
   and variables > Actions > Variables**. Every subsequent `master` push is now
   eligible to publish after the checks. Set it to `false` to stop future
   automatic uploads; this does not cancel an already-started publish job.

All Actions are pinned to commit SHAs; Dependabot checks them monthly. The
publishing adapter is [upload-google-play](https://github.com/r0adkll/upload-google-play),
configured with `tracks: internal` and `status: completed`. If Google requires
manual Console review or another account action, the failure remains visible;
the workflow does not bypass it or silently downgrade to an unpublished draft.

#### Versions, Release Notes, And Retries

CI supplies `-PairlockVersionCode` through Gradle's environment-property support;
the checked-in local default stays at 8. `versionName` remains the human-managed
value in `app/build.gradle`, currently `0.1.7`. Update it for a named release.
Play release names use `Airlock <version code>` so CI uploads are distinguishable.
After CI starts publishing, any later manual upload must also exceed Play's
highest code; the old local default cannot be uploaded as an update.

Update `play-store/whatsnew/whatsnew-en-US` with each user-visible change. The
audit checks visible string/plural/array values, listing text, and release notes
for internal-only markers and the notes' 500-character limit. It does not OCR
screenshots or inspect videos; continue the visual release review.

GitHub reruns keep the same run number and therefore the same version code.
Retrying a failed check before upload is fine. If a bundle was already uploaded
or the commit outcome is uncertain, inspect Play first and start a **new manual
workflow run** instead of uploading that version again. Do not rerun an old
release to roll back; ship a new version from `master`. If the workflow is
recreated or its version-code base changes, confirm the next code is still
higher than Play's highest code. The upper bound is 2,100,000,000.

The run summary records the source commit, version code, and bundle checksum;
verified signed builds retain the AAB for 30 days before the Play upload begins.
Nothing is copied into
`releases/` or published as a GitHub Release by this workflow.

Local tooling checks require only Python 3's standard library:

```sh
python3 -m unittest discover -s scripts/tests -v
python3 scripts/ci-release.py audit
GITHUB_RUN_NUMBER=1 python3 scripts/ci-release.py version
```

Local signing still reads ignored `keystore.properties`. CI instead supplies
all four `AIRLOCK_KEYSTORE_FILE`, `AIRLOCK_KEYSTORE_PASSWORD`, `AIRLOCK_KEY_ALIAS`,
and `AIRLOCK_KEY_PASSWORD` environment variables. Partial environment
configuration fails instead of falling back to another key. The publishing
build also requires `-PrequireReleaseSigning=true`.

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
