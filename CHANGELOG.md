# Changelog

## v1.1.0 (pre-release, debug)
- **Added:** RAW wire view in chat. A live hex dump of the exact padded frames exchanged with a contact, with frame anatomy (bucket, payload, padding, libsignal message type). Memory-only.
- **Added:** live on-the-wire size meter while typing.
- **Added:** `FrameCodec.tap` and `WireAnatomy` in `core`, with a test proving the tap sees the exact wire bytes and no plaintext.
- **Changed:** versionCode 3, versionName `1.1.0`.

## v1.0.0-pre1 (pre-release, debug)
- First public pre-release. Tor peer-to-peer messaging, PQXDH + Triple Ratchet (libsignal), in-person QR pairing, invite links (unverified until safety-number check), Share ONYX card, disappearing messages, app lock, panic wipe, /FORGE rank with an optional Snowflake proxy.
- Change record: [`v1.0/CHANGE-RECORD.md`](v1.0/CHANGE-RECORD.md). Note: those APKs show versionName `0.1.0-forge`.
