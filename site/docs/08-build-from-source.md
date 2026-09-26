# Build from source

## Requirements

| Tool | Version |
|---|---|
| JDK | 17 – 25 (the Gradle version below runs on Java 25) |
| Android SDK | Platform 36, build-tools 36 |
| Gradle | 9.3.1 (downloaded by the wrapper) |
| Android Gradle Plugin | 9.1.1 (compiles Kotlin itself; no separate Kotlin Android plugin) |
| Kotlin | 2.3.0 |

The quickest setup on Windows is to install Android Studio and open the project once. That creates `local.properties` pointing at your SDK.

## Build

```bash
./gradlew :core:test            # protocol tests (pure JVM, no device needed)
./gradlew :app:assembleDebug    # debug APKs, one per ABI + universal
./gradlew :app:assembleRelease  # release APKs (needs a signing config)
```

Output lands in `app/build/outputs/apk/<debug|release>/`.

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
- archive tasks use a stable file order and no timestamps;
- one APK per ABI.

To compare your build with a published one:

```bash
apksigner verify --print-certs published.apk
# Strip the signature block from both files and diff them, or use
# F-Droid's reproducible-build tooling (fdroidserver).
```

## Publishing to F-Droid: checklist

- [ ] Full AGPL-3.0 text in `LICENSE`
- [ ] Gradle wrapper checksum pinned (`distributionSha256Sum`)
- [ ] Release build verified reproducible
- [ ] Store text and screenshots in `fastlane/metadata/android/en-US/`
- [ ] Metadata file `io.github.proteu5.onyx.yml` submitted to `fdroiddata`
- [ ] Tagged release in the source repository

## Hosting this site

The site in `site/` is a single Node file with no dependencies:

```bash
cd site
node server.js              # http://localhost:8080
```

Put APKs in `site/releases/` and edit `site/releases/manifest.json`. The page lists them with SHA-256 hashes computed at runtime, and `/SHA256SUMS` serves the plain list. Screenshots in `site/public/screenshots/` appear in the gallery automatically, in file-name order.
