# Change Record: CR-ONYX-2026-003

| Field | Value |
|---|---|
| **Title** | ONYX v1.2.1: abuse-resistance hardening + About/origin (v1.2.0 skipped) |
| **Type** | Normal change: security hardening |
| **Date** | 2026-09-26 |
| **Requested by** | SirRogersJackBlood |
| **Previous release** | v1.1.0 (CR-ONYX-2026-002) |
| **Status** | Code complete: awaiting build, test and publish |

## Rationale
Prevent ONYX from being repurposed as an automated or API-driven covert channel, or as a carrier for code and injection payloads. Keep it a short-message, human-to-human chat.

## Scope
1. `core/MessagePolicy.kt` (new): `sanitize()` (NFKC, strip controls, invisibles and bidi, one line, allowlist of letters, digits, emoji and `. , ! ? ' " - : ( )`, no consecutive symbols, ≤ 80 code points) and `rateAllowed()` (20 per minute, 0.7 s gap).
2. `Messenger.sendText`: sanitize, then rate-limit, then send exactly the sanitized text.
3. `Messenger.handleIncoming`: re-sanitize received TEXT (body capped at 1 KiB before decoding); drop if empty (still ACKed so senders stop retrying).
4. `ChatActivity`: single-line input with an 80-character `LengthFilter`; user feedback when symbols were removed or the rate limit hit.
5. Docs: threat model "Abuse resistance", FAQ, protocol, how-it-works; attachments removed from the roadmap.
6. Version: versionCode 5, versionName `1.2.1` (v1.2.0 skipped by decision; never released).
8. About: version tracking (versionName and versionCode via PackageManager, debug/release flag, signing-cert SHA-256 via GET_SIGNING_CERTIFICATES, in-app version history).
7. About/origin: story proofread and published in the app About screen, the website (/ORIGIN section, /docs/about, "About" in the nav) and `ABOUT.md`.

## Compatibility
- Wire protocol unchanged. v1.2 ⇄ v1.0/v1.1 still interoperate.
- Older clients can still *send* long or symbol-heavy text, but v1.2 sanitizes it on receipt.
- Existing stored messages are not rewritten.

## Test evidence
- [x] `core` unit tests **28/28** (new: `policyNeutralisesInjectionPayloads`, `policyKeepsNormalConversation`, `policyStripsInvisiblesAndLookalikes`, `policyTruncatesTo80CodePoints`, `rateLimitStopsBulkSending`)
- [ ] `.\gradlew :app:assembleDebug` succeeds
- [ ] Typing past 80 characters is blocked; newline cannot be entered
- [ ] Sending `<?php echo 1; ?>` arrives as `?php echo 1?` on the other phone
- [ ] Sending 21 messages rapidly: the 21st is refused with "Slow down"
- [ ] v1.1 → v1.2: a long or symbol-heavy message from v1.1 is shown sanitized on v1.2

## Release steps
`.\gradlew :app:assembleDebug` → `.\scripts\stage-release.ps1 -Version 1.2.1` → hashes and signer into release notes → push → GitHub pre-release `v1.2.0`.

## Rollback
Reinstall v1.1.0 (same key, data kept; Android may require `adb install -r -d` to downgrade versionCode 5 → 3) or revert the v1.2 commit. No data migration.
