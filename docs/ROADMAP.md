# ONYX roadmap

Guiding rule: **Privacy first, no cut corners.** Tor stays the transport; the post-quantum Signal setup
(PQXDH with ML-KEM-1024, Triple Ratchet with ML-KEM-768) stays the encryption. No custom cryptography.

## v1.4.x (in progress)
- v1.4.0: contact list for 5+ contacts, compact header, your own mailbox phone ([MAILBOX.md](MAILBOX.md))
- v1.4.1: private groups of up to 8, no admin ([GROUPS.md](GROUPS.md))
- v1.4.4:
  - images re-encoded without metadata
  - 500-character messages
  - links behind a "You are leaving ONYX" notice
  - accessibility warning

## v1.5.x: handles
- **Opt-in handles:** pick a name plus a random number suffix (e.g. `raven#4821`), so people who already know your handle can find you.
  - Exact-match lookup only: no browsing, no search, no list.
  - The pool is hosted and replicated by volunteer mailbox phones; there's no server.
  - A handle expires after 8 months without renewal, then is held 30 days before anyone else can take it.
  - [HANDLES.md](HANDLES.md) will publish exactly what is stored and how it is hashed. No user data is ever on GitHub.
  - Finding someone by handle still ends in a normal pairing, marked UNVERIFIED until you compare safety numbers.
- **Forge "Keeper" points** for phones that host the handle pool. Cosmetic only, visible only to people you chat with. No leaderboard and nothing tradable.

## v1.5.5: ONYX Desktop and Black-Sphere Community
- **ONYX Desktop:** Windows, macOS and Linux. The core is plain Kotlin/JVM, libsignal ships desktop libraries, and Tor runs as a bundled binary. Start as its own identity, paired by QR like a phone; linking a desktop to a phone (multi-device) comes later.
- **Black-Sphere Community:** servers and channels like Discord, without Discord.
  - **Hosting shared out, Snowflake-style.** A community is hosted by volunteer ONYX Desktop nodes (spare phones later), not one server. Hosts replicate the community between them, so it stays up when any one host goes offline.
  - **Hosts hold only ciphertext.** Channel messages are end-to-end encrypted to the members. A host stores and forwards what it can't read, the same principle as the mailbox.
  - **A separate identity per community**, so communities can't be linked to each other or to your private contacts. Members never see each other's onion addresses.
  - **Points and tiers exist only inside each community.** Based on things that can't be faked from one phone: membership time, hosting uptime seen by the other hosts, roles. Never global, never a public ranking, never tradable.
  - No bots and no API.
  - To design: how to encrypt for hundreds of members (sender keys or MLS rather than per-member fan-out), with post-quantum protection kept; host discovery and replication; moderation without an all-powerful admin.

## v1.6 (to assess): zero-knowledge proofs
- Prove things without revealing who you are, for example "I'm a member of this community", "I hold the Flame tier" or "this handle is mine", without showing which member or which identity.
- Start point: libsignal already ships **zkgroup**, the anonymous-credential system Signal uses so its servers can't see group membership. That avoids inventing our own cryptography.
- Decide the scope: Black-Sphere only, or ONYX-wide (handles, groups).
- Research list: Zerocoin, Zerocash, zk-SNARKs (Groth16, PLONK, Halo2), zk-STARKs, Bulletproofs, ShadowCoin/ShadowCash
  (ring signatures, stealth addresses), Semaphore, zkgroup, libsodium (building blocks such as Argon2id), Obelisk (to find).
- **Decided (Oct 19):** prototype **LaZer** (IBM, lattice-based, no trusted setup) anonymous credentials for
  Black-Sphere membership and tiers. Watch **Lattice Jolt**/Akita (zero-knowledge not released yet); STARKs are
  the conservative fallback. Open: Android port of a C library, no audit yet, proving time on phones.
- **Research step: transit size and delay.** Post-quantum signatures (Falcon-512 ~666 B, ML-DSA-65 3,309 B,
  SLH-DSA-128s 7,856 B) and proofs (tens of KB) are 10–1,500× today's 64-byte signatures, and ONYX pads to fixed
  buckets. Benchmark in the emulator lab over real Tor: each frame size (16 KiB, 64 KiB, 256 KiB) × 50 sends,
  median and 95th-percentile delivery time, battery and data per 100 messages, also via mailbox and on a slow
  network. Decide from that: signature scheme for groups and handles, proofs only on joins or on every message,
  and whether a 256 KiB bucket is needed (a full 32-entry roster with ML-DSA is ~212–280 KB).
- **Post-quantum caveat:** most of these are not quantum-resistant. For membership proofs the risk is mainly future forgery,
  not unmasking past members. Any choice must say so plainly; STARK and lattice-based options get a look.

## After v1.5: assess I2P (opt-in)
- I2P built into the APK (no separate app), **off by default** and turned on per user. Tor stays the default.
- To assess before building:
  - APK size (+5–10 MB per ABI)
  - keeping a second network stack patched
  - I2P's smaller anonymity set
  - whether running two transports makes ONYX easier to fingerprint on the network

## Distribution
- Self-hosted F-Droid repo on the ONYX site, plus Obtainium (GitHub releases).
- Resubmit to F-Droid around January 2027 (after 3+ months of maintenance), following the template, with an
  honest comparison against Briar, Cwtch and SimpleX.

## Open decision
- **Discord.** No account linking or rankings on Discord: it would deanonymize users. Replaced by
  Black-Sphere Community (v1.5.5).
