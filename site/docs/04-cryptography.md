# Cryptography

This page covers every cryptographic operation in ONYX: what it protects, which primitive it uses and why. It assumes some familiarity with Diffie-Hellman, hash functions and authenticated encryption.

> **Important** ONYX does **not** implement its own message-encryption protocol. Session establishment and message encryption are done by **libsignal**, Signal's own audited Rust library, through its Java/Android bindings. ONYX's own cryptography is limited to pairing, link authentication, padding and local storage, and it uses only standard constructions (HKDF, HMAC-SHA256, AES-256-GCM, SHA3-256).

## Overview

| Layer | Purpose | Primitives | Implemented by |
|---|---|---|---|
| Transport | Hide IPs and routing | Tor v3 onion services (ntor: X25519, Ed25519, AES-128-CTR, SHA3) | Tor |
| Pairing | Authenticate first contact in person | HKDF-SHA256, HMAC-SHA256, SHA-256 commitment | ONYX |
| Link auth | Prove every connection comes from a paired contact | HMAC-SHA256 challenge-response | ONYX |
| Session setup | Post-quantum authenticated key agreement | PQXDH: X25519 + Kyber-1024, XEdDSA signatures | libsignal |
| Messages | Forward secrecy + post-compromise security, PQ-hardened | Double Ratchet + SPQR (ML-KEM-768) = Triple Ratchet | libsignal |
| Size hiding | Hide message and frame lengths | ISO/IEC 7816-4 padding to fixed buckets | ONYX |
| Storage | Protect data at rest | AES-256-GCM, HMAC-SHA256 index, Android Keystore (StrongBox/TEE) | ONYX + Android |

## Keys on your phone

