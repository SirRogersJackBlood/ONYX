# Change Record: CR-ONYX-2026-002

| Field | Value |
|---|---|
| **Title** | ONYX v1.1.0: RAW wire view + version bump |
| **Type** | Normal change: feature + release |
| **Date** | 2026-09-26 |
| **Requested by** | SirRogersJackBlood |
| **Previous release** | v1.0.0-pre1 (CR-ONYX-2026-001) |
| **Status** | Code complete: awaiting build, test and publish |

## Scope

1. **RAW wire view** in chat: a live hex dump of the padded frames exchanged with a contact, plus a live on-the-wire size meter while typing.
2. `core`: `FrameCodec.tap` (observes exact wire bytes), `WireAnatomy` (frame description + hex dump).
3. `app`: `net/WireTap.kt` (memory-only capture, 120 frames per contact); tap wired into `PeerClient.open` and into `PeerServer` after link authentication; cleared on panic wipe.
4. Version: versionCode 3, versionName `1.1.0`.

## Privacy review

- [x] Captures only post-encryption bytes: libsignal ciphertext inside padded frames. No plaintext path to the tap (covered by a unit test).
- [x] Never written to disk; bounded ring buffer; cleared on panic wipe and on process death.
- [x] Shown on screens that already use `FLAG_SECURE` (no screenshots or recents thumbnails).
- [x] Server side starts capturing only **after** link authentication, so anonymous probes are never attributed to a contact.
- [x] The size meter does arithmetic only. Drafts are never encrypted, because that would advance the ratchet.

## Test evidence

- `core` unit tests: **23/23 pass** (new: `wireTapSeesExactPaddedBytesAndNoPlaintext`).
- [ ] `.\gradlew :app:assembleDebug` succeeds
- [ ] Redmi Note 8 ⇄ Galaxy S25 Edge: send and receive; RAW shows ▲ OUT / ▼ IN frames with 1028-byte wire size for short texts
- [ ] Typing updates the size meter; long text moves to the next bucket
- [ ] Panic wipe clears the RAW view

## Release steps

1. Build: `.\gradlew :app:assembleDebug`
2. Move the APKs from `app\build\outputs\apk\debug\` into `v1.1\` as `onyx-v1.1.0-<abi>-debug.apk`
3. Record hashes in `v1.1\SHA256SUMS.txt` and paste them into `RELEASE.md`
4. Commit and push; create GitHub pre-release `v1.1.0`; attach the APKs and `SHA256SUMS.txt`

## Rollback

Reinstall the v1.0.0-pre1 APKs (same debug key, so data is kept). Code rollback: revert the v1.1 commit. No data migration is involved.
