# Change Record: CR-ONYX-2026-004

| Field | Value |
|---|---|
| **Title** | ONYX v1.2.3: credits on About, SECURITY.md (final pre-release before beta) |
| **Type** | Standard change: documentation / UI text |
| **Date** | 2026-09-26 |
| **Requested by** | SirRogersJackBlood |
| **Previous release** | v1.2.1 (CR-ONYX-2026-003) |
| **Status** | Released 2026-09-26: GitHub pre-release [`v.1.2.3`](https://github.com/SirRogersJackBlood/ONYX/releases/tag/v.1.2.3) |

## Rationale
State plainly who designed, built and tested ONYX, and invite independent security review before the beta.

## Scope
1. `AboutActivity`: new CREDITS card (Design, direction & QA: SirRogersJackBlood · Development: Claude (Anthropic), AI pair-programmer · Built on · review note); 1.2.3 entry in version history.
2. `ABOUT.md`, `site/docs/10-about.md`, `site/docs/09-faq.md`: same credits and review note.
3. `SECURITY.md` (new): private reporting via GitHub Security Advisories.
4. `site/releases/manifest.json`: version 1.2.3.
5. Version: versionCode 6, versionName `1.2.3`. The interim 1.2.2 test build is not published; its staged APKs were moved to `v1.2/superseded/`.

## Compatibility
No protocol, storage or policy changes. Upgrades in place over v1.0–v1.2.1 (same signing key).

## Test evidence
- [x] `.\gradlew :app:assembleDebug` succeeds (after freeing disk space on C:)
- [ ] About shows `v1.2.3`, `BUILD 6`, ◆ INSTALLED on 1.2.3, CREDITS card, signer `f9eb3fcb…5165`
- [ ] Chat with a v1.1 / v1.2.1 contact still works

- [x] Staged APK hashes verified against `SHA256SUMS.txt` (4/4)
- [x] All four APKs signed with `f9eb3fcb…5165` (APK Signature Scheme v2)
- [x] GitHub asset `onyx-v1.2.3-arm64-v8a-debug.apk` downloaded and hash matches `03bb0952…2630`; all four asset URLs resolve
- [x] Website updated: release link, per-device downloads and SHA-256 from `site/releases/manifest.json`

- [x] PGP: `SHA256SUMS.txt.asc` and all four `.apk.asc` verify as Good signature from `9672 6F1B CC9F 9D5E 0CED CF9C BC48 B84D 2415 5676`; published key contains no secret material

## Release steps
`.\gradlew :app:assembleDebug` → `.\scripts\stage-release.ps1 -Version 1.2.3` → hashes into release notes → push → GitHub pre-release `v1.2.3`.

## Rollback
Reinstall v1.2.1 (`adb install -r -d`, versionCode 6 → 5). No data migration.