| Key | Type | Lifetime | Stored |
|---|---|---|---|
| Identity key pair | Curve25519 (X25519 for DH, XEdDSA for signatures) | Permanent (until panic wipe) | Encrypted store |
| Registration ID | Random 14-bit value | Permanent | Encrypted store |
| Signed prekey | X25519, signed by identity key | One per pairing | Encrypted store |
| One-time prekey | X25519 | One per pairing, deleted after use | Encrypted store |
| One-time Kyber prekey | Kyber-1024, signed by identity key | One per pairing, deleted after use | Encrypted store |
| Onion service key | Ed25519 | Permanent | Encrypted store (never in Tor's plaintext directory) |
| Pairing secret | 256 random bits | ≤ 10 minutes, memory only | RAM |
| Link key (per contact) | 256 bits, HKDF output | Life of the contact | Encrypted store |
| Vault master key | AES-256 | Permanent | Android Keystore: StrongBox if available, else TEE |
| Data-encryption key (DEK) | AES-256 | Permanent | Encrypted by the master key, on disk |

## Pairing

Pairing turns a face-to-face meeting into a cryptographically authenticated relationship, with no server and no trust-on-first-use gap.

### The QR code

```text
QR = "onyx1:" || base64url( 0x01 || onion_pk[32] || C[32] || s[32] || expiry[u32] )

onion_pk  Ed25519 public key of the displayer's onion service
C         SHA-256( serialized libsignal IdentityKey of the displayer )
s         256-bit one-time secret from a CSPRNG
expiry    now + 600 seconds (Unix time)
```

The code is about 140 characters, which keeps the QR sparse and easy to scan. Putting a hash commitment `C` in the code instead of the full identity key keeps it small while still pinning the identity.

### Key derivation

Both sides derive three independent values from the secret with HKDF-SHA256 (RFC 5869):

```text
salt       = "ONYX-pairing-v1"
pairingId  = HKDF(s, salt, info="pairing-id",  L=16)
macKey     = HKDF(s, salt, info="pairing-mac", L=32)
linkKey    = HKDF(s, salt, info="link-key",    L=32)
```

Domain-separated `info` strings mean compromise of one output reveals nothing about the others.

### Exchange

```text
A (scanner)                                         B (displayer)
   ── Tor ── connect to onion_pk ─────────────────────▶
   ◀──────────────────────────────── CHALLENGE(nonce)
   PAIR_REQUEST { pairingId, IK_A, onion_A, [mailbox_A],
                  mac_req = HMAC(macKey, "req" || T(pairingId, IK_A, onion_A, mailbox_A)) }
   ───────────────────────────────────────────────────▶
                                         look up pairingId, check expiry,
                                         verify mac_req (constant time),
                                         delete the pending code (single use),
                                         pin IK_A for contact A
   ◀─── PAIR_RESPONSE { bundle_B, [mailbox_B],
          mac_resp = HMAC(macKey, "resp" || T(PAIR_REQUEST, bundle_B, mailbox_B)) }
   verify mac_resp,
   check SHA-256(bundle_B.identityKey) == C,
   pin IK_B, run PQXDH with bundle_B,
   send first PreKey message ───────────────────────▶ completes the session
```

`T(...)` is a transcript encoding in which every field is prefixed with its 32-bit length. That makes it impossible to shift bytes from one field into another and produce a colliding MAC input.

### What this guarantees

- **Only someone who saw the QR can pair.** Without `s`, an attacker cannot produce `mac_req`.
- **B's identity is pinned by the QR itself.** Even an attacker who somehow knew `s` could not substitute their own identity key for B's, because `SHA-256(IK_B)` is fixed in the code the scanner read with their own eyes.
- **The response is bound to the request.** `mac_resp` covers the complete encoded request, so responses cannot be replayed across pairing attempts.
- **Replay is useless.** The pending code is deleted on first valid use and expires after 10 minutes regardless.
- **Verification is built in for in-person pairing.** Because the channel for `C` is physical, contacts added by QR are verified from the first message. Contacts added by invite link are clearly marked unverified until safety numbers are compared.

### Remote invite links (unverified)

A remote invite uses the same payload with version byte `0x02` and a 24-hour expiry. Its secret and prekey bundle are kept in the encrypted store, since the invite must survive app restarts, and are deleted on first use.

The cryptography is the same. What changes is the trust in the **channel**: the link crossed an app ONYX doesn't control, so someone who could read *and replace* the link in transit could substitute their own commitment `C`. Both phones therefore mark the contact **UNVERIFIED** until the users compare the 60-digit safety number (`NumericFingerprintGenerator`, version 2, stable IDs = onion public keys) over an independent channel such as a voice call.

An incoming link, whether tapped (`onyx1:` scheme) or shared as text, only **pre-fills** the pairing field. ONYX never pairs without an explicit tap, so a malicious app or web page cannot silently add a contact.

## Link authentication

Every Tor connection to an onion service is anonymous by design: the server cannot see who connected. ONYX adds a challenge-response so the server can identify which paired contact is calling, and so a stranger learns nothing.

```text
server → CHALLENGE(nS)                   nS: 32 random bytes
client → HELLO(nC, tagC)                 tagC = HMAC(linkKey, "onyx-hello-v1"   || nS || nC)
server → WELCOME(tagS)                   tagS = HMAC(linkKey, "onyx-welcome-v1" || nS || nC)
```

- **Mutual.** The client verifies `tagS`, so it knows it reached the real contact and not an impostor at the same address.
- **Replay-proof.** `nS` is fresh per connection, so a recorded HELLO is worthless.
- **Silent failure.** On a wrong tag the server closes the socket without any error message. Someone who learns your `.onion` address cannot confirm that it runs ONYX.
- **Side-channel care.** The server computes the expected tag for *every* contact and compares each in constant time, without stopping at the first match. Timing therefore doesn't reveal how many contacts you have or where the match was.

## Session establishment: PQXDH

Once pairing succeeds, the scanner runs libsignal's **PQXDH** ("Post-Quantum Extended Diffie-Hellman") against the displayer's prekey bundle:

```text
Bundle_B = { IK_B, SPK_B + Sig(IK_B, SPK_B), OPK_B, PQPK_B + Sig(IK_B, PQPK_B) }

A verifies both signatures, generates an ephemeral X25519 key EK_A, then:

DH1 = DH(IK_A, SPK_B)
DH2 = DH(EK_A, IK_B)
DH3 = DH(EK_A, SPK_B)
DH4 = DH(EK_A, OPK_B)
(CT, SS) = KEM.Encaps(PQPK_B)            Kyber-1024

SK = KDF( F || DH1 || DH2 || DH3 || DH4 || SS )
```

A sends `CT` and `EK_A` with the first message. B decapsulates and derives the same `SK`.

**Why this matters:** an adversary who records the traffic today and gets a quantum computer later can break `DH1`–`DH4`, but not `SS`. Because `SK` mixes both, the session key stays secret. This is the "harvest now, decrypt later" defence.

**How ONYX uses prekeys differently from Signal:** Signal uploads prekeys to a server so strangers can start sessions with you asynchronously, and keeps a *last-resort* Kyber key for when one-time keys run out. ONYX publishes nothing. A fresh one-time EC prekey, signed prekey and Kyber prekey are generated for **each pairing** and handed only to the person who scanned your code. The Kyber prekey is deleted as soon as it is used, so there is no last-resort key to reuse.

> **Note** libsignal 0.86 labels this KEM `KYBER_1024`: CRYSTALS-Kyber-1024 as used in Signal's PQXDH specification. NIST standardised the same design, with small changes, as ML-KEM (FIPS 203).

## Messages: the Triple Ratchet

After PQXDH, every message is protected by libsignal's session cipher, which combines two ratchets.

### Double Ratchet

- **Symmetric-key ratchet.** Each message key comes from a KDF chain (HMAC-SHA256) and is deleted after use. A stolen phone cannot decrypt messages that were already received. This is **forward secrecy**.
- **Diffie-Hellman ratchet.** Each reply carries a new X25519 ratchet key, and the chain is re-seeded with a fresh DH output. If an attacker briefly compromises session state, they lose access again after the next round trip. This is **post-compromise security** ("self-healing").
- **Message encryption:** AES-256 in CBC mode with HMAC-SHA256 authentication, keys derived per message.

### SPQR: the post-quantum ratchet

The Double Ratchet's self-healing depends on X25519, which a quantum computer could break. Signal's **Sparse Post-Quantum Ratchet (SPQR)** runs *alongside* it:

- It uses **ML-KEM-768** in a "braid": encapsulation keys and ciphertexts are split into chunks and spread across many messages, so no single message has to carry a full ML-KEM ciphertext.
- Each completed exchange produces a fresh post-quantum shared secret.
- The Double Ratchet key and the SPQR key are **mixed through a KDF** to form each message key. An attacker has to break **both** the elliptic-curve and the lattice cryptography.

libsignal negotiates SPQR automatically. ONYX gets it by using libsignal's `SessionCipher`, and does not reimplement any part of it.

## Padding: hiding sizes

Encryption hides content, not length, and length leaks a lot: a one-word reply looks nothing like a long message. ONYX pads at two layers.

**Plaintext buckets** (before libsignal encrypts):

```text
256 B · 1 KiB · 4 KiB · 16 KiB · 64 KiB
```

**Wire frame buckets** (every frame on the Tor link):

```text
1 KiB · 4 KiB · 16 KiB · 64 KiB · 256 KiB
```

The scheme is ISO/IEC 7816-4: append `0x80`, then `0x00` up to the bucket size. Unpadding scans back from the end and **fails closed**: a block that isn't exactly a bucket size, or doesn't contain the `0x80` marker, is rejected. Signal pads plaintext to multiples of 160 bytes; ONYX's buckets are coarser and apply to the wire as well.

Timestamps inside messages are rounded down to the minute before encryption, so a phone seized later reveals less about exactly when messages were written.

## Storage: the vault

```text
Android Keystore (StrongBox secure element if present, else TEE)
└── masterKey: AES-256-GCM, non-exportable, usable only after first unlock since boot
    └── wraps DEK (random 256-bit)             → vault/dek.bin = len(iv) || iv || GCM(masterKey, DEK)
        ├── indexKey = HKDF(DEK, "ONYX-vault-v1", "index")
        └── every record:
              ns_h = HMAC(indexKey, "ns" || namespace)[0..16]
              k_h  = HMAC(indexKey, "k"  || T(namespace, key))[0..16]
              v    = iv(12) || AES-256-GCM(DEK, iv, aad = ns_h || k_h, T(key, value))
```

Design choices:

- **Nothing identifying in plaintext.** Table names, contact IDs, onion addresses and message IDs are stored only as HMAC outputs. A forensic image of the database file shows random-looking keys and values.
- **The AAD binds each value to its row.** Ciphertext can't be moved to another row or namespace without failing authentication.
- **No timestamps on disk.** Row ordering uses a local counter, not a clock.
- **Secure delete.** SQLite's `secure_delete` pragma overwrites freed pages, so disappearing messages don't linger in free space.
- **Hardware binding.** The master key is generated inside the Keystore with `setUnlockedDeviceRequired(true)`. It never exists in app memory and can't be copied off the device.
- **Panic wipe order.** The master key is destroyed *first*. Even if deleting files is interrupted, the remaining ciphertext is permanently unreadable.

Why not SQLCipher? It encrypts pages but still needs its own key-management layer, and it adds a large native dependency. The per-record design above uses only platform primitives, hides index values, and plugs into the same `KeyVault` interface a future hypervisor-isolated vault will implement.

## Onion addresses

ONYX validates every onion address before connecting, using Tor's v3 format:

```text
address  = base32( pubkey[32] || checksum[2] || 0x03 )
checksum = SHA3-256( ".onion checksum" || pubkey || 0x03 )[0..2]
```

Android does not guarantee SHA3, so ONYX includes a small FIPS 202 implementation. It is tested against the JDK's SHA3-256 and the FIPS "abc" vector, and checked against the Tor Project's own published onion address. A corrupted or tampered QR is therefore rejected before any network traffic.

## What is not post-quantum (yet)

Being honest about gaps:

| Component | Status |
|---|---|
| Message content | PQ-safe (PQXDH + SPQR) |
| Pairing MACs | Symmetric (HMAC-SHA256): not affected by Shor's algorithm |
| Local storage | Symmetric (AES-256): not affected by Shor's algorithm |
| **Tor circuits** | **Not PQ.** Tor's ntor handshake uses X25519. A future quantum adversary with recorded traffic could strip Tor's layers, but would find only libsignal ciphertext inside. What would be exposed is routing metadata: that two onion services talked. |
| Onion service identity | Ed25519: not PQ; upstream Tor work |

## Test vectors and verification

The protocol core (`core/` in the source tree) is plain Kotlin/JVM with no Android dependencies, and its test suite runs on any JDK:

- SHA3-256 against the JDK implementation at every block-boundary length, plus the FIPS 202 vector
- HKDF against RFC 5869 test case 1
- a real Tor v3 onion address checksum
- pairing: happy path, wrong secret, a flipped bit in the request, a response replayed against a different request
- link auth: correct contact, replay under a new challenge, stranger
- SOCKS5 against a fake Tor, including the onion-specific error codes

```bash
./gradlew :core:test
```
