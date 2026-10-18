# Releasing ONYX

Every release is built so that F-Droid's own build from source matches it **byte for byte**. F-Droid then publishes the developer-signed APKs, and GitHub and F-Droid installs update each other.

## One-time setup
- Release keystore `onyx-release.jks` (backed up offline with the PGP key), referenced in `local.properties`:
  ```properties
  onyx.keystore=C:/Users/Support/onyx-keys/onyx-release.jks
  onyx.keyAlias=onyx
  ```
- Android SDK build-tools (for `apksigner`) and NDK `30.0.16248370`.
- Gpg4win with the release PGP key (`9672 6F1B … 2415 5676`).

## Each release
1. Bump `onyxVersionCode` (+1) and `onyxVersionName` in `app/build.gradle.kts`. Add `fastlane/metadata/android/en-US/changelogs/<code>1.txt`, `<code>2.txt`, `<code>3.txt` (max 500 characters), the About history entry and `CHANGELOG.md`.
2. Test: `.\scripts\emulator-lab.ps1 -Apk <x86_64 release APK>` must be 65/65, then real phones.
3. Commit **all** fixes, push, and only then tag: `git tag -a vX.Y.Z -m "ONYX vX.Y.Z"` and `git push origin vX.Y.Z`. The tag must build on a clean checkout.
4. Build and sign:
   ```powershell
   $p = Read-Host "Keystore password" -AsSecureString
   $env:ONYX_KS_PASS = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($p))
   .\scripts\build-release.ps1 -Version X.Y.Z -Sign
   ```
   For each ABI this builds exactly what F-Droid builds (`-PonyxAbi=<abi> -PonyxUnsigned`), then signs with `apksigner` using v2 + v3 only and `--alignment-preserved`. **Never sign through Gradle or without `--alignment-preserved`**: apksigner otherwise rewrites the zip padding and F-Droid's comparison fails.
5. Publish a GitHub pre-release on the tag with exactly five files from `vX.Y\`: the three `onyx-vX.Y.Z-<abi>-release.apk`, `SHA256SUMS-release.txt`, `SHA256SUMS-release.txt.asc`. Nothing else.
6. Update `site/releases/manifest.json` (version, tag, hashes, sizes), the install page and the release notes; push.
7. F-Droid picks up new `vX.Y.Z` tags automatically (`AutoUpdateMode: Version`, `UpdateCheckMode: Tags`). Its recipe uses `VercodeOperation` 10×code+1/2/3 and downloads the three APKs by name.

## Self-test
Rebuild a published release and compare it byte for byte:
```powershell
.\scripts\build-release.ps1 -Version 1.3.0 -OutDir $env:TEMP\onyx-check -Expect .\v1.3\SHA256SUMS-release.txt
```
Verified 2026-10-18: Windows build reproduced all three v1.3.0 APKs exactly (`a5cb75…`, `728152…`, `433822…`).

`scripts/stage-release.ps1` is for debug pre-releases only.
