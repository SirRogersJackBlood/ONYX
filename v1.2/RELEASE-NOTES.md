# ONYX v1.2.4 "Forge": Hardened on Every Android (pre-release)

🌐 [0nyx.up.railway.app](https://0nyx.up.railway.app/) · Source: [SirRogersJackBlood/ONYX](https://github.com/SirRogersJackBlood/ONYX) · Release: [v1.2.4](https://github.com/SirRogersJackBlood/ONYX/releases/tag/v1.2.4) · Previous: [v.1.2.3](https://github.com/SirRogersJackBlood/ONYX/releases/tag/v.1.2.3)

> **Pre-release (debug build).** Signed with a development key for testing. The F-Droid release will be built and signed by F-Droid from source and will **not** upgrade over this build.
>
> **Update recommended for everyone on Android 12, 13 or 14.** On v1.2.3, removing your phone's screen lock made Android erase ONYX's key, and with it every message and contact on the phone.

## Fixed in v1.2.4

Three bugs, all found by a new emulator test lab and all re-tested.

| Bug | Who it affected | What happens now |
|---|---|---|
| **Data loss when the screen lock is removed** | Android 12–14 | Android erases keys tied to the screen lock when the lock is removed. On these versions ONYX's key stays in the phone's secure hardware (StrongBox or TEE) but is no longer tied to the lock. v1.2.3 keys are switched over safely on first start. |
| **Crash at first launch with no screen lock** | Android 13–14 | Fixed. Settings → Security shows a red **NO SCREEN LOCK** warning with a button to set one. |
| **Crash when Tor had to restart** (background restrictions, or reopening after "Stop ONYX") | All versions | Tor's core runs once per process, so ONYX restarts its process cleanly instead of starting Tor twice. |

If Android has already erased ONYX's key (for example you removed your screen lock while on v1.2.3), ONYX now starts fresh and explains why once, instead of crashing on every launch. That data cannot be recovered; your contacts need to pair with you again.

Android 11, 15 and 16 keep the key tied to the screen lock, as before.

## Tested

`scripts/emulator-lab.ps1`: **65/65 checks pass** on 5 emulated devices.

| Device | Android | Profile |
|---|---|---|
| Pixel 4a | 11 (minimum) | No Google services, 2 GB RAM |
| Small phone | 13 | 200% text size |
| Pixel 7 | 14 | Mainstream phone |
| Pixel Tablet | 15 | Tablet |
| Pixel Fold | 16 (target) | Foldable, edge-to-edge |

Each runs 13 checks: install, launch and Tor bootstrap, rotation, dark mode, offline and back online, Doze, memory pressure, restricted background, process death, screen lock added, screen lock removed.

## Compatibility
- Wire protocol unchanged. Works with v1.0.0-pre1 through v1.2.3.
- Same signing key as earlier pre-releases: installs over them and keeps your data.

## Downloads

| File | For |
|---|---|
| `onyx-v1.2.4-arm64-v8a-debug.apk` | Almost all phones since 2017. **Pick this if unsure.** |
| `onyx-v1.2.4-armeabi-v7a-debug.apk` | Older 32-bit phones |
| `onyx-v1.2.4-x86_64-debug.apk` | Emulators |
| `onyx-v1.2.4-universal-debug.apk` | Any device (largest) |

## Verify

**SHA-256**
```text
611359ec960cad8281af0b45dec32a03eb1f2cdbd9054d285cdc8ea55ff66b91  onyx-v1.2.4-arm64-v8a-debug.apk
d55a9d531b9fe42da586ab3f7b481161547e969c5748e9bd3973bd4e35f55416  onyx-v1.2.4-armeabi-v7a-debug.apk
14882f209660a7ac18861eb09617cab2c5305292f718449f2c543115a0c71300  onyx-v1.2.4-universal-debug.apk
7e7a75179a6c2999664f167b59bc9ef231ed4c82c64815250022c69df9ea772e  onyx-v1.2.4-x86_64-debug.apk
```

**PGP** (release key `9672 6F1B CC9F 9D5E 0CED CF9C BC48 B84D 2415 5676`, SirRogersJackBlood <sirrogersjackblood@proton.me>; key at [0nyx.up.railway.app/onyx-release-key.asc](https://0nyx.up.railway.app/onyx-release-key.asc))
```text
gpg --import onyx-release-key.asc
gpg --verify SHA256SUMS.txt.asc SHA256SUMS.txt
```
Each APK also has a detached `.apk.asc` signature.

**Signing certificate** (`CN=Android Debug, O=Android, C=US`, same as previous releases)
```text
SHA-256  f9eb3fcbbc2115fb3b5dcd67e842e62d80c4c2afa85dd4c5401dbdcca2ee5165
```

## Known limitations
- Both phones must be online for delivery (messages queue and retry). Mailbox mode is planned.
- One onion address per identity; no Tor client authorization yet.
- Tor circuits are not post-quantum (message content is).
- If Tor must restart while ONYX is open on screen, the window closes; reopen it.

---
<sub>Design, direction & QA by **SirRogersJackBlood** · Development by Claude (Anthropic) · AGPL-3.0-only · Change record CR-ONYX-2026-005</sub>
