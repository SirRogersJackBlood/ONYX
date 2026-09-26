# ONYX — Architecture & Threat Model

**Principle: Privacy first, no cut corners.** When convenience and privacy conflict, privacy wins, and the trade-off is written down here.

## 1. What ONYX protects against

| Adversary | What they can see with ONYX |
|---|---|
| Mobile carrier / ISP | That the phone uses Tor. Nothing about contacts, timing per contact, or content. |
| Tor relays | Encrypted cells. The onion-service rendezvous hides both ends' IPs from each other and from relays. |
| A contact | Your onion address (shared at pairing) and your messages to them. Never your IP or phone number. |
| Someone who learns your .onion | A TCP stream that closes silently unless they prove a pairing-derived key. They cannot confirm you run ONYX. |
| Someone holding your locked phone | An encrypted database whose key is sealed in the Keystore (StrongBox when present) and unusable before first unlock. Panic wipe destroys the key. |
| A future quantum computer recording traffic today | PQXDH (ML-KEM-1024) + SPQR (ML-KEM-768) inside libsignal. Tor's own link layer is **not** post-quantum yet (tracked below). |

**Out of scope (stated honestly):** a fully compromised OS or firmware; someone who watches you type; a global adversary doing end-to-end timing correlation on Tor; an unlocked phone in an attacker's hands with the app open.

## 2. Layers

```
┌───────────────────────────── UI (plain Android Views, FLAG_SECURE) ────────────────────────────┐
│ Messenger: pairing · outbox/retry · receive · disappearing timer · Forge badge sharing          │
├───────────────┬───────────────────────────────┬───────────────────────────────┬────────────────┤
│ CryptoEngine  │ PeerServer / PeerClient       │ SnowflakeController            │ SecureStore    │
│ libsignal     │ padded frames, link auth      │ IPtProxy, guardrails           │ AES-256-GCM    │
│ PQXDH+Triple  │ SOCKS5 (hostname-only)        │ local-only stats → Forge tier  │ HMAC'd indexes │
├───────────────┴───────────────┬───────────────┴───────────────────────────────┼────────────────┤
│ TorController: in-process Tor, ephemeral ADD_ONION, key kept in SecureStore    │ KeyVault       │
│                                                                                 │ Keystore today │
│                                                                                 │ pVM tomorrow   │
└─────────────────────────────────────────────────────────────────────────────────┴────────────────┘
core/  = pure-JVM protocol module (no Android, no network libs), 21 unit tests
```

`KeyVault` is the seam for the Gunyah/AVF thread: when a device can run a protected VM, a Microdroid-backed vault replaces `KeystoreVault` without touching anything above it.

## 3. Protocols

### 3.1 Pairing (QR, in person)
QR = `onyx1:` + base64url(`v1 | onionPk(32) | SHA-256(IdentityKey)(32) | secret(32) | expiry(u32)`) ≈ 140 chars.

1. A scans B's code and connects to B's onion over Tor.
2. A → `PAIR_REQUEST{pairingId, A.identityKey, A.onionPk, mac}`. The MAC is HMAC over a length-prefixed transcript, keyed by HKDF(secret).
3. B verifies the MAC, then pins A's identity and replies `PAIR_RESPONSE{B.bundle, mac(bound to request)}`.
4. A verifies the MAC **and** that SHA-256(bundle.identityKey) equals the QR commitment. A key swapped in by a relay or a fake onion is rejected here.
5. A runs PQXDH and immediately sends a `PAIRED` PreKey message, which completes the session on B's side.

The secret is single-use, lasts 10 minutes, is kept only in memory and never outlives the pairing screen. Both sides derive the same `linkKey` from it.

### 3.2 Link authentication (every connection)
```
server → CHALLENGE(nS)
client → HELLO(nC, HMAC(linkKey, "hello"|nS|nC))
server → WELCOME(HMAC(linkKey, "welcome"|nS|nC))
```
The exchange is mutual and replay-proof. The server checks the tag against every contact's key with no early exit. Anyone who fails gets a closed socket and no error.

### 3.3 Frames and padding
Every frame on the wire is `u32 len | pad(type|body)`, where the padded length is always 1, 4, 16, 64 or 256 KiB. Every plaintext is padded to 256 B, 1, 4, 16 or 64 KiB **before** libsignal encrypts it. Timestamps inside the envelope are rounded down to the minute.

### 3.4 Outbox
Each message is encrypted **once** when queued, and retries resend identical ciphertext, so the ratchet never forks. Backoff runs from 15 s to 30 min with ±25 % jitter, so retry timing doesn't form a fingerprint.

