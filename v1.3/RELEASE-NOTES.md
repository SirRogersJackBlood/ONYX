# ONYX v1.3.0 "Forge": Release-Signed, F-Droid Bound (pre-release)

🌐 [0nyx.up.railway.app](https://0nyx.up.railway.app/) · Source: [SirRogersJackBlood/ONYX](https://github.com/SirRogersJackBlood/ONYX) · Release: [v1.3.0](https://github.com/SirRogersJackBlood/ONYX/releases/tag/v1.3.0) · Previous: [v1.2.4](https://github.com/SirRogersJackBlood/ONYX/releases/tag/v1.2.4)

> **First release-signed build.** This is the version submitted to F-Droid. From here on, updates from GitHub and F-Droid are signed with the same ONYX release key.

## Read this before installing

**ONYX v1.3.0 has a new app ID:** `io.github.sirrogersjackblood.onyx` (was `io.github.proteu5.onyx`). F-Droid requires an ID the publisher owns. Android treats it as a **new app**:

1. Install v1.3.0. It appears next to your current ONYX.
2. Pair your contacts again (in person by QR, or with an invite link).
3. Uninstall the old ONYX when you're done. Its messages can't be moved across: that's by design, the vault key never leaves the phone.

This is a one-time step. Later versions install over v1.3.0 and keep your data.

## New in v1.3.0

- **Release key.** Signed with a dedicated ONYX release key instead of the development key.
- **About 80% smaller.** The arm64 APK is about 40 MB instead of 230 MB: native libraries are stripped, and libraries for other platforms are no longer packaged.
- **F-Droid ready.** Reproducible-build settings, dependencies only from Google Maven and Maven Central, store listing with screenshots, and a build recipe in the repo.
- **Licensing.** Full AGPL-3.0 text for the code; artwork under Apache-2.0; ONYX and the raven mark are trademarks of SirRogersJackBlood (see `TRADEMARKS.md`).

Everything from v1.2.4 is included: no crash without a screen lock, no data loss when a screen lock is removed on Android 12–14, and clean Tor restarts.

## Tested

Release build, emulator lab: **65/65** on Android 11, 13, 14, 15 and 16 (`scripts/emulator-lab.ps1`), plus fresh installs on real phones.

## Compatibility
- Wire protocol unchanged: v1.3.0 talks to every earlier version.
- Requires Android 11 or newer.

## Downloads

| File | Version code | For |
|---|---|---|
| `onyx-v1.3.0-arm64-v8a-release.apk` | 82 | Almost all phones since 2017. **Pick this if unsure.** |
| `onyx-v1.3.0-armeabi-v7a-release.apk` | 81 | Older 32-bit phones |
| `onyx-v1.3.0-x86_64-release.apk` | 83 | Emulators |
| `onyx-v1.3.0-universal-release.apk` | 80 | Any device (largest) |

## Verify

**SHA-256**
```text
<paste v1.3\SHA256SUMS.txt here after staging>
```

**PGP** (release key `9672 6F1B CC9F 9D5E 0CED CF9C BC48 B84D 2415 5676`, SirRogersJackBlood <sirrogersjackblood@proton.me>; key at [0nyx.up.railway.app/onyx-release-key.asc](https://0nyx.up.railway.app/onyx-release-key.asc))
```text
gpg --import onyx-release-key.asc
gpg --verify SHA256SUMS.txt.asc SHA256SUMS.txt
```
Each APK also has a detached `.apk.asc` signature.

**Signing certificate** (new ONYX release key, `CN=SirRogersJackBlood, O=ONYX`)
```text
SHA-256  dc2130161e6ce3f7e0fc355bbf733fbe169170f59b6a24223a04d31cf7102e9e
```
```powershell
apksigner verify --print-certs .\onyx-v1.3.0-arm64-v8a-release.apk
```

## Known limitations
- Both phones must be online for delivery (messages queue and retry). Mailbox mode is planned.
- One onion address per identity; no Tor client authorization yet.
- Tor circuits are not post-quantum (message content is).

---
<sub>Design, direction & QA by **SirRogersJackBlood** · Development by Claude (Anthropic) · AGPL-3.0-only · Change record CR-ONYX-2026-006</sub>
