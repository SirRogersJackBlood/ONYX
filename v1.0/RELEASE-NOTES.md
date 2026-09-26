# ONYX v1.0.0-pre1 "Forge": pre-release

🌐 [0nyx.up.railway.app](https://0nyx.up.railway.app/) · Source: [SirRogersJackBlood/ONYX](https://github.com/SirRogersJackBlood/ONYX)

> **Pre-release (debug build).** Signed with a development key for testing. The F-Droid release will be built and signed by F-Droid from source and will **not** upgrade over this build.

## Highlights
- **Tor peer-to-peer messaging.** Each phone hosts its own ephemeral v3 onion service. No servers, accounts or phone numbers.
- **Post-quantum E2EE.** PQXDH (X25519 + Kyber-1024) and the Triple Ratchet (Double Ratchet + SPQR/ML-KEM-768), via libsignal 0.86.5.
- **In-person QR pairing.** Contacts are verified from the first message. **Invite links** (24 h, single use) pair remotely; those contacts stay *unverified* until you compare safety numbers.
- **Hardware vault.** AES-256-GCM, key in StrongBox/TEE. Screenshots blocked, content-free notifications, app lock, disappearing messages, panic wipe.
- **/FORGE rank.** An optional Snowflake proxy (Wi-Fi + charging only). Your rank is visible only to people you chat with.

## Downloads

| File | For | Size |
|---|---|---|
| `onyx-v1.0-pre1-arm64-v8a-debug.apk` | Almost all phones since 2017. **Pick this if unsure.** | 238.7 MB |
| `onyx-v1.0-pre1-armeabi-v7a-debug.apk` | Older 32-bit phones | 222.9 MB |
| `onyx-v1.0-pre1-x86_64-debug.apk` | Emulators | 244.8 MB |
| `onyx-v1.0-pre1-universal-debug.apk` | Any device (largest) | 607.9 MB |

Debug builds keep full native debug symbols, hence the size. Android shows the version as `0.1.0-forge`.

## Verify

**SHA-256**
```text
0a04ed83a2981b34c9e83dac666e3f007eacd817419eb504c1d8e446c438d07c  onyx-v1.0-pre1-arm64-v8a-debug.apk
dc94ec33ce2e06b5b788d1a2a54fbddc5c78eba3b543d4214afce02dbd32a583  onyx-v1.0-pre1-armeabi-v7a-debug.apk
92219a72683c5b59149a4d5e46ca97ef887d6b19df337eca53567a624804a8cc  onyx-v1.0-pre1-x86_64-debug.apk
f3192e6e6f17c0cdc67ce461dfcfa3ec48e6725e308cded5d4298cc423b24ed9  onyx-v1.0-pre1-universal-debug.apk
```

**Signing certificate** (`CN=Android Debug, O=Android, C=US`)
```text
SHA-256  f9eb3fcbbc2115fb3b5dcd67e842e62d80c4c2afa85dd4c5401dbdcca2ee5165
```

```powershell
Get-FileHash .\onyx-v1.0-pre1-arm64-v8a-debug.apk -Algorithm SHA256
apksigner verify --print-certs .\onyx-v1.0-pre1-arm64-v8a-debug.apk
```

## Known limitations
- Both phones must be online for delivery (messages queue and retry). Mailbox mode is planned.
- One onion address per identity; no Tor client authorization yet.
- Tor circuits are not post-quantum (message content is).
- Higher battery use than push-based messengers, by design.

---
<sub>Design by **SirRogersJackBlood** · AGPL-3.0-only · Change record CR-ONYX-2026-001</sub>
