# How it works

This page explains ONYX without the mathematics. The [Cryptography](cryptography) page covers the same flow in full detail.

## Your identity is a key, not a number

On first launch ONYX generates:

- an **identity key pair**, which is who you are to your contacts;
- an **onion service key**, which gives your phone its own address inside the Tor network (a 56-character `.onion` name);
- a **vault key**, which encrypts everything stored on the phone and is itself sealed inside the phone's secure hardware.

None of these ever leave your phone except the public halves, and only to people you pair with.

## Pairing: one QR code, face to face

When you tap **Show my code**, ONYX creates a QR code containing:

- your onion address, which says where to reach you;
- a **fingerprint of your identity key**, which says who you are;
- a **one-time secret**, which proves the scanner is standing in front of you;
- an **expiry time** ten minutes away.

The other phone scans it, connects to your onion address through Tor and proves it knows the secret. Your phone replies with its public keys. The scanner then checks those keys against the fingerprint from the QR code. If anything in between tampered with them, the fingerprints won't match and pairing stops.

Because this happens in person, your contact is **verified from the first message**. With other messengers, verifying safety numbers is an optional extra step that most people skip.

## Not in the same room: invite links

**Show my code → Create & share invite link** sends a one-time link through any app you choose.

- It works **once** and expires after **24 hours**. You can revoke unused links at any time.
- Your contact pastes it into ONYX (**Scan code → Connect with invite**), or taps it if their app makes it clickable. ONYX only fills in the code; it never connects until they tap.
- The contact is marked **UNVERIFIED** on both phones. The link travelled through another app, so ONYX can't know it wasn't swapped on the way.
- To verify, open **Safety #** in the chat and read the 60 digits to each other on a voice call or in person. If every digit matches, tap **They match · verify**.

## Sending a message

Messages are **short and plain**: up to 80 characters on one line, letters, numbers, emoji and basic punctuation. There are no attachments. See [FAQ](faq#why-only-80-characters-and-no-attachments).


1. ONYX pads your message to a fixed size so its length doesn't leak.
2. It encrypts it with the Signal Protocol session you share with that contact.
3. It opens a Tor connection to their onion address, and both phones prove they share a pairing key.
4. The encrypted message is sent in a fixed-size frame. Their phone stores it and acknowledges it.
5. If their phone is offline, the message waits in your outbox and retries with randomised back-off.

## See it yourself: the RAW view

Tap **RAW** in any chat to split the screen. The lower half shows, live, the exact bytes that cross the link for that conversation: what someone who managed to break Tor's encryption would capture.

- **▲ OUT / ▼ IN**: each frame, its type, and its size on the wire (usually exactly 1,028 bytes for a normal message).
- **Anatomy**: the bucket size, how many bytes are real payload versus zero padding, and whether it's a first (PQXDH) message or a normal Triple Ratchet message.
- **Hex dump**: the start of the frame. Past the small header it's ciphertext, then zeros.
- **While you type**, a meter shows how big your draft will be on the wire. "ok" and a full 80-character message produce the same size.

![Two phones side by side, both with the RAW view open under the chat, showing 1,028-byte frames that are mostly zero padding](/screenshots/11-raw-wire-view.jpg)

The RAW view is memory-only. It is never saved to disk, holds only ciphertext and padding, and is wiped by panic wipe.

## What people can see

| Who | What they see |
|---|---|
| Your mobile carrier or Wi-Fi | That you use Tor. Not who you talk to, not when, not how much. |
| Tor relays | Encrypted, fixed-size cells. Neither end's IP address. |
| Your contact | Your messages to them. Never your IP address or phone number. |
| Someone who finds your .onion address | A connection that closes immediately. They cannot even confirm it's ONYX. |

## Disappearing messages

Set a timer per conversation (5 minutes to 1 week). Both phones delete expired messages, and the database overwrites freed space so deleted text doesn't linger on disk.

## Panic wipe

**Settings → Panic wipe** destroys the hardware-held master key first, which makes every stored byte unreadable instantly. It then deletes the database and your onion identity.
