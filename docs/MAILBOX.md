# ONYX Mailbox (v1.4.0)

ONYX has no servers. When your phone is offline, a message to you used to wait on the sender's phone
until both of you were online at the same time. A **mailbox** fixes that without adding a server:
it is a spare phone **you own**, running ONYX in mailbox mode, that stays online and holds
encrypted messages until your main phone collects them.

## Setting it up
1. On the spare phone (no contacts yet): Settings → Mailbox → **Use THIS phone as a mailbox**.
2. On your main phone: Settings → Mailbox → **Pair my mailbox**, and scan the code the mailbox shows
   (Show my code). Pair in person, like any contact.
3. Your main phone tells each of your contacts, inside the encrypted chat, where to leave messages.

The mailbox pairs with exactly one owner (the first phone that pairs with it) and refuses everyone after that.

## What the mailbox can and can't see
| The mailbox sees | The mailbox never sees |
|---|---|
| An opaque 16-byte tag per sender | Who the sender is (no names, no onion addresses) |
| When an item arrived and how big it is (padded) | Any message content: everything is Signal ciphertext for the owner |
| How many items are waiting | Your contact list or chat history |

Senders connect to the mailbox over Tor, so it doesn't learn their IP either.

## How it works
- For each contact, both phones already share a pairing-derived **link key**. From it, each side derives
  - `depositKey = HKDF(linkKey, salt "onyx-mailbox-v1", info "deposit", 32 bytes)`
  - `tag = HKDF(linkKey, salt "onyx-mailbox-v1", info "tag", 16 bytes)`

  Neither value reveals the link key, and they differ per contact.
- Every minute the owner sends the mailbox the list of `(tag, depositKey)` pairs (`MAILBOX_KEYS`, at most 1,000),
  then asks for waiting items (`FETCH`). Blocked contacts are left out, so they can't fill your mailbox.
- A sender whose direct delivery fails connects to the mailbox's onion and proves the deposit key
  (the same challenge–response used for normal links). The mailbox stores the ciphertext under the tag and ACKs.
- The owner receives each item (`MAILBOX_ITEM`), decrypts and stores it, then ACKs. Only then does the mailbox delete it.
  `MAILBOX_DONE` ends the batch. An item that can't be decrypted yet is kept, not lost.
- Retention: deleted when collected, or after **14 days**. Limits: 500 items per sender, 5,000 in total.
- Contacts learn your mailbox through pairing (`mailboxPk`) or the encrypted `MAILBOX` notice (kind 6), which
  is also sent with an empty body when you remove it.

## Limits and honest caveats
- The mailbox is only as available as that phone: keep it charged, on Wi-Fi, with ONYX running.
- Someone who takes the mailbox phone can see the tags, timing and sizes above, never content. Use a screen lock on it.
- Older ONYX versions (before v1.4.0) don't understand the `MAILBOX` notice. They keep delivering directly as before.
- Read receipts are still not sent, so a sender sees "sent" (via mailbox) rather than "delivered".
