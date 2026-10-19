# Changelog

## v1.5.0 (in development)
- **Added (core only):** opt-in handles design ([docs/HANDLES.md](docs/HANDLES.md)): Argon2id + BLAKE2b (checked against RFC 9106 vectors), sealed same-size records, per-record owner keys, the shared host rule with write proof of work and delete tombstones, request proof of work. No network or UI yet; a second security review comes before that.

## v1.4.4 (in development)
- **Added:** images.
  - Picked with the system photo picker; no storage permission needed.
  - Every untrusted image (picked or received) is decoded only in a fresh, isolated, permission-less process created for that image alone. It returns raw pixels, and ONYX encodes its own JPEG. Received images are checked (sender, group, 10 a minute) before any decoding, and decoded off the network thread.
  - Re-encoded on the sending phone to at most 800 px and 58 KB, which drops EXIF, GPS, camera model, timestamps, thumbnails and trailing data. The receiving phone re-encodes again and checks the size before decoding.
  - Every image is padded to exactly 60 KiB, which travels as a 64 KiB frame (measured on two phones over Tor: ~0.8 s on Wi-Fi), at most 5 a minute.
  - Images stay inside ONYX: no save-to-gallery button, and screenshots stay blocked.
  - Works in 1:1 chats and groups. New envelope kind `IMAGE` (11).
- **Changed:** messages up to 500 characters (was 80), still on one line with the same filtering. Every text message is padded to 4 KiB, so "ok" and 500 characters look identical.
- **Added:** links.
  - http(s) to an ASCII host name only, at most 3 per message.
  - Tracking parameters (utm_*, fbclid, gclid…) are removed.
  - Disguised links (`real.com@evil.example`) and look-alike letters are not treated as links.
  - Never previewed or fetched. Tapping one shows "You are leaving ONYX" with the full host, plus Open / Copy link (for Tor Browser) / Stay.
- **Added:** accessibility warning.
  - When any accessibility app is on, the home screen lists it and explains that it can read the screen (decrypted messages included) and act for you. "Review" opens Android's accessibility settings; "I trust these" hides the warning until the set of apps changes. Settings → Security shows the same list.
  - On Android 14+, every ONYX screen is marked accessibility-data-sensitive, so only declared accessibility tools (screen readers like TalkBack) can read it.
- **Changed:** delivery to up to 4 contacts in parallel. An offline contact no longer delays everyone else; group invites had been taking 7–15 minutes behind offline contacts.
- **Added:** warm links. A contact's connection stays open for 45 s after a message, so replies skip Tor's 2–3 s connection setup (measured: 0.7 s instead of 2.2 s).
- **Changed:** this version also accepts v1.5's leaner wire sizes (each plaintext size plus 2 KiB), so v1.5 can switch sending without breaking anyone. Measured today: ~1 KB of encryption overhead pushes every frame into a 4× larger size.
- **Added (developer):** Settings → Developer → transit benchmark. It measures real delivery time over Tor for 16 KiB, 64 KiB and 256 KiB padding-only frames between two paired phones, plus Android's own battery and data readings. It's off by default; the receiving side must turn on "Accept benchmark", which lasts only until restart. Nothing is stored. New frame type `BENCH` (0x30).
- **Changed:** the mailbox keeps a small index and caps storage at 300 MB in total and 30 MB per sender, and never loads every item into memory.

## v1.4.1 (in development)
- **Added:** private groups of up to 8 people, with no admin and no server ([docs/GROUPS.md](docs/GROUPS.md)). The group id commits to its creator, name and verified-only setting; every member needs a signed voucher from whoever invited them; an onion claimed with two keys is marked disputed and excluded. Any member can invite their own contacts, and invites wait until you join or decline. Your join and leave are signed with your own identity key, so nobody can add you, remove you, or fake that you left. Every message is encrypted separately to each member over the existing 1:1 sessions.
- **Added:** automatic introductions between members who haven't paired. The one-time code works only for the intended member, is relayed through members who are already paired, and the new contact starts UNVERIFIED. Verified-only groups turn introductions off.
- **Added:** envelope kinds `GROUP_INVITE` (7), `GROUP_STATE` (8), `GROUP_TEXT` (9) and `GROUP_INTRO` (10). The stored message format gains a sender field (v2; v1 messages still read).

