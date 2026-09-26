# Threat model

A privacy tool is only as honest as its threat model. This page says who ONYX defends against, how, and where it stops.

## Assets

1. **Message content**
2. **Social graph**: who talks to whom
3. **Timing and volume**: when and how much you communicate
4. **Identity**: your real-world identity, IP address and phone number
5. **Data at rest** on a phone

## Adversaries

| Adversary | What they can do | What they learn with ONYX |
|---|---|---|
| Mobile carrier / ISP | Watch all your traffic | That you use Tor. Not who you talk to, when, or how much. |
| Malicious Tor relay | See encrypted cells passing through | Nothing linking the two phones. Onion rendezvous hides both ends. |
| A contact | Everything you send them | Your messages and onion address. Never your IP or phone number. |
| A stranger who learns your `.onion` | Connect to it | A socket that closes silently. They can't confirm it's ONYX or that you're online for anyone in particular. |
| Someone with the QR code after it expired | Try to pair | Nothing. Codes are single-use, expire after 10 minutes and exist only in memory. |
| Network attacker during pairing | Intercept or modify Tor traffic | Can't substitute keys, because the identity commitment is in the QR and was read in person. |
| Thief with your locked phone | Image the storage | Ciphertext whose key lives in the secure element and is unusable before first unlock. |
| Future quantum computer + recorded traffic | Break X25519 / Ed25519 | Tor routing metadata only. Message keys also depend on Kyber and ML-KEM. |

## What ONYX does NOT protect against

> **Warning** Be clear about these limits before relying on ONYX in a high-risk situation.

- **A compromised phone.** Malware with root, a malicious keyboard, or spyware that screenshots the display sees what you see. ONYX blocks screenshots and asks keyboards not to learn from input (`FLAG_SECURE`, `IME_FLAG_NO_PERSONALIZED_LEARNING`), but the operating system is trusted.
- **An unlocked phone in someone else's hands** with ONYX open. Use the app lock and panic wipe.
- **A global passive adversary** watching both phones' network links at once can correlate timing. This is a known limit of Tor and of every low-latency anonymity network.
- **Your contact.** They can screenshot with another camera, or show their phone to someone. Encryption protects the channel, not the recipient's behaviour.
- **Coercion.** No software can stop you being forced to unlock your phone.
- **SMS fallback** (when it ships) exposes metadata to the carrier by nature. It is off by default, opt-in per contact, and labelled every time it's used.

## Metadata, specifically

| Metadata | Signal | ONYX |
|---|---|---|
| Phone number | Required (can be hidden from contacts) | Not collected or used |
| IP address | Seen by Signal's servers | Seen by nobody |
| Contact list | Hashed discovery in an SGX enclave | Never leaves the phone |
| Who messages whom | Hidden by sealed sender from Signal, but the server still sees connection timing | No server exists |
| Message size | Padded to 160-byte multiples | Plaintext buckets + fixed-size wire frames |
| Online status | Signal's servers see connection times | Only your contacts can reach your onion, after proving a pairing key |
| Push service | Google FCM on stock Android | None |

## Known weaknesses and planned hardening

These are real and on the roadmap:

- **One onion address per identity.** Every contact knows the same address, so two contacts comparing notes can confirm they both talk to you. *Planned:* a separate onion address for each contact.
- **No Tor client authorization yet.** The onion service descriptor is fetchable by anyone who knows the address, which reveals when you're online. Link auth stops them talking to you, but not seeing that you're up. *Planned:* v3 `ClientAuthV3`, so only contacts can even fetch the descriptor.
- **Both phones must be online** for direct delivery. *Planned:* a mailbox mode, where an always-on device of the recipient's (an old phone on Wi-Fi) holds messages.
- **Loopback listener.** Other apps on the same phone can connect to ONYX's local port. They are rejected by link auth, but a Unix-domain socket would remove the surface entirely. *Planned.*
- **Tor is not post-quantum.** That is upstream work in the Tor Project.

## Supply chain

- **No Google Play Services, Firebase, analytics or crash reporting.**
- Dependencies: libsignal (Signal), tor-android + jtorctl (Guardian Project / Tor Project), IPtProxy (Guardian Project), ZXing (QR codes). Nothing else.
- F-Droid builds ONYX from source. Reproducible builds let anyone check that the published APK matches the code.
- This website runs **no JavaScript**, sets **no cookies**, loads **nothing** from third parties, and its server keeps **no request logs**.
