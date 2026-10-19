# ONYX Handles (v1.5): design and threat model

**Status: design for review. Core code (`core/.../Handles.kt`, `Argon2.kt`) is written and unit-tested; the
network protocol, pool hosting and app screens are not built yet.**

Handles are **opt-in**. Without one, ONYX works exactly as before: you pair in person by QR or with an invite
link. A handle lets someone who already knows it (because you told them) send you a request to connect.
There is **no search, no browsing and no list**. Lookup is exact-match only.

**No handle or user data is ever stored on GitHub.** GitHub holds this description and the code. Records
live only on volunteer phones that chose to host the pool.

## 1. What a handle looks like
`raven.4821`: a name you choose (3–20 letters a–z, digits or `_`) plus a number from 10 to 9999 that **the
app picks at random**. The official app doesn't let you choose the number. A modified app could register any
free number, so **a handle is first come, first served and never proves who someone is**: whoever you reach
through a handle starts UNVERIFIED until you compare safety numbers.
Names are NFKC-normalized and lower-cased; letters from other scripts are rejected, so a Cyrillic "а" can't
pass for a Latin "a". If the random number is taken, the app picks another.

## 2. How a handle is hashed and published

```text
h        = NFKC(lowercase(handle))                                   "raven.4821"
seed     = Argon2id(h, salt = "ONYX-pool-v1", 64 MiB, 3 passes, 1 lane, 32 bytes)   slow on purpose
recordId = HKDF-SHA256(seed, "ONYX-pool-v1", "record-id", 32)        what pool hosts index by
key      = HKDF-SHA256(seed, "ONYX-pool-v1", "record-key", 32)       only someone who knows the handle can derive it

card     = { handle, request-inbox onion key, identity key, expiry day,
             signature by the identity key over all of that }
sealed   = nonce(12) ‖ AES-256-GCM(key, aad = "onyx-handle-v1" ‖ recordId, pad(card, 256 B))
           always 284 bytes, whoever's card it is

entry    = { recordId, ownerKey, version, expiryDay, sealed,
             signature by ownerKey,
             proof of work over (recordId, ownerKey, version), 22 bits } what hosts store
```

- **Argon2id** (RFC 9106) costs about half a second and 64 MiB per guess on a phone. It slows guessing; it
  does not make a handle secret. Someone who knows your name part has at most 9,990 numbers to try (about
  1.5 hours on one phone, far less on a server farm). The salt is the same for everyone (it has to be, so that
  anyone who knows a handle can find it), so a host or anyone else can precompute a dictionary of common names ×
  every number and link and read those records. **Treat a handle like a phone number you've chosen to publish.**
- **ownerKey** is a fresh signing key made for this record and kept on your phone. It is **not** your identity
  key, so hosts can't link a record to you or to your other activity.
- The card inside is signed by the **identity key it names**. That proves the card was published by whoever
  holds that key, nothing more; it doesn't prove that key is the person you have in mind.

## 3. Who sees what

| Who | Sees | Can't |
|---|---|---|
| Pool host (an opted-in mailbox phone) | record ids, same-size encrypted blobs, owner keys, expiry days, roughly when lookups happen, and the contents of any record whose handle it guesses or has in a dictionary | read records for handles it can't guess, list handles, or forge a record's signature. It **can** refuse to serve a record, serve an old one, or serve a different record it accepted first (section 5 says how lookups guard against that) |
| Someone who knows your exact handle | your request-inbox onion and identity key, so they can send a request | see your main onion address, IP, contacts or anything else |
| Someone guessing handles | whether a guess exists, at ~0.5 s and 64 MiB per guess | browse or bulk-download the directory without guessing |
| GitHub / the ONYX project | this document and the code | anything about any user |

