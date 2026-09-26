# FAQ

## Why no phone number?

A phone number is a real-world identifier tied to a SIM, a carrier account and usually your legal name. Every messenger that uses one can be asked who owns it. ONYX identifies people by a key exchanged face to face, and that key is linked to nothing else.

## Can I add someone remotely?

Yes, with an **invite link**: **Show my code → Create & share invite link**. It works once, expires in 24 hours and can be revoked. Because it travels through another app, the contact starts **unverified**. Compare safety numbers on a voice call or in person, then mark them verified. See [How it works](how-it-works#not-in-the-same-room-invite-links).

## Why do both phones need to be online?

Messages go directly from phone to phone, with no server in between to hold them. If your contact is offline, the message waits in your outbox and is delivered when they're back. A **mailbox mode**, where an always-on device of theirs (an old phone on Wi-Fi) holds messages, is on the roadmap.

## Does ONYX drain the battery?

Keeping Tor and an onion service running costs more than an app that sleeps until a push notification arrives, but ONYX uses no push service by design. Expect noticeably more drain than Signal. Snowflake only runs while charging.

## Is it really post-quantum?

Message encryption is. It uses libsignal's PQXDH (Kyber-1024) and SPQR (ML-KEM-768), the same as Signal. The Tor circuits underneath are not post-quantum yet; see [Cryptography](cryptography#what-is-not-post-quantum-yet).

## Why not just use Signal?

Signal's encryption is excellent, and ONYX uses the same library for it. The difference is metadata: Signal needs a phone number and routes everything through its servers. If that's acceptable to you, Signal is more mature and easier to use. ONYX is for when it isn't.

## Why not Briar, Cwtch or SimpleX?

They're excellent and influenced ONYX. Briar and Cwtch also run over Tor; SimpleX uses relays without user IDs. ONYX's combination is Signal's post-quantum Triple Ratchet, Tor peer-to-peer, mandatory in-person pairing with an identity commitment, and an opt-in way to help the Tor network.

## Is the Snowflake proxy safe to run?

It carries other people's already-encrypted Tor traffic *into* Tor, is never an exit, and doesn't touch your messages. Your IP is visible to the Snowflake broker and the users you help. Read [Tor & Snowflake](tor-and-snowflake) before turning it on.

## Who can see my Forge rank?

Only people you chat with, inside the encrypted session, and only if you enable sharing. There is no leaderboard.

## What happens if I lose my phone?

Your identity and messages are only on that phone and there's no cloud backup, on purpose. You'd pair with your contacts again. If the phone was locked, the data is protected by the hardware-held key.

## What's on the roadmap?

- Mailbox mode (offline delivery via your own always-on device)
- Tor client authorization and a separate onion address per contact
- Bridges for networks that block Tor
- SMS fallback (opt-in, clearly labelled as leaking metadata)
- Nearby transport (Bluetooth / Wi-Fi Direct)
- Attachments (chunked and padded)
- F-Droid release with reproducible builds
- Hardware-isolated vault on devices that support protected VMs

## Who makes ONYX?

Design by **SirRogersJackBlood**. The source is AGPL-3.0: read it, build it, audit it.
