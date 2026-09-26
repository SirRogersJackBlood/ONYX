# ONYX v0.1.0 "Forge": preview release

> **Preview build.** Signed with a development key for testing. The F-Droid release will be built and signed by F-Droid from source and will **not** upgrade over this build. Uninstall the preview first; this deletes its data.

🌐 **Website:** [ONYX · Private messaging over Tor](https://0nyx.up.railway.app/), with docs, verification steps and downloads.

## Highlights

- **Tor peer-to-peer messaging.** Each phone hosts its own ephemeral v3 onion service. There are no servers, accounts or phone numbers.
- **Post-quantum end-to-end encryption.** PQXDH (X25519 + Kyber-1024) and the Triple Ratchet (Double Ratchet + SPQR/ML-KEM-768), via libsignal 0.86.5.
- **In-person QR pairing.** One-time, 10-minute codes carry a commitment to your identity key, so contacts are verified from the first message.
- **Invite links.** Pair remotely with a 24 h, single-use, revocable link. The contact stays marked *unverified* until you compare the 60-digit safety number.
- **Share ONYX card.** A QR code and share button for the project website only; nothing about you.
- **/FORGE rank.** An optional Snowflake proxy (unmetered Wi-Fi + charging, daily cap). Your tier is shared only with people you chat with.

## Security & privacy

- Mutual, replay-proof link authentication. Unknown connections are closed silently.
- Every message padded to 256 B / 1 / 4 / 16 / 64 KiB before encryption; every wire frame padded to 1 / 4 / 16 / 64 / 256 KiB.
- Encrypted local store: AES-256-GCM, master key in StrongBox/TEE (unusable before first unlock), HMAC'd index columns, `secure_delete`, no timestamps on disk.
- Onion service key stored only in the encrypted vault.
- `FLAG_SECURE` on every screen, keyboard learning disabled in text fields, content-free notifications hidden on the lock screen.
- App lock (biometric / device credential), disappearing messages (5 min – 1 week), panic wipe (destroys the hardware key first).
- No backups, no cloud transfer, no analytics, no crash reporting, no Google services.

## Tested on

| Device | Android | Result |
|---|---|---|
| Redmi Note 8 | 11 | ✅ |
| Samsung Galaxy S25 Edge | 15+ (edge-to-edge) | ✅ (after inset and button fixes) |

## Downloads

| File | For |
|---|---|
| `onyx-0.1.0-forge-arm64-v8a.apk` | Almost all phones since 2017. **Pick this if unsure.** |
| `onyx-0.1.0-forge-armeabi-v7a.apk` | Older 32-bit phones |
| `onyx-0.1.0-forge-x86_64.apk` | Emulators |

### Verify before installing

```text
SHA-256
<fill in: Get-FileHash .\onyx-0.1.0-forge-arm64-v8a.apk -Algorithm SHA256>
```

```bash
apksigner verify --print-certs onyx-0.1.0-forge-arm64-v8a.apk
```

Signing certificate SHA-256: `<fill in>`

## Known limitations

- **Both phones must be online** for delivery. Messages queue and retry until then; mailbox mode is planned.
- **One onion address per identity.** Contacts who compare notes can tell they talk to the same person; per-contact addresses are planned.
- **No Tor client authorization yet.** Anyone who knows your address can see when you're online, though they can't talk to you.
- **Tor circuits are not post-quantum.** Message content is PQ-protected; routing metadata is not.
- **Higher battery use** than push-based messengers, by design (no push service).
- SMS fallback, nearby transport and attachments are not included yet.

## Build from source

```bash
./gradlew :core:test
./gradlew :app:assembleRelease
```

Requires JDK 17–25 and Android SDK Platform 36. See [README](README.md).

---

<sub>Design by **SirRogersJackBlood** · AGPL-3.0-only</sub>