## v1.4.0 (in development)
- **Added:** contact list for 5+ contacts: search by name (on the phone only), sort by most recent conversation or A–Z, unread counts, "online" dot (a frame exchanged in the last 5 minutes; kept in memory only).
- **Added:** long-press a contact to rename, archive or block. Archived chats return when a new message arrives. Blocked contacts' messages are dropped silently (still acknowledged, so they learn nothing) and sending to them is refused.
- **Added:** **mailbox** ([docs/MAILBOX.md](docs/MAILBOX.md)): a spare phone you own, in mailbox mode, holds encrypted messages while your main phone is offline. Senders fall back to it when direct delivery fails; the owner collects every minute. It sees only an opaque per-sender tag, time and size; items are deleted when collected or after 14 days. New frames `FETCH`, `MAILBOX_ITEM`, `MAILBOX_DONE`, `MAILBOX_KEYS` and envelope kind `MAILBOX` (6).
- **Changed:** background notices (badge, timer, mailbox) stop retrying after about 2 days; your messages still never give up.
- **Changed:** compact home header: smaller emblem beside the connection status, so conversations start higher on the screen.
- **Storage:** list state (recency counter, unread, archived, blocked) lives in a new encrypted `contact-meta` namespace; the stored contact format is unchanged.

## v1.3.0 (pre-release, release-signed)

### F-Droid validation follow-up (2026-10-18)

