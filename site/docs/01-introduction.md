# Introduction

ONYX is a private messenger for Android that works **without phone numbers, accounts or servers**. Two people pair once, in person, by scanning a QR code. After that, every message travels directly from one phone to the other through the Tor network. It is end-to-end encrypted with the same post-quantum protocol Signal uses.

> **Note** ONYX is in preview. The first milestone ("Forge") covers one-to-one messaging over Tor. Mailbox delivery, SMS fallback and nearby transport are on the [roadmap](faq#whats-on-the-roadmap).

## What makes it different

Most "private" messengers encrypt message content well but still route everything through a company's servers. Those servers learn your phone number, your IP address, when you are online and, often, who you talk to. ONYX removes the server entirely.

| | ONYX |
|---|---|
| Account | None. Your identity is a key pair generated on your phone. |
| Contact discovery | None. You add people by scanning their code face to face. |
| Transport | Tor onion services, phone to phone |
| Encryption | Signal Protocol via libsignal: PQXDH and the Triple Ratchet |
| Local storage | AES-256-GCM with a hardware-held key (StrongBox / TEE) |
| Tracking | None. No analytics, no crash reporting, no Google services |

## How these docs are organised

- **[Install & verify](install)**: download, check the hash and signature, first launch.
- **[How it works](how-it-works)**: the user-level walkthrough of pairing and messaging.
- **[Cryptography](cryptography)**: every key, every KDF and why each one is there.
- **[Wire protocol](protocol)**: byte-level layouts of the QR code, frames and pairing messages.
- **[Threat model](threat-model)**: who can see what, and what ONYX does not protect against.
- **[Tor & Snowflake](tor-and-snowflake)**: onion services, SOCKS, and the /FORGE contributor rank.
- **[Build from source](build-from-source)**: reproduce the APK yourself.
- **[FAQ](faq)**

## Principles

1. **Privacy first, no cut corners.** When convenience and privacy conflict, privacy wins, and the trade-off is written down.
2. **No home-made cryptography.** ONYX uses reviewed primitives and Signal's own library, and innovates only in how they are put together.
3. **Say what isn't done.** Features that are planned but unfinished are marked as such, never implied.
