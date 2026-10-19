# ONYX Groups (v1.4.1)

Small private group chats with **no server and no admin**.

## Why at most 8 people
- **Every message is encrypted separately to every member** over the same 1:1 Signal sessions
  (PQXDH + Triple Ratchet) you already have with each of them. Nothing new to trust, no shared group key
  that one leaked phone would expose. The cost is one encryption and one Tor delivery per member, so a
  message to 8 people is 7 deliveries. That stays fast over Tor; 50 or 500 wouldn't.
- **Everyone in a group can reach everyone else directly.** That's fine among people who know each other
  and becomes a way to collect strangers' addresses in big groups. ONYX is for people you know.
- Small groups also keep spam small: with no public directory, no bots and no API, the only way into a
  group is a voucher from someone already in it, sent to you as an invite, and you choose whether to join.

## Who's in charge: nobody
- **Any member can invite** one of their own contacts. The invite carries a **voucher**: the inviter signs
  "this onion address with this identity key may join this group". An invite is only a request: nothing about
  you is shared with the group until you tap Join. You can decline, or block the person who keeps inviting you.
- **Every member entry needs a voucher** from someone already in the group (except the creator's). Someone who
  merely learns a group's id can't add themselves, and a member can't claim to have joined before the person
  who invited them.
- **Only you can change your own membership.** Your join and your leave are each signed with your phone's
  identity key, and every phone checks that signature. Nobody can join for you, remove you, or pretend you left.
- **Anyone can leave at any time** (while connected, so the others hear about it). Leaving signs a "left"
  entry, tells the members and deletes the group and its messages from your phone.
- **The group id commits to its creator, name and verified-only setting.** Changing any of them in an invite
  changes the id, so tampered invites are rejected.

## How phones agree on who's in the group
Each phone keeps its own copy of the member list and passes it on when it changes. For each member the entry
with the highest signed version wins; at the same version a "left" beats a "joined", and after that every
phone keeps the same one (the smaller signature). Updates can arrive in any order. If more than 8 people end
up joined at once, every phone counts the same 8: the earliest join times. Entries are never evicted, so an
old "joined" can't be replayed over a later "left".

If the same onion address shows up with **two different identity keys**, the member is marked
**disputed**: no messages, introductions or images go to or come from them until you check in person. The
group's Members list shows it.

## Known limits (stated honestly)
- A member who invites can create **fake members** (each needs that member's voucher, so they're traceable to
  them) and fill free seats, pushing out people who join later. They can't push out anyone who joined before
  them. With no admin there's no one to remove them: block them, leave, and start a new group.
- The member who introduces you could lie about someone's identity key. Introduced contacts start
  **UNVERIFIED**; compare safety numbers, or use a **verified-only** group (no introductions at all).
- A member who relays an introduction can see the one-time code and could use it to block that introduction.
  A half-finished introduction is cleared and retried after a day.

## Meeting members you haven't paired with
When you join, you're paired automatically with members you don't know yet, through the group:
1. Of each two unpaired members, the one whose onion key sorts lower makes a **one-time invite code** that
   works **only** for the other member's onion address and identity key (a member passing it along can't pair
   with it as themselves).
2. The code is relayed through members who are already paired (at most 6 hops, at most 30 relayed per member
   per hour).
3. The receiving phone checks the code against the signed member list (same onion, same identity key)
   and pairs. The new contact is marked **UNVERIFIED** and named after the group until you rename it.
   Compare safety numbers with them in their chat to verify.

In a **verified-only** group there are no introductions, invites go only to verified contacts, and you only
exchange messages with members you've verified in person.

## What's sent, and to whom
| Message | Goes to | Contains |
|---|---|---|
| `GROUP_INVITE` (kind 7) | the invited contact | group id, name, signed member list |
| `GROUP_STATE` (8) | members you're paired with | the same, when it changes |
| `GROUP_TEXT` (9) | each member, separately | group id + text (500 characters, same filter as 1:1) |
| `GROUP_INTRO` (10) | members you're paired with | a one-time code bound to one member |
| `IMAGE` (11, v1.4.4) | each member, separately | group id + caption + re-encoded JPEG |

All of it travels inside the normal end-to-end encrypted, padded 1:1 messages, over Tor. Nothing is
stored anywhere except the members' phones (and, while you're offline, your own mailbox if you have one).

## Limits
- A member you haven't been introduced to yet doesn't get your messages until the introduction completes
  (both phones online once). Messages are never relayed through other members.
- Members on ONYX older than v1.4.1 can't join groups.