## 4. "One-up Signal": what we changed and what we deliberately did not

**We did not invent a new cipher or ratchet.** Signal's current protocol (PQXDH + the Triple Ratchet, i.e. Double Ratchet + SPQR) is the most-reviewed secure-messaging design in existence, and SPQR was machine-checked with formal verification tools. Home-made cryptography would be a downgrade. ONYX uses the same algorithm through the same library, libsignal.

ONYX goes further than Signal everywhere *around* that algorithm:

| | Signal | ONYX |
|---|---|---|
| Identifier | Phone number (usernames optional) | None. Your identity is a key, exchanged by QR in person. |
| Server | Central servers see IP, timing and (sealed-sender-limited) routing | No server. Onion-to-onion over Tor. |
| IP exposure | Signal's servers see your IP | Nobody does |
| Contact discovery | Hashed phone numbers inside an SGX enclave | None exists; pairing only in person |
| First-contact verification | Optional safety-number check | Required: the QR carries an identity commitment |
| One-time Kyber prekeys | Published to the server, with a last-resort fallback | Generated per pairing, handed only to the person who scanned, deleted when used |
| Push | FCM (Google) on stock builds | None |
| Metadata padding | Plaintext padded to multiples of 160 B | Coarse buckets **plus** fixed-size wire frames |
| Local storage | SQLCipher | AES-GCM per record; index columns are HMACs; secure_delete |
| Notifications | Show sender by default | Content-free, hidden on the lock screen |

**Planned protocol hardening, not yet in this build:**
- Tor v3 client authorization (`ClientAuthV3`), so even the onion descriptor is useless to anyone who isn't a contact.
- Per-contact onion addresses: a leaked address reveals one relationship, not all of them.
- Optional constant-rate cover traffic on the Tor link.
- Tor's post-quantum circuit handshake, once Tor ships it (upstream work).

## 5. Snowflake and Forge rank
- A Snowflake proxy forwards censored users **into** Tor. It is never a relay or an exit. It runs only on unmetered Wi-Fi, while charging, and under the user's daily cap. STUN servers are non-Google.
- Stats (hours relayed, people helped, bytes) stay on the device, encrypted.
- Contacts receive only `Badge{tier, coverIcon}` (3 bytes), inside the E2EE session, and only if the user allows it. There is no leaderboard and no server.
- Disclosure shown before enabling: the broker and the users you help see your public IP; that is inherent to WebRTC.

## 6. Transport stack

| # | Transport | Status in this build |
|---|---|---|
| 1 | Tor direct (onion↔onion) | ✅ Implemented |
| 2 | Tor mailbox (e.g. the Redmi Note 8, always on Wi-Fi) | 🔜 Spec'd; the pairing wire format already carries `mailboxPk` |
| 3 | Nearby (Bluetooth / Wi-Fi Direct) | 🔜 Planned |
| 4 | Encrypted SMS | 🔜 Codec done and tested (fixed 4 parts, size-hiding). Android side needs the default-SMS-app role and is opt-in per contact, labelled "carrier sees metadata". |

`TransportSelector` never falls back to SMS while Tor works (covered by a unit test).

## 7. Build and dependencies (F-Droid)

| Dependency | Why | License |
|---|---|---|
| `org.signal:libsignal-android:0.86.5` | PQXDH, Triple Ratchet | AGPL-3.0 (ONYX is therefore AGPL-3.0) |
| `info.guardianproject:tor-android:0.4.9.6` + `jtorctl:0.4.5.7` | Embedded Tor | BSD-3 |
| `com.netzarchitekten:IPtProxy:5.5.1` | Snowflake proxy | MIT / BSD |
| `com.google.zxing:core:3.5.3` | QR encode/decode | Apache-2.0 |

No AndroidX, no Play Services, no Firebase, no analytics. `dependenciesInfo` is off. ABI splits keep the APKs small.

## 8. Roadmap
- **M1 (this build):** identity, vault, encrypted store, Tor onion, QR pairing, PQ E2EE 1:1 over Tor P2P, outbox, disappearing messages, app lock, panic wipe, Snowflake + Forge rank + badge sharing.
- **M2:** mailbox mode (Redmi as always-on mailbox), Tor client auth, per-contact onions, SMS transport (full default-SMS-app role), bridges (obfs4/Snowflake-as-client) for censored networks.
- **M3:** nearby transport, attachments (chunked, padded), reproducible-build verification, F-Droid submission.
- **Side thread:** `PvmKeyVault` on AVF-capable, self-signed builds (needs `MANAGE_VIRTUAL_MACHINE`).
