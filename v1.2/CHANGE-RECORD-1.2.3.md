# Change Record: CR-ONYX-2026-004

| Field | Value |
|---|---|
| **Title** | ONYX v1.2.3: credits on About, SECURITY.md (final pre-release before beta) |
| **Type** | Standard change: documentation / UI text |
| **Date** | 2026-09-26 |
| **Requested by** | SirRogersJackBlood |
| **Previous release** | v1.2.1 (CR-ONYX-2026-003) |
| **Status** | Code complete: awaiting build, test and publish |

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
- [ ] `.\gradlew :app:assembleDebug` succeeds
- [ ] About shows `v1.2.3`, `BUILD 6`, ◆ INSTALLED on 1.2.3, CREDITS card, signer `f9eb3fcb…5165`
- [ ] Chat with a v1.1 / v1.2.1 contact still works

## Release steps
`.\gradlew :app:assembleDebug` → `.\scripts\stage-release.ps1 -Version 1.2.3` → hashes into release notes → push → GitHub pre-release `v1.2.3`.

## Rollback
Reinstall v1.2.1 (`adb install -r -d`, versionCode 6 → 5). No data migration.
