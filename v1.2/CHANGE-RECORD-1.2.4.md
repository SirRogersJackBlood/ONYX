# Change Record: CR-ONYX-2026-005

| Field | Value |
|---|---|
| **Title** | ONYX v1.2.4: vault and Tor reliability fixes (emulator lab) |
| **Type** | Normal change: bug fix, including a data-loss defect |
| **Date** | 2026-10-17 |
| **Requested by** | SirRogersJackBlood |
| **Previous release** | v1.2.3 (CR-ONYX-2026-004) |
| **Status** | Released 2026-10-17: GitHub pre-release [`v1.2.4`](https://github.com/SirRogersJackBlood/ONYX/releases/tag/v1.2.4) |

## Rationale
The new emulator lab found three defects in v1.2.3, one of which destroys user data on Android 12–14.

## Scope
1. `vault/KeyVault.kt`: master key lock-bound only on Android 11 and 15+ with a screen lock; unbound on Android 12–14 (API 31–34) and without a lock. Re-wraps the data key when the right binding changes (atomic: the vault file names its own master key; old key deleted last). `master.state` records the binding. New `lockMissing()`, `lockBound()`, `isLost()`.
2. `OnyxApp.kt` / `MainActivity.kt`: if the master key was erased by Android, reset cleanly and show a one-time explanation instead of crash-looping.
3. `SettingsActivity.kt` / `Ui.kt`: red NO SCREEN LOCK warning with a button to security settings; note when the key is not lock-bound.
4. `tor/TorController.kt` / `service/OnyxService.kt`: Tor never started twice in one process; restart the process instead (sticky service), and end the process after "Stop ONYX".
5. `scripts/emulator-lab.ps1` (new), `.gitignore` (`lab-results/`).
6. Version: versionCode 7, versionName `1.2.4`; About history entry. Tag format `v1.2.4`.
7. v1.2.3 artifacts moved to `v1.2/v1.2.3/`.

## Defects fixed
| # | Defect | Android | Evidence |
|---|---|---|---|
| D1 | Crash at first launch without a screen lock (`User ECDH key missing`) | 13, 14 | lab run 20261016-1526 |
| D2 | All local data lost and crash loop after the screen lock is removed (`vault master key missing`) | 12–14 | lab run 20261016-1609 |
| D3 | SIGABRT in Tor (`hs_circuitmap_init`) when Tor restarts in-process | all | lab run 20261016-1652 |

## Test evidence
- [x] Emulator lab run 20261016-1740: **65/65 PASS**, 0 WARN (Android 11, 13, 14, 15, 16)
- [x] Real devices: Redmi Note 8 and Galaxy S25 Edge install over v1.2.3, chats intact
- [x] Manual: Settings → Stop ONYX, reopen, back online without a crash
- [x] GitHub release assets verified: 4 APKs, per-APK `.asc`, `SHA256SUMS.txt` + `.asc` match the staged files; arm64 APK downloaded and hash re-checked
- [x] Staged hashes match `SHA256SUMS.txt` (4/4); PGP: `SHA256SUMS.txt.asc` and all four `.apk.asc` are Good signatures from `9672 6F1B … 2415 5676`; all four APKs signed with `f9eb3fcb…5165` (v2); dex contains versionName 1.2.4

## Release steps
`.\gradlew :app:assembleDebug` → `.\scripts\stage-release.ps1 -Version 1.2.4 -Sign` → verify → push → GitHub pre-release tag `v1.2.4`.

## Rollback
Reinstall v1.2.3 (`adb install -r -d`). Not recommended on Android 12–14 (D2). v1.2.4 writes its vault file in a new format that v1.2.3 cannot read, so a downgrade loses local data and contacts must pair again.
