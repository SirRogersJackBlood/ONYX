# ONYX v1.1.0 "Forge": pre-release

🌐 [0nyx.up.railway.app](https://0nyx.up.railway.app/) · Source: [SirRogersJackBlood/ONYX](https://github.com/SirRogersJackBlood/ONYX) · Previous: [v1.0.0-pre1](https://github.com/SirRogersJackBlood/ONYX/releases/tag/v1.0.0-pre1)

> **Pre-release (debug build).** Signed with a development key for testing. The F-Droid release will be built and signed by F-Droid from source and will **not** upgrade over this build.

## New in v1.1

### RAW wire view
Tap **RAW** in any chat to split the screen and watch, live, the exact bytes that cross the link for that conversation: what someone who broke Tor's encryption would capture.

- **▲ OUT / ▼ IN** frames with time, type and size on the wire (a normal message is exactly **1,028 bytes**)
- **Frame anatomy**: bucket size, real payload versus zero padding, and whether it's a PQXDH first message or a Triple Ratchet message
- **Hex dump** of each frame: a small header, then ciphertext, then zeros
- **Live size meter** while typing: "ok" and a 200-character paragraph produce the same size on the wire

**Privacy:** the RAW view is memory-only. It is never written to disk, holds only ciphertext and padding (a unit test proves no plaintext reaches it), keeps at most 120 frames per contact, and is wiped by panic wipe. The receiving side starts capturing only *after* the peer proves it's a paired contact.

### Other
- The app now reports its real version: **1.1.0** (versionCode 3).
- `core`: new `FrameCodec.tap` and `WireAnatomy`. **23/23** protocol tests pass.

![RAW wire view on both phones](https://raw.githubusercontent.com/SirRogersJackBlood/ONYX/main/site/public/screenshots/11-raw-wire-view.jpg)

## Compatibility
- ✅ **Tested cross-version:** v1.0.0-pre1 ⇄ v1.1.0 exchange messages normally. The wire protocol is unchanged.
- ✅ **Upgrade in place:** same signing key as v1.0.0-pre1, so installing over it keeps contacts, keys and messages.

## Downloads

| File | For | Size |
|---|---|---|
| `onyx-v1.1.0-arm64-v8a-debug.apk` | Almost all phones since 2017. **Pick this if unsure.** | 238.5 MB |
| `onyx-v1.1.0-armeabi-v7a-debug.apk` | Older 32-bit phones | 222.7 MB |
| `onyx-v1.1.0-x86_64-debug.apk` | Emulators | 244.6 MB |
| `onyx-v1.1.0-universal-debug.apk` | Any device (largest) | 607.7 MB |

Debug builds keep full native debug symbols, hence the size.

## Verify

**SHA-256**
```text
d75dcc0b6f45e6aab3ddaed1d782534dfc6a403b0f5a338fb51a8f9dc335947a  onyx-v1.1.0-arm64-v8a-debug.apk
5b2cb883d4668799d03e78a32373a3d5dd4b9dbab7bccfc8e47a6d4e673083d3  onyx-v1.1.0-armeabi-v7a-debug.apk
66b567e1c024cb6e1809c9411ecf1733db02e9f8b4819a06102c5b74750b948b  onyx-v1.1.0-x86_64-debug.apk
802b5556e93887b58c6e21feabfe8d17e4e35ee9bba8be7755a35f00e8fb9bd2  onyx-v1.1.0-universal-debug.apk
```

**Signing certificate** (`CN=Android Debug, O=Android, C=US`, APK Signature Scheme v2), the same as v1.0.0-pre1
```text
SHA-256  f9eb3fcbbc2115fb3b5dcd67e842e62d80c4c2afa85dd4c5401dbdcca2ee5165
```

```powershell
Get-FileHash .\onyx-v1.1.0-arm64-v8a-debug.apk -Algorithm SHA256
apksigner verify --print-certs .\onyx-v1.1.0-arm64-v8a-debug.apk
```

## Known limitations
- Both phones must be online for delivery (messages queue and retry). Mailbox mode is planned.
- One onion address per identity; no Tor client authorization yet.
- Tor circuits are not post-quantum (message content is).
- Higher battery use than push-based messengers, by design.

---
<sub>Design by **SirRogersJackBlood** · AGPL-3.0-only · Change record CR-ONYX-2026-002</sub>
