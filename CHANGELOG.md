# Changelog

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
