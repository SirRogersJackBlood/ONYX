# Change Record: CR-ONYX-2026-002

| Field | Value |
|---|---|
| **Title** | ONYX v1.1.0: RAW wire view + version bump |
| **Type** | Normal change: feature + release |
| **Date** | 2026-09-26 |
| **Requested by** | SirRogersJackBlood |
| **Previous release** | v1.0.0-pre1 (CR-ONYX-2026-001) |
| **Status** | Built, tested, staged: awaiting upload to GitHub Releases |

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
- [x] `.\gradlew :app:assembleDebug` succeeds (versionCode 3, versionName 1.1.0 per output-metadata.json)
- [x] Redmi Note 8 ⇄ Galaxy S25 Edge: send and receive with the RAW view (confirmed by SirRogersJackBlood)
- [x] **Cross-version:** v1.0.0-pre1 ⇄ v1.1.0 interoperate (wire protocol unchanged)
- [ ] Typing updates the size meter; long text moves to the next bucket
- [ ] Panic wipe clears the RAW view

## Release steps

1. Build: `.\gradlew :app:assembleDebug`
2. Move the APKs from `app\build\outputs\apk\debug\` into `v1.1\` as `onyx-v1.1.0-<abi>-debug.apk`
3. Record hashes in `v1.1\SHA256SUMS.txt` and paste them into `RELEASE.md`
4. Commit and push; create GitHub pre-release `v1.1.0`; attach the APKs and `SHA256SUMS.txt`

## Artifacts (staged in `v1.1/`, hashes independently re-verified)

| File | Size | SHA-256 |
|---|---|---|
| `onyx-v1.1.0-arm64-v8a-debug.apk` | 238.5 MB | `d75dcc0b6f45e6aab3ddaed1d782534dfc6a403b0f5a338fb51a8f9dc335947a` |
| `onyx-v1.1.0-armeabi-v7a-debug.apk` | 222.7 MB | `5b2cb883d4668799d03e78a32373a3d5dd4b9dbab7bccfc8e47a6d4e673083d3` |
| `onyx-v1.1.0-x86_64-debug.apk` | 244.6 MB | `66b567e1c024cb6e1809c9411ecf1733db02e9f8b4819a06102c5b74750b948b` |
| `onyx-v1.1.0-universal-debug.apk` | 607.7 MB | `802b5556e93887b58c6e21feabfe8d17e4e35ee9bba8be7755a35f00e8fb9bd2` |

Signer (APK Signature Scheme v2, all four): `CN=Android Debug, O=Android, C=US` · SHA-256 `f9eb3fcbbc2115fb3b5dcd67e842e62d80c4c2afa85dd4c5401dbdcca2ee5165`, identical to v1.0.0-pre1, so in-place upgrade keeps data.

## Publish checklist
- [x] Release notes: `v1.1/RELEASE-NOTES.md` (also copied to `RELEASE.md`)
- [ ] GitHub pre-release `v1.1.0` created; APKs + `SHA256SUMS.txt` attached
- [ ] Published asset hashes match `SHA256SUMS.txt`
- [ ] Site `manifest.json` shows 1.1.0 after the push (Railway redeploy)

## Rollback

Reinstall the v1.0.0-pre1 APKs (same debug key, so data is kept). Code rollback: revert the v1.1 commit. No data migration is involved.