## 4. Requests
The **request inbox** is a separate onion address used only for "wants to connect" requests. It is never
your main address. A request carries a **proof of work** (SHA-256, 20 leading zero bits: about a second on a
phone) bound to that inbox, the day **and that specific request** (its digest covers the sender's one-time
key), so one solution can't be reused; the inbox also refuses a solution it has already seen that day, and
rate-limits. 20 bits is cheap for a GPU, so the proof of work is a speed bump, not a wall; rate limits and
"accept or ignore" do the rest. You accept or
ignore each request; accepting starts a normal pairing, and the contact is **UNVERIFIED** until you compare
safety numbers, like invite links today.

## 5. Renewing, expiring and the 30-day hold
- ONYX renews your record about once a month while it runs. Renewing bumps `version` and pushes `expiry` out
  to 8 months (243 days).
- A record not renewed for 8 months is **purged** by every host. Its id is then **held 30 days**: during
  that time only the old owner key can bring it back, and only with a higher version, so nobody can grab a
  freed handle the moment it lapses. An expired record that hasn't been swept yet counts as held too.
- **Deleting** works the same way: the host keeps a 30-day hold at the delete's version, so an old signed copy
  of the record can't be replayed to bring it back.
- Only the owner key can update or delete a record. A host stores an incoming entry only when:
  1. its proof of work (22 bits, several seconds on a phone) checks out, so filling hosts with junk costs real work;
  2. the signature matches the owner key;
  3. the id is free, or held for that same owner key at a lower version, or the owner key matches and the
     version is higher;
  4. the expiry is not in the past and no more than 8 months away.

  Every host applies the same rule (`Handles.decide`). Hosts also cap how many records they keep.
- **Hosts can disagree.** A host that never saw your record accepts the first owner key it's shown for that
  id. So a lookup asks **at least 3 hosts** and only trusts a record they all return with the same owner key;
  the app also remembers the owner key it first saw for a handle and warns if it ever changes.
- **If you lose your owner key** (phone lost, app wiped) you can't renew or delete your record; it expires after
  8 months and you can register a new handle.
- Contacts are tied to identity keys, not handles. A handle taken over later can't hijack existing contacts;
  if a looked-up handle's identity key differs from a contact you already have, ONYX warns you.

## 6. Who hosts the pool
Volunteers' **mailbox phones** (always on, on a charger) can tick "Help host the handle pool". There is no
ONYX company server. Each record is stored on several hosts. A new host learns other hosts from its own
contacts' mailboxes that also host; a short list of seed hosts in the repo (volunteer host onions only, never
users) lets the first hosts find each other. Hosting earns cosmetic **Keeper** Forge points, counted on your
own phone and shown only to people you chat with.

## 7. Known limits (stated honestly)
- Someone who guesses your exact handle can send you a request. That's the point of a handle; the proof of work
  and rate limits keep it from becoming spam.
- Short or common handles can be guessed; Argon2id slows guessing, it doesn't stop it.
- Hosts learn roughly when lookups happen, not who looked up whom (lookups arrive over Tor).
- A malicious host can refuse to serve a record, serve an old version, or serve a different record it
  accepted first. Asking several hosts and pinning the owner key covers that; it can't forge a signature.
- Hosts and dictionary attackers can link and read records for common or guessed handles (section 2).
- **Not quantum-resistant:** the owner and identity signatures (XEdDSA/Curve25519) could be forged by a future
  quantum computer, which would let it overwrite records. The encryption (AES-256, keys from Argon2id) is not
  the weak point. This is tracked with the v1.6 post-quantum review.

## 8. Implementation status
| Part | State |
|---|---|
| Argon2id + BLAKE2b (`core/Argon2.kt`) | Written; matches RFC 9106 vectors (d, i, id) and the reference 64 MiB vector |
| Handle rules, records, host rule (with put proof of work and delete tombstones), sweep, request proof of work (`core/Handles.kt`) | Written; unit-tested |
| Pool wire protocol (put / get / replicate) | Not built |
| Request inbox (second onion), requests UI | Not built |
| Host mode, seed list, Keeper points | Not built |
| Security review of this design | Done once (Oct 19): tombstones on delete, proof of work on writes, request-bound proof of work, multi-host lookups and these corrections came from it. A second review before the network code ships. |
