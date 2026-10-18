// SPDX-License-Identifier: AGPL-3.0-only
import com.android.build.api.variant.FilterConfiguration
import java.util.Properties

plugins {
    // AGP 9 compiles Kotlin itself (built-in Kotlin); no separate kotlin-android plugin.
    id("com.android.application")
}

// ---- versioning -------------------------------------------------------------------------
// Base versionCode; each per-ABI APK gets base * 10 + ABI index (F-Droid VercodeOperation 10*%c+N).
val onyxVersionCode = 8
val onyxVersionName = "1.3.0"
val abiCodes = mapOf("armeabi-v7a" to 1, "arm64-v8a" to 2, "x86_64" to 3)
val allAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")
// F-Droid builds one ABI per recipe entry: ./gradlew assembleRelease -PonyxAbi=arm64-v8a
val onyxAbi: String? = providers.gradleProperty("onyxAbi").orNull?.also {
    require(it in abiCodes) { "onyxAbi must be one of ${abiCodes.keys}" }
}
val buildAbis = onyxAbi?.let { listOf(it) } ?: allAbis

// ---- release signing (developer builds only) ------------------------------------------------
// local.properties (never committed):  onyx.keystore=C:\\Users\\Support\\onyx-keys\\onyx-release.jks
//                                      onyx.keyAlias=onyx
// Passwords come from the environment: ONYX_KS_PASS and ONYX_KEY_PASS (key password defaults to the store one).
// F-Droid and anyone else without these get an unsigned release APK.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val releaseKeystore: String? = localProps.getProperty("onyx.keystore")

android {
    namespace = "io.github.proteu5.onyx"
    compileSdk = 36
    // Pinned so native libraries are stripped identically here and on F-Droid (reproducible builds).
    ndkVersion = "30.0.16248370"

    defaultConfig {
        // Changed in 1.3.0 (from io.github.proteu5.onyx): an ID the publisher owns, as F-Droid requires.
        applicationId = "io.github.sirrogersjackblood.onyx"
        minSdk = 30          // Android 11: BiometricPrompt authenticators, scoped storage, TLS 1.3
        targetSdk = 36
        versionCode = onyxVersionCode
        versionName = onyxVersionName
    }

    // One APK per ABI keeps F-Droid downloads small (Tor + libsignal + Go natives are large).
    splits {
        abi {
            isEnable = true
            reset()
            include(*buildAbis.toTypedArray())
            isUniversalApk = onyxAbi == null
        }
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                keyAlias = localProps.getProperty("onyx.keyAlias", "onyx")
                storePassword = System.getenv("ONYX_KS_PASS")
                keyPassword = System.getenv("ONYX_KEY_PASS") ?: System.getenv("ONYX_KS_PASS")
            }
        }
    }

    buildTypes {
        release {
            // -PonyxUnsigned: build exactly what F-Droid builds (unsigned); scripts/build-release.ps1 then signs it
            // with apksigner, so the published APK = F-Droid's build + a signature block.
            signingConfig = if (providers.gradleProperty("onyxUnsigned").isPresent) null else signingConfigs.findByName("release")
            // Reproducible builds: no VCS metadata file in the APK.
            vcsInfo { include = false }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // F-Droid: no Google-encrypted dependency metadata blob in the APK.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    compileOptions {
        // libsignal needs newer Java APIs (java.time etc.) back-ported via desugaring.
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
            // libsignal-android also ships an 80 MB test-only build of its native library; never ship it.
            excludes += "**/libsignal_jni_testing.so"
        }
        resources {
            excludes += listOf("META-INF/*.version", "META-INF/*.kotlin_module", "DebugProbesKt.bin")
            // libsignal's Java jar carries its desktop native libraries (macOS, Windows, Linux) as resources.
            // Useless on Android, and prebuilt blobs F-Droid's scanner flags.
            excludes += listOf("**/*.dylib", "**/*.dll", "libsignal_jni*.so", "signal_jni*.so")
        }
    }

    lint {
        abortOnError = true
        disable += "MissingTranslation"
    }
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val abi = output.filters.find { it.filterType == FilterConfiguration.FilterType.ABI }?.identifier
            output.versionCode.set(onyxVersionCode * 10 + (abiCodes[abi] ?: 0))   // universal = x0
        }
    }
}

// Reproducible builds: the ART baseline profile is not byte-stable between machines.
tasks.configureEach {
    if (name.contains("ArtProfile")) enabled = false
}

dependencies {
    implementation(project(":core"))
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    // Signal Protocol: PQXDH + Double Ratchet + SPQR (Triple Ratchet). AGPL-3.0.
    // NOTE: newest builds are published to Signal's own repo; 0.86.5 is the latest on Maven Central.
    implementation("org.signal:libsignal-android:0.86.5")

    // Embedded Tor (onion service host + SOCKS client). BSD-3 / Guardian Project.
    // 0.4.9.11+ needs compileSdk 37; stay on 0.4.9.6 until we move compileSdk.
    implementation("info.guardianproject:tor-android:0.4.9.6")
    implementation("info.guardianproject:jtorctl:0.4.5.7")

    // Snowflake proxy (help censored users reach Tor). Guardian Project, MIT/BSD.
    implementation("com.netzarchitekten:IPtProxy:5.5.1")

    // QR encode/decode only (pure Java, Apache-2.0). No Google Play Services, no ML Kit.
    implementation("com.google.zxing:core:3.5.3")
}
