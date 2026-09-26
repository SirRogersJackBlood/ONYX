# Wire protocol

Byte-level reference for everything ONYX sends. All integers are big-endian. `u16`, `u32` and `u64` are unsigned. `bytes16(x)` means a `u16` length followed by `x`; `bytes32(x)` means a `u32` length followed by `x`.

## Transport

Each phone hosts an ephemeral Tor v3 onion service on **virtual port 9878**, which Tor forwards to a random loopback port where ONYX listens. Outgoing connections go through Tor's SOCKS5 port using **only** the hostname address type (`ATYP 0x03`). ONYX never resolves names locally and never passes an IP to the SOCKS port, so there is no DNS leak path.

## Frame envelope

Every message on a link is one frame:

```text
frame     = u32 length || padded
padded    = pad( type[u8] || body ,  FRAME_BUCKETS )
length   ∈ { 1024, 4096, 16384, 65536, 262144 }
pad(x, B) = x || 0x80 || 0x00 … up to the smallest bucket in B that fits
```

A receiver rejects any length that isn't a bucket, any padding without the `0x80` marker, unknown types, and trailing bytes after a body.

## Frame types

| Type | Name | Direction | Body |
|---|---|---|---|
| `0x01` | CHALLENGE | server → client | `nonce[32]` |
| `0x02` | HELLO | client → server | `nonce[32] ‖ tag[32]` |
| `0x03` | WELCOME | server → client | `tag[32]` |
| `0x04` | PAIR_REQUEST | client → server | see below |
| `0x05` | PAIR_RESPONSE | server → client | see below |
| `0x10` | MESSAGE | client → server | `signalType[u8] ‖ bytes32(ciphertext)` |
| `0x11` | ACK | server → client | `digest[16]` |
| `0x7e` | REJECT | either | empty (never says why) |
| `0x7f` | BYE | either | empty |

`signalType` is libsignal's `CiphertextMessage.getType()`: `3` for a PreKey message (the first message of a session), `2` for a normal ratchet message.

`digest = SHA-256(signalType ‖ ciphertext)[0..16]`. The receiver sends an ACK **only after the decrypted message is stored**. A duplicate is still ACKed, so the sender can drop it from its outbox.

## Connection flows

### Regular link

```text
S → C  CHALLENGE(nS)
C → S  HELLO(nC, HMAC(linkKey, "onyx-hello-v1" ‖ nS ‖ nC))
S → C  WELCOME(HMAC(linkKey, "onyx-welcome-v1" ‖ nS ‖ nC))
C → S  MESSAGE …          S → C  ACK …          (repeated)
C → S  BYE
```

If HELLO doesn't match any contact, the server closes the socket without sending anything.

### Pairing

```text
S → C  CHALLENGE(nS)          (always sent first, so pairing and regular links look the same)
C → S  PAIR_REQUEST
S → C  PAIR_RESPONSE          (or close, if the code is unknown, expired, used or the MAC fails)
```

## Pairing messages

### QR payload

```text
"onyx1:" base64url-nopad(
  version[u8] = 1
  onionPk[32]
  identityCommit[32]      SHA-256(IdentityKey.serialize())
  secret[32]
  expiresAt[u32]          Unix seconds
)
```

### PAIR_REQUEST body

```text
pairingId[16]
bytes16(identityKey)      serialized libsignal IdentityKey (33 bytes)
onionPk[32]
hasMailbox[u8] (0|1)  [mailboxPk[32]]
mac[32]                   HMAC(macKey, "req" ‖ T(pairingId, identityKey, onionPk, mailboxPk|ε))
```

### PAIR_RESPONSE body

```text
bytes32(bundle)           PreKeyBundleWire, below
hasMailbox[u8] (0|1)  [mailboxPk[32]]
mac[32]                   HMAC(macKey, "resp" ‖ T(PAIR_REQUEST body, bundle, mailboxPk|ε))
```

`T(a, b, …) = bytes32(a) ‖ bytes32(b) ‖ …`

### PreKeyBundleWire

```text
version[u8] = 1
registrationId[u32]  deviceId[u32]
preKeyId[u32]         bytes16(preKey)            (empty if none)
signedPreKeyId[u32]   bytes16(signedPreKey)  bytes16(signedPreKeySignature)
bytes16(identityKey)
kyberPreKeyId[u32]    bytes16(kyberPreKey)   bytes16(kyberPreKeySignature)
```

Field size limits are enforced on decode: EC keys ≤ 64 B, signatures ≤ 128 B, Kyber key ≤ 2048 B.

## Message plaintext (inside libsignal)

```text
envelope = pad( version[u8]=1 ‖ kind[u8] ‖ id[16] ‖ sentAtMinute[u32] ‖ bytes32(body) ,  MESSAGE_BUCKETS )
MESSAGE_BUCKETS = { 256, 1024, 4096, 16384, 65536 }
```

| Kind | Name | Body |
|---|---|---|
| 1 | TEXT | UTF-8 text, ≤ 80 characters after `MessagePolicy.sanitize` (enforced on send and receive) |
| 2 | BADGE | `0x01 ‖ tier[u8] ‖ cover[u8]` (3 bytes, nothing else) |
| 3 | RECEIPT | reserved; ONYX does not send read receipts |
| 4 | PAIRED | empty; first message after pairing, completes PQXDH |
| 5 | TIMER | `u32` disappearing-message seconds (0 = off) |

## SMS fallback encoding (planned transport)

The codec ships and is tested now; the Android transport comes later. Each ciphertext is sent as **exactly four** GSM-7-safe SMS parts, whatever its length, so the part count doesn't leak size either:

```text
part = "ONX1" ‖ msgId(6 base32) ‖ index(1) ‖ total(1) ‖ base64url chunk (≤ 141 chars)
payload = signalType[u8] ‖ bytes32(ciphertext) ‖ random fill to capacity
```

Maximum ciphertext is 418 bytes, enough for normal ratchet messages in the 256-byte plaintext bucket.

## Outbox and retries

- Each outgoing envelope is encrypted **once** and the ciphertext is stored. Retries resend identical bytes, so the ratchet never forks.
- Back-off: `min(15 s × 2^attempt, 30 min) × U(0.75, 1.25)`. The jitter stops retry timing from becoming a fingerprint.
- Messages to the same contact are delivered in order over one link.
