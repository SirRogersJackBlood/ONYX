# Build from source

Source: **[github.com/SirRogersJackBlood/ONYX](https://github.com/SirRogersJackBlood/ONYX)** · Releases: **[/releases](https://github.com/SirRogersJackBlood/ONYX/releases)**

```bash
git clone https://github.com/SirRogersJackBlood/ONYX.git
cd ONYX
```

## Requirements

| Tool | Version |
|---|---|
| JDK | 17 – 25 (the Gradle version below runs on Java 25) |
| Android SDK | Platform 36, build-tools 36 |
| Android NDK | 30.0.16248370 (only used to strip native libraries; pinned for reproducible builds) |
| Gradle | 9.3.1 (downloaded by the wrapper) |
| Android Gradle Plugin | 9.1.1 (compiles Kotlin itself; no separate Kotlin Android plugin) |
| Kotlin | 2.3.0 |

The quickest setup on Windows is to install Android Studio and open the project once. That creates `local.properties` pointing at your SDK.

## Build

```bash
./gradlew :core:test            # protocol tests (pure JVM, no device needed)
./gradlew :app:assembleDebug    # debug APKs, one per ABI + universal
./gradlew :app:assembleRelease  # release APKs, unsigned unless a signing key is configured
./gradlew :app:assembleRelease -PonyxAbi=arm64-v8a   # one ABI only (how F-Droid builds)
```

Output lands in `app/build/outputs/apk/<debug|release>/`. Each per-ABI APK has its own version code: `10 × base + 1/2/3` for armeabi-v7a / arm64-v8a / x86_64, and `10 × base` for the universal APK.

Release signing is for the developer's own builds only. It reads the keystore path from `local.properties` (`onyx.keystore`, `onyx.keyAlias`; never committed) and the passwords from the `ONYX_KS_PASS` / `ONYX_KEY_PASS` environment variables.

## Test on emulators

`scripts/emulator-lab.ps1` (Windows, Android Studio) creates five emulators and runs 13 checks on each: install, launch and Tor bootstrap, rotation, dark mode, offline and back online, Doze, memory pressure, restricted background, process death, screen lock added, screen lock removed.

| Emulator | Android | Profile |
|---|---|---|
| Pixel 4a | 11 (minimum) | No Google services, 2 GB RAM |
| Small phone | 13 | 200% text size |
| Pixel 7 | 14 | Mainstream phone |
| Pixel Tablet | 15 | Tablet |
| Pixel Fold | 16 (target) | Foldable, edge-to-edge |

```powershell
.\scripts\emulator-lab.ps1 -Apk .ppuild\outputspkeleasepp-x86_64-release.apk -Setup   # first time
```

Results and crash traces land in `lab-results\` (git-ignored). The lab found three bugs fixed in v1.2.4.

On Windows PowerShell, prefix the wrapper with `.\`, as in `.\gradlew :app:assembleDebug`.

## Project layout

```text
core/   pure Kotlin/JVM protocol module: padding, frames, pairing, onion checks, SOCKS5, SMS codec, Forge rank
app/    Android app: vault, encrypted store, libsignal glue, Tor controller, messenger, UI
site/   this website (Node, zero dependencies)
docs/   architecture notes
fastlane/metadata/android/   F-Droid store listing
```

## Dependencies

| Library | Why | License |
|---|---|---|
| `org.signal:libsignal-android` | PQXDH, Triple Ratchet | AGPL-3.0 |
| `info.guardianproject:tor-android` | embedded Tor | BSD-3 |
| `info.guardianproject:jtorctl` | Tor control port | BSD-3 |
| `com.netzarchitekten:IPtProxy` | Snowflake proxy | MIT / BSD |
| `com.google.zxing:core` | QR encode/decode (pure Java) | Apache-2.0 |
| `com.android.tools:desugar_jdk_libs` | Java API back-ports that libsignal needs | GPL-2.0 + CE |

Because libsignal is AGPL-3.0, ONYX is AGPL-3.0-only.

## Reproducible builds

The build is set up to be reproducible, so anyone can confirm that a published APK came from the published source:

- `dependenciesInfo { includeInApk = false }`: no Google-encrypted dependency blob;
- `vcsInfo { include = false }`: no version-control metadata file;
- ART baseline-profile tasks disabled (not byte-stable between machines);
- NDK version pinned, so native libraries are stripped identically everywhere;
- dependencies only from Google Maven and Maven Central;
- one APK per ABI, each with a fixed version code.

The F-Droid recipe lists the release key's certificate (`AllowedAPKSigningKeys`) and the developer's APK for each ABI (`binary:`). When F-Droid's own build matches the developer's APK, F-Droid publishes the developer-signed APK, so GitHub and F-Droid builds update each other.

To compare your build with a published one:

```bash
apksigner verify --print-certs published.apk
# Strip the signature block from both files and diff them, or use
# F-Droid's reproducible-build tooling (fdroidserver).
```

## Publishing to F-Droid: checklist

- [x] Full AGPL-3.0 text in `LICENSE`; artwork under Apache-2.0 (`LICENSE-ASSETS`, `NOTICE`); `TRADEMARKS.md`
- [x] Application ID the publisher owns: `io.github.sirrogersjackblood.onyx` (from v1.3.0)
- [x] Store text, changelogs and screenshots in `fastlane/metadata/android/en-US/`
- [x] Recipe `fdroid/io.github.sirrogersjackblood.onyx.yml` (one build per ABI, release-key certificate)
- [ ] Gradle wrapper checksum pinned (`distributionSha256Sum`)
- [ ] Release build verified reproducible
- [ ] Tagged release `v1.3.0` with release-signed APKs
- [ ] Recipe submitted to `fdroiddata`

## Hosting this site

The site in `site/` is a single Node file with no dependencies:

```bash
cd site
node server.js              # http://localhost:8080
```

Put APKs in `site/releases/` and edit `site/releases/manifest.json`. The page lists them with SHA-256 hashes computed at runtime, and `/SHA256SUMS` serves the plain list. Screenshots in `site/public/screenshots/` appear in the gallery automatically, in file-name order.