- **Documented:** all seven Android permissions, their code paths, prompts, optional features, denial behavior, and privacy boundaries. The follow-up does not add manifest permissions. See [full release notes](v1.3/RELEASE-NOTES.md#android-permissions-purpose-timing-and-user-control).
- **Fixed:** tracked the Android resources required by clean builds; anchored the private `/Res/` ignore rule at the repository root; removed ABI filters conflicting with Gradle splits.
- **Fixed:** release signing preserves ZIP alignment and omits legacy JAR/v1 signing metadata. Corrected APKs use source commit `057ea6f6c220e0d03c5fd96ebc68f30a165c5ac5`; the existing `v1.3.0` tag has not been moved.
- **Verified:** F-Droid [pipeline #2960451922](https://gitlab.com/null-premise-group/fdroiddata/-/pipelines/2960451922) passed, including all three ABI builds, signature-copy reproducibility, and APK scanning. The [upstream MR](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/53119) tracks inclusion.
- **Updated:** release download list, current SHA-256 checksums, and signing-certificate instructions. Earlier alignment variants and v1.2.4 files remain historical assets; their old PGP/checksum files do not cover the corrected APKs.

### Original v1.3.0 changes
_First release-signed build, and the version submitted to F-Droid._
- **Changed (breaking):** application ID is now `io.github.sirrogersjackblood.onyx` (was `io.github.proteu5.onyx`), as F-Droid requires an ID the publisher owns. Android treats it as a new app: install it fresh, then pair contacts again. The old app can be uninstalled afterwards.
- **Added:** release signing with a dedicated key (developer builds; F-Droid builds without it), R8 shrinking, per-ABI version codes (`10 × base + 1/2/3` for armeabi-v7a / arm64-v8a / x86_64, universal `10 × base`), `-PonyxAbi=` to build a single ABI.
- **Added:** reproducible-build settings (no VCS info file, no ART baseline profile) so F-Droid can publish the developer-signed APKs.
- **Changed:** dependencies only from Google Maven and Maven Central (Guardian Project repo removed).
- **Added:** F-Droid recipe (`fdroid/io.github.sirrogersjackblood.onyx.yml`), store screenshots and changelogs in `fastlane/`.
- **Changed:** release APKs are about 40 MB instead of 230 MB: native libraries stripped (NDK 30.0.16248370, pinned), and libsignal's test library and desktop (macOS/Windows) libraries no longer packaged.
- **Changed:** versionName `1.3.0`, base versionCode 8 (APKs 80–83).
- **Tests:** emulator lab 65/65 on the release build (Android 11, 13, 14, 15, 16).
- Change record: [`v1.3/CHANGE-RECORD.md`](v1.3/CHANGE-RECORD.md).

## v1.2.4 (pre-release, debug)
_Reliability release. Three bugs found by the new emulator lab, all fixed and re-tested: 65/65 checks on Android 11, 13, 14, 15 and 16._
- **Fixed:** crash at first launch on Android 13 and 14 phones with no screen lock: the keystore there refuses lock-bound keys (`setUnlockedDeviceRequired`) without a PIN, pattern or password.
- **Fixed (data loss):** Android 12-14 permanently erase lock-bound keys when the screen lock is removed, which made every message unreadable and crashed ONYX on each launch. On those versions the vault key now stays in StrongBox/TEE but is not lock-bound; v1.2.x keys are re-wrapped on the next start. Android 11 and 15+ keep the lock binding, and follow the lock being added or removed.
- **Fixed:** native crash (SIGABRT in Tor) when Tor had to restart, for example after Android restricted ONYX in the background, or reopening ONYX after "Stop ONYX". Tor's core can run only once per process, so ONYX now restarts its process cleanly instead of starting Tor twice.
- **Added:** if Android has already erased the key, ONYX resets cleanly and explains why once, instead of crash-looping.
- **Added:** Settings → Security shows a red **NO SCREEN LOCK** warning with a button to set one, and says when the key is not tied to the lock.
- **Tests:** emulator lab (`scripts/emulator-lab.ps1`): 5 Android versions × 13 checks (install, Tor bootstrap, rotation, dark mode, offline, Doze, memory pressure, restricted background, process death, screen lock added and removed), crash traces saved.
- **Changed:** versionCode 7, versionName `1.2.4`. Release tags use `v1.2.4` style from now on (F-Droid-compatible).
- Change record: [`v1.2/CHANGE-RECORD-1.2.4.md`](v1.2/CHANGE-RECORD-1.2.4.md).

## v1.2.3 (pre-release, debug)
_Final pre-release before the beta. Includes everything from v1.2.1; the interim 1.2.2 test build was not published._
- **Added:** CREDITS on the About screen: design, direction & QA by SirRogersJackBlood; development by Claude (Anthropic), AI pair-programmer; dependencies; an invitation for independent security review.
- **Added:** `SECURITY.md` (private reporting via GitHub Security Advisories). Credits updated in `ABOUT.md`, the website About page and FAQ.
- **Changed:** versionCode 6, versionName `1.2.3`. Website download info now shows 1.2.3.
- Change record: [`v1.2/CHANGE-RECORD-1.2.3.md`](v1.2/v1.2.3/CHANGE-RECORD-1.2.3.md).

## v1.2.1 (pre-release, debug)
_v1.2.0 was skipped; its changes ship here._
- **Added:** origin story and "A note on messages" in the app About screen, on the website (/ORIGIN section + /docs/about) and in `ABOUT.md`.
- **Security:** new `core/MessagePolicy`. Messages are limited to 80 characters on one line; code-shaped symbols, consecutive symbols, control, invisible and bidi characters are stripped; look-alikes are NFKC-normalized. Enforced on send **and** receive.
- **Security:** outgoing rate limit (20 per minute per contact, 0.7 s minimum gap).
- **Policy:** no attachments, now or planned. Removed from the roadmap.
- **UI:** single-line input with an 80-character limit; notice when symbols were removed.
- **Tests:** 28/28 in `core` (5 new: injection payloads, normal chat, invisibles and look-alikes, 80-code-point truncation, rate limit).
- **Added:** version tracking on the About screen: installed version and build number (read from the package itself), build type, signing-certificate SHA-256 to compare with release notes, and a version history.
- **Changed:** versionCode 5, versionName `1.2.1`.
- Change record: [`v1.2/CHANGE-RECORD.md`](v1.2/CHANGE-RECORD.md).

## v1.1.0 (pre-release, debug)
- **Added:** RAW wire view in chat. A live hex dump of the exact padded frames exchanged with a contact, with frame anatomy (bucket, payload, padding, libsignal message type). Memory-only.
- **Added:** live on-the-wire size meter while typing.
- **Added:** `FrameCodec.tap` and `WireAnatomy` in `core`, with a test proving the tap sees the exact wire bytes and no plaintext.
- **Changed:** versionCode 3, versionName `1.1.0`.
- **Verified:** interoperates with v1.0.0-pre1; same signing certificate (`f9eb3fcb…5165`), so it upgrades in place.
- Change record: [`v1.1/CHANGE-RECORD.md`](v1.1/CHANGE-RECORD.md) · notes: [`v1.1/RELEASE-NOTES.md`](v1.1/RELEASE-NOTES.md).

## v1.0.0-pre1 (pre-release, debug)
- First public pre-release. Tor peer-to-peer messaging, PQXDH + Triple Ratchet (libsignal), in-person QR pairing, invite links (unverified until safety-number check), Share ONYX card, disappearing messages, app lock, panic wipe, /FORGE rank with an optional Snowflake proxy.
- Change record: [`v1.0/CHANGE-RECORD.md`](v1.0/CHANGE-RECORD.md). Note: those APKs show versionName `0.1.0-forge`.
