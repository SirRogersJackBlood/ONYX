# Change Record: CR-ONYX-2026-001

| Field | Value |
|---|---|
| **Title** | Stage debug APKs as the v1.0 pre-release series (pre1) |
| **Type** | Standard change: release staging, no code change to the staged artifacts |
| **Date** | 2026-09-26 |
| **Requested by** | SirRogersJackBlood |
| **Implemented by** | Claude (Cowork), on the OnyxChat workspace |
| **Source repo** | https://github.com/SirRogersJackBlood/ONYX |
| **Release target** | https://github.com/SirRogersJackBlood/ONYX/releases (pre-release) |
| **Website** | https://0nyx.up.railway.app |
| **Status** | Implemented: awaiting upload to GitHub Releases |

## Scope

Move every debug APK from the Gradle output directory into a versioned, immutable staging folder (`v1.0/`), rename them to the release naming convention, and record integrity hashes for the pre-release series.

## Artifacts

| Staged file | Original build output | Size | SHA-256 |
|---|---|---|---|
| `onyx-v1.0-pre1-arm64-v8a-debug.apk` | `app-arm64-v8a-debug.apk` | 238.7 MB | `0a04ed83a2981b34c9e83dac666e3f007eacd817419eb504c1d8e446c438d07c` |
| `onyx-v1.0-pre1-armeabi-v7a-debug.apk` | `app-armeabi-v7a-debug.apk` | 222.9 MB | `dc94ec33ce2e06b5b788d1a2a54fbddc5c78eba3b543d4214afce02dbd32a583` |
| `onyx-v1.0-pre1-x86_64-debug.apk` | `app-x86_64-debug.apk` | 244.8 MB | `92219a72683c5b59149a4d5e46ca97ef887d6b19df337eca53567a624804a8cc` |
| `onyx-v1.0-pre1-universal-debug.apk` | `app-universal-debug.apk` | 607.9 MB | `f3192e6e6f17c0cdc67ce461dfcfa3ec48e6725e308cded5d4298cc423b24ed9` |

Build metadata (from `output-metadata.json`, preserved alongside):

- applicationId `io.github.proteu5.onyx` · variant `debug` · versionCode **1** · embedded versionName **`0.1.0-forge`**
- Build time: 2026-09-26 17:07 (local)
- Signing: Android debug keystore (development key). **Not for general distribution.**

> **Note** "pre1" is the release label. The APKs were built before the version bump, so Android's app info still shows `0.1.0-forge`. The version bump below applies from the next build (pre2).

## Implementation steps (performed)

1. Inventoried `app/build/outputs/apk/debug/` (4 APKs + metadata).
2. Created `v1.0/`.
3. Moved each APK with a no-clobber move (`mv -n`) and renamed it to `onyx-v1.0-pre1-<abi>-debug.apk`.
4. Moved `output-metadata.json` alongside for traceability.
5. Computed SHA-256 for every artifact → `SHA256SUMS.txt`.
6. Version bump for the next build: `versionCode 2`, `versionName "1.0.0-pre2"` in `app/build.gradle.kts`.

## Verification

- [x] `app/build/outputs/apk/debug/` is empty (move, not copy)
- [x] All four APKs present in `v1.0/` with sizes identical to the originals
- [x] SHA-256 recorded for all four
- [x] `*.apk` excluded by `.gitignore`, so APKs are **not** committed to the repo; they ship only as Release assets
- [ ] Upload to the GitHub pre-release and confirm the published hashes match `SHA256SUMS.txt`
- [ ] Record the signing certificate: `apksigner verify --print-certs onyx-v1.0-pre1-arm64-v8a-debug.apk`

## Risk & impact

| Risk | Mitigation |
|---|---|
| Debug APKs are large (223–608 MB) | Publish per-ABI APKs; recommend arm64-v8a. The universal APK is optional. Release builds (R8 + stripped natives) will be much smaller. |
| Debug signing key | Labelled pre-release everywhere. F-Droid will sign its own builds, and users must uninstall the preview first. |
| Embedded version string says `0.1.0-forge` | Documented above; corrected from pre2 onward. |

## Rollback

Move the files back: `v1.0/onyx-v1.0-pre1-<abi>-debug.apk` → `app/build/outputs/apk/debug/app-<abi>-debug.apk`. No source, data or published artifact is changed by this CR.
