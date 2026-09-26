# ONYX · Crucible Engine

Private peer-to-peer messaging for Android. **No phone numbers, no accounts, no servers.**
Messages go onion-to-onion over Tor and are protected by the Signal Protocol (post-quantum PQXDH + Triple Ratchet).

BY: **Proteu5** · License: AGPL-3.0-only · Target: F-Droid

## Status: M1 "Forge"
- ✅ `core/`: protocol module (pure Kotlin/JVM). **21/21 unit tests pass**, covering SHA3 against the JDK, RFC 5869 HKDF vectors, a real Tor onion checksum, pairing tamper tests, SOCKS5 against a fake Tor, padding, SMS codec and Forge tiers.
- ✅ `app/`: Android app, fully written. **It has not been compiled yet**, because the environment it was written in has no access to the Android SDK or Maven. The first build happens on your machine (below); expect a handful of small compile fixes.

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for the threat model, protocols, how ONYX compares with Signal, and the roadmap.

## Build (Windows, Android Studio)
1. Install **Android Studio** (current stable) with the **Android SDK Platform 36** and **JDK 17**.
2. Open this `OnyxChat` folder in Android Studio and let Gradle sync. It downloads Gradle 8.14.3, AGP 8.13, libsignal, tor-android, IPtProxy and ZXing.
3. Run the core tests with `gradlew :core:test`.
4. Build the APK with `gradlew :app:assembleDebug`.
   Output: `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`. Both of your phones are arm64.
5. Install on both phones with `adb install -r app-arm64-v8a-debug.apk`.

## First test (two phones)
1. Open ONYX on both phones and wait for **● ONLINE** (the first Tor bootstrap takes 10–60 s).
2. On phone A tap **Show my code**. On phone B tap **Scan code**.
3. B connects to A's onion, the phones exchange keys, and the chat opens. A's chat unlocks as soon as B's first handshake message arrives.
4. Send messages. Force-stop one phone and send again: the message stays **queued** and is delivered when that phone comes back online.

The Galaxy S25 Edge and the Redmi Note 8 both work for this. The hypervisor limitations in the handoff notes do not affect the app.

## Before publishing to F-Droid
- [ ] Append the full AGPL-3.0 text to `LICENSE`.
- [ ] Pin the Gradle wrapper checksum: `gradlew wrapper --gradle-distribution-sha256-sum <sha>`.
- [ ] Run a release build and check that it builds reproducibly.
- [ ] Check dependency versions against the latest releases. Signal now publishes newer libsignal builds to its own Maven repository; 0.86.5 is the newest on Maven Central.
- [ ] Write the fdroiddata metadata YAML (`io.github.proteu5.onyx.yml`).
