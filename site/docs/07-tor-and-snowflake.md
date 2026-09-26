# Tor & Snowflake

## Tor inside ONYX

ONYX embeds Tor directly (via the Guardian Project's `tor-android`), so you don't need Orbot or any other app. Tor runs inside ONYX's own process under a foreground service. The notification reads "ONYX is online" and never shows content.

### Configuration

ONYX writes a minimal `torrc`:

```text
AvoidDiskWrites 1     # keep Tor state writes to a minimum
SafeSocks 1           # refuse SOCKS requests that carry raw IP addresses
ClientOnly 1          # never act as a relay
```

### Your onion service

- It is created at runtime with the control-port command `ADD_ONION` as an **ephemeral** service.
- Its Ed25519 private key is stored in ONYX's **encrypted vault**, not in a plaintext `HiddenServiceDir` on disk the way a normal Tor setup keeps it.
- Tor forwards virtual port `9878` to a random port on `127.0.0.1`. Nothing listens on any external interface.

### Outgoing connections

ONYX's SOCKS5 client:

- connects only to `127.0.0.1`;
- sends only hostnames, never IPs, so nothing is resolved on the phone;
- refuses any destination that isn't a checksum-valid v3 `.onion` address;
- understands Tor's onion-specific error codes, so the app can say "contact offline" instead of a generic failure.

## Snowflake: helping others connect

In countries that block Tor, people reach it through **Snowflake**: volunteers' browsers and phones act as short-lived WebRTC proxies into the Tor network. ONYX can make your phone one of those volunteers.

### What it is, and isn't

| | |
|---|---|
| **It is** | A temporary bridge that carries a censored user's *already-encrypted* Tor traffic into the Tor network |
| **It is not** | A Tor relay, and **never an exit node**. No website traffic ever leaves from your IP. |
| **It does not touch** | Your ONYX messages, contacts or onion service |

### Guardrails

Snowflake runs only when **all** of these hold:

1. You turned it on (off by default, with a disclosure screen first).
2. The phone is on **unmetered Wi-Fi**.
3. The phone is **charging**.
4. Today's relayed data is under **your daily cap** (100 MB, 500 MB or 2 GB).

### What it reveals

> **Note** Your public IP address is visible to the Tor Project's Snowflake broker and to the censored users you help. WebRTC works that way, and ONYX shows you this before you turn it on. ONYX uses non-Google STUN servers.

## /FORGE rank

Contributing earns a rank:

| Tier | Reached at |
|---|---|
| Ember | Turned Snowflake on |
| Spark | 10 hours relayed **or** 25 people helped |
| Flame | 50 hours **or** 250 people |
| Forge | 200 hours **or** 1,000 people |
| Crucible | 1,000 hours **or** 5,000 people |

### Privacy rules for the rank

These rules are enforced in code, not just policy:

- Raw stats (hours, people helped, bytes) are **stored encrypted on your phone and never sent anywhere**.
- The **only** thing ever shared is a 3-byte badge: `version ‖ tier ‖ cover icon`.
- It is shared **only** with people you chat with, **inside** the end-to-end-encrypted session, and only if you allow it.
- There is **no leaderboard, no server and no public profile**. A global ranking would need exactly the central server ONYX exists to avoid.

Your contacts see your tier and your chosen cover icon (the ONYX emblem, the /FORGE sigil, or the ring) next to your name.
