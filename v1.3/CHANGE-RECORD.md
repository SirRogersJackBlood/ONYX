# Change Record: CR-ONYX-2026-006

| Field | Value |
|---|---|
| **Title** | ONYX v1.3.0: release signing, new application ID, F-Droid submission |
| **Type** | Major change: new app identity and signing key |
| **Date** | 2026-10-18 |
| **Requested by** | SirRogersJackBlood |
| **Previous release** | v1.2.4 (CR-ONYX-2026-005) |
| **Status** | Code complete and lab-tested: awaiting final build, publish and F-Droid merge request |

## Rationale
F-Droid requires an application ID the publisher owns, free dependencies from trusted repositories, and a source build it can reproduce. A dedicated release key replaces the development key, so F-Droid can publish the developer-signed APKs.

## Scope
1. Application ID `io.github.sirrogersjackblood.onyx` (was `io.github.proteu5.onyx`). Code namespace unchanged.
2. Release signing config (keystore path in `local.properties`, passwords from environment). Certificate SHA-256 `dc2130161e6ce3f7e0fc355bbf733fbe169170f59b6a24223a04d31cf7102e9e`.
3. Per-ABI version codes (base 8: universal 80, armeabi-v7a 81, arm64-v8a 82, x86_64 83); `-PonyxAbi=` single-ABI builds.
4. Reproducible-build settings: no VCS info, no ART baseline profile, NDK 30.0.16248370 pinned, R8 shrinking.
5. Packaging: native libraries stripped; libsignal test library and desktop libraries excluded.
6. Dependencies only from Google Maven and Maven Central (Guardian Project repo removed).
7. F-Droid recipe `fdroid/io.github.sirrogersjackblood.onyx.yml`; fastlane screenshots and changelogs 80–83.
8. Licensing: full AGPL-3.0 `LICENSE`, artwork Apache-2.0 (`LICENSE-ASSETS`, `NOTICE`), `TRADEMARKS.md`.
9. Website and docs updated (vault per Android version, emulator lab, licensing, v1.3.0 notice).

## Impact
Android installs v1.3.0 as a **new app** next to the old one. Users pair their contacts again, then uninstall the pre-release. Wire protocol unchanged: v1.3.0 talks to all earlier versions.

## Test evidence
- [x] Emulator lab on the release build, 65/65 (run 20261017-2012)
- [x] Release APKs signed with the release key (v2), version codes 80–83 verified
- [x] Native libraries stripped (libsignal 74 MB → 6.7 MB)
- [ ] Rebuild without desktop libraries: lab on Android 11 and 16
- [ ] Real devices: fresh install on Redmi Note 8 and Galaxy S25 Edge, pairing works
- [ ] Hashes and PGP signatures verified after staging
- [ ] F-Droid CI build on the merge request passes

## Release steps
`.\gradlew :app:assembleRelease` → `.\scripts\stage-release.ps1 -Version 1.3.0 -Release -Sign` → verify → commit, push, tag `v1.3.0` → GitHub pre-release → F-Droid merge request.

## Rollback
Keep using v1.2.4 (different app ID, so both can be installed). For F-Droid: close the merge request.

## Key custody
Release keystore and PGP secret key backed up offline (USB). Loss of the release keystore means no further updates for installs signed with it.
