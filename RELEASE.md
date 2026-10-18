# ONYX v1.3.0 "Forge": release-signed, reproducible Android builds

[Website](https://0nyx.up.railway.app/) · [Source](https://github.com/SirRogersJackBlood/ONYX) · [Release downloads](https://github.com/SirRogersJackBlood/ONYX/releases/tag/v1.3.0) · [F-Droid submission !53119](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/53119)

The corrected v1.3.0 APKs are signed with the ONYX release key. On October 18, 2026, F-Droid's merge-request pipeline passed for all three architectures, including source checks, reproducible builds, signature copying, and APK scanning. The pipeline result is evidence of those checks; the F-Droid submission's current merge status is shown in the linked MR.

## Before installing

The application ID is now `io.github.sirrogersjackblood.onyx`, replacing `io.github.proteu5.onyx`. Android treats this as a separate app. Install the new app, pair your contacts again, and keep the old installation until you no longer need its local messages. This release does not migrate the old installation's contacts or messages. Later updates with the same application ID and signing key can update this installation in place.

Requires Android 11 or newer (API 30). The messaging wire protocol is unchanged.

## What changed in v1.3.0

- Dedicated release signing replaces the development key for the current APKs.
- Three architecture-specific downloads use version codes 81, 82, and 83. Native-library stripping, R8/resource shrinking, and exclusion of libsignal test and desktop libraries keep downloads around 36–44 MiB.
- Reproducible-build settings disable VCS metadata and ART baseline profiles, pin NDK `30.0.16248370`, and support one-ABI builds through `-PonyxAbi=`. Dependencies resolve through Google Maven and Maven Central.
- The F-Droid submission includes the build recipe, release signing-certificate fingerprint, store screenshots, and changelogs.
- The existing screen-lock reliability fixes and clean Tor process restarts from v1.2.4 remain included.
- Code remains licensed under AGPL-3.0-only, with artwork under Apache-2.0. ONYX and the raven mark remain trademarks of SirRogersJackBlood; see `LICENSE`, `LICENSE-ASSETS`, `NOTICE`, and `TRADEMARKS.md`.

## Android permissions: purpose, timing, and user control

These are the seven permissions declared by the v1.3.0 app. The build/signing follow-up did not add new manifest permissions. F-Droid reports each permission once for each architecture: metadata lines 18, 32, and 46 identify the three build entries, not three different features.

| Permission | What ONYX uses it for | When it is used and what you control |
|---|---|---|
| `android.permission.CAMERA` | The Scan screen uses Camera2 and ZXing to read another person's ONYX pairing QR code. Frames are decoded in memory; this scanner does not save or upload camera images. | Android requests camera access when the Scan screen resumes if permission is missing. The camera is closed when that screen pauses or pairing starts. If access is denied, the screen provides a pasted `onyx1:` invite as an alternative. Camera hardware is optional in the manifest. |
| `android.permission.INTERNET` | Network sockets let the embedded Tor client bootstrap, host the phone's onion service, and exchange end-to-end encrypted messages with paired contacts. The separately enabled Snowflake proxy also needs network access. | Android grants this manifest permission without a runtime dialog. Messaging needs connectivity; blocking network access or choosing **Stop ONYX (go offline)** prevents delivery while offline. No phone number, account, or central ONYX messaging server is introduced by this permission. |
| `android.permission.ACCESS_NETWORK_STATE` | The Snowflake controller inspects the active connection's Wi-Fi and unmetered capabilities before deciding whether it may relay traffic. | This is a normal manifest permission, without a runtime dialog. Snowflake defaults to off. Its controller checks user enablement, unmetered Wi-Fi, charging, and the configured daily usage cap before starting and periodically while running. This code does not request location or read Wi-Fi network names. |
| `android.permission.FOREGROUND_SERVICE` | A foreground service keeps Tor and the local message listener available after you leave the app's screens, so paired contacts can deliver messages directly. It also manages Snowflake when enabled. | Opening the main screen starts the service with a connection-status notification. Android may restart an already-started sticky service. ONYX declares no boot-start receiver. **Settings → Stop ONYX (go offline)** shuts down messaging, Tor, and Snowflake; foreground-service permission is not a camera, microphone, contacts, or location grant. |
| `android.permission.FOREGROUND_SERVICE_SPECIAL_USE` | Android 14+ requires the permission matching the declared `specialUse` foreground-service type. The manifest describes its purpose as hosting a Tor onion service for direct encrypted message delivery. | Used by the same service, not a second always-on feature. It is a normal manifest permission and has no separate runtime prompt. The same offline/stop control applies. |
| `android.permission.POST_NOTIFICATIONS` | Shows Tor connection status and content-free new-message alerts. New-message notifications say **New message**, with no sender or message body, and are marked `VISIBILITY_SECRET` to keep them off the lock screen. | On Android 13+, the main screen requests notification permission when it is not granted. You can deny or disable notifications. Denial suppresses notification-drawer alerts; it does not itself grant or revoke Tor network access. Android may still show the running foreground service in its active-app/Task Manager UI. |
| `android.permission.USE_BIOMETRIC` | The optional app lock uses Android's `BiometricPrompt` with strong biometrics or the device's PIN, pattern, or password. ONYX receives the authentication result; this code does not collect fingerprint or face images. | App lock defaults to off and is controlled in **Settings → Security → App lock (biometric / device PIN)**. Enabling it uses Android's authentication UI when an unlock is needed. This is a normal manifest permission, distinct from the authentication prompt. If Android reports no usable authenticator, the current implementation permits access rather than enforcing an unavailable lock. |

Android's permission classifications and operating-system behavior are documented in the [permission reference](https://developer.android.com/reference/android/Manifest.permission), [notification permission guide](https://developer.android.com/develop/ui/compose/notifications/notification-permission), and [foreground-service types guide](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use).

### Snowflake is a separate, optional network feature

**Run Snowflake proxy** is off by default and requires an explicit confirmation in Settings. It helps other people reach Tor and is not a Tor exit node. Its network connections include the Snowflake broker, relay, NAT probe, and STUN/WebRTC infrastructure. The existing confirmation explains that the broker and people being helped can see the phone's public IP. These proxy connections are separate from ONYX's encrypted conversations. Disable the Snowflake switch to stop participating; messaging does not require enabling it.

### Permissions ONYX does not request

The current manifest does not request microphone/audio recording, contacts, location, SMS, call-log/phone-state, shared-storage/media-reading, advertising-ID, accessibility-service, or notification-listener access. `POST_NOTIFICATIONS` lets ONYX post its own notifications; it is not permission to read other apps' notifications. Camera access is for pairing, not a photo-attachment or video-calling feature.

The app also sets `allowBackup=false` and includes explicit cloud-backup and device-transfer exclusions for app data. This describes the app's backup configuration, not an additional Android permission.

## Source and signing fixes made during F-Droid validation

- Restored the Android resources needed by a clean checkout: launcher and notification icons, artwork, theme/color resources, and data-extraction rules. A root-only `/Res/` ignore rule prevents the private root folder from also hiding `app/src/main/res` on Windows.
- Removed duplicate NDK ABI filters that conflicted with Gradle ABI splits.
- Added `/site/` to ignore rules for new files and excluded the unrelated website from F-Droid source scanning. Previously tracked website files remain in Git; the Android build does not package the website.
- Corrected the submission metadata to use `CurrentVersion`, full source commit hashes, version-template `%v` binary URLs, per-ABI version-code operations, and F-Droid's canonical metadata formatting.
- Corrected the external APK signing step to preserve ZIP alignment and omit legacy JAR/v1 signing metadata, using `--alignment-preserved true --v1-signing-enabled false`. The app requires Android 11+, and the corrected APK signatures verify with Android's signing tools. F-Droid then successfully copied and verified the release signatures for all three rebuilt APKs.

**Exact source for these APKs:** [`057ea6f6c220e0d03c5fd96ebc68f30a165c5ac5`](https://github.com/SirRogersJackBlood/ONYX/tree/057ea6f6c220e0d03c5fd96ebc68f30a165c5ac5). Use this full commit when reproducing the corrected files. The existing `v1.3.0` tag predates the resource/ABI fixes and has not been moved.

## Reading the F-Droid report

- **Minor — CAMERA / INTERNET:** the CI script classifies these capabilities as minor findings. They describe permissions in the APK; they are not failed test results or evidence that camera data is being uploaded.
- **Info — other permissions:** disclosures of the remaining five permissions explained above.
- **Info — signing key:** identifies the publisher certificate, `CN=SirRogersJackBlood, O=ONYX`; compare its SHA-256 fingerprint below.
- **Info — APK ABI and size:** identifies the architecture and download size for each variant.
- **Info — R8 marker:** compiler/build information: release DEX output, R8 full mode, desugaring, and minimum API 30. The reported mapping ID is a build identifier, not a device or user identifier.
- **Info — Reproducible build APK:** links the developer reference APK whose signature was successfully verified on F-Droid's rebuild.
- **Info — Fastlane/Triple-T:** confirms discovery of the listing title, summary, description, changelog, icon, and ten phone screenshots. It is listing inventory, not a request to remove them.

Validation evidence: [passed pipeline #2960451922](https://gitlab.com/null-premise-group/fdroiddata/-/pipelines/2960451922), including [build/reproducibility job #17268594130](https://gitlab.com/null-premise-group/fdroiddata/-/jobs/17268594130). Source scanning, update checks, lint, metadata formatting, Git redirects, schema validation, script checks, and APK scanning all passed. These are the checks run for this follow-up; no new emulator or real-device test run is claimed here.

## Corrected downloads

| File | Version code | Architecture | Approximate size |
|---|---|---|---|
| [`onyx-v1.3.0-armeabi-v7a-release.apk`](https://github.com/SirRogersJackBlood/ONYX/releases/download/v1.3.0/onyx-v1.3.0-armeabi-v7a-release.apk) | 81 | 32-bit ARM | 35.58 MiB |
| [`onyx-v1.3.0-arm64-v8a-release.apk`](https://github.com/SirRogersJackBlood/ONYX/releases/download/v1.3.0/onyx-v1.3.0-arm64-v8a-release.apk) | 82 | 64-bit ARM | 39.77 MiB |
| [`onyx-v1.3.0-x86_64-release.apk`](https://github.com/SirRogersJackBlood/ONYX/releases/download/v1.3.0/onyx-v1.3.0-x86_64-release.apk) | 83 | x86-64, including compatible emulators | 43.80 MiB |

This verified set contains three per-ABI APKs, not a universal APK. Assets named `legacy-alignment` and the older `v1.2.4` debug files are retained as historical files. The current downloads are the three `v1.3.0-...-release.apk` files linked above.

## Verify the corrected APKs

[Download `SHA256SUMS-release.txt`](https://github.com/SirRogersJackBlood/ONYX/releases/download/v1.3.0/SHA256SUMS-release.txt):

```text
a5cb75666c3666ffc8762e8fcc5e1931d020160be666cf549bfc7b5b9f7d28fd  onyx-v1.3.0-armeabi-v7a-release.apk
7281527501cf014faa6bca6306499b2e33c9d55445d3b5d24ee7ac8fccf3f56a  onyx-v1.3.0-arm64-v8a-release.apk
433822a09bcc349cb29fb5500c3bed902ea2eb5bed804334cae44da4208de884  onyx-v1.3.0-x86_64-release.apk
```

All three corrected APKs use this Android signing certificate:

```text
CN=SirRogersJackBlood, O=ONYX
SHA-256: dc2130161e6ce3f7e0fc355bbf733fbe169170f59b6a24223a04d31cf7102e9e
```

For example:

```powershell
Get-FileHash .\onyx-v1.3.0-arm64-v8a-release.apk -Algorithm SHA256
apksigner verify --verbose --print-certs .\onyx-v1.3.0-arm64-v8a-release.apk
```

The corrected files are Android-signed and covered by `SHA256SUMS-release.txt`. No new detached PGP signatures were created for this corrected set. The older `SHA256SUMS.txt`, its `.asc`, and the v1.2.4 `.apk.asc` files do not authenticate these replacement APKs.

## Existing limitations

- Both phones need connectivity for delivery; queued messages retry when possible.
- One onion address is used per identity; Tor client authorization is not implemented here.
- Message encryption and Tor's transport are separate layers; the Tor circuits themselves are not post-quantum.

Implementation references at the exact build commit: [manifest](https://github.com/SirRogersJackBlood/ONYX/blob/057ea6f6c220e0d03c5fd96ebc68f30a165c5ac5/app/src/main/AndroidManifest.xml), [QR scanner](https://github.com/SirRogersJackBlood/ONYX/blob/057ea6f6c220e0d03c5fd96ebc68f30a165c5ac5/app/src/main/kotlin/io/github/proteu5/onyx/ui/ScanActivity.kt), [service and notifications](https://github.com/SirRogersJackBlood/ONYX/blob/057ea6f6c220e0d03c5fd96ebc68f30a165c5ac5/app/src/main/kotlin/io/github/proteu5/onyx/service/OnyxService.kt), [app lock](https://github.com/SirRogersJackBlood/ONYX/blob/057ea6f6c220e0d03c5fd96ebc68f30a165c5ac5/app/src/main/kotlin/io/github/proteu5/onyx/ui/Ui.kt), [Snowflake controller](https://github.com/SirRogersJackBlood/ONYX/blob/057ea6f6c220e0d03c5fd96ebc68f30a165c5ac5/app/src/main/kotlin/io/github/proteu5/onyx/snowflake/SnowflakeController.kt).

---
Design, direction and QA: **SirRogersJackBlood**. Original development credits: **Claude (Anthropic)**. Change record: CR-ONYX-2026-006.
