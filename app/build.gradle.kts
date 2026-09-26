// SPDX-License-Identifier: AGPL-3.0-only
plugins {
    // AGP 9 compiles Kotlin itself (built-in Kotlin); no separate kotlin-android plugin.
    id("com.android.application")
}

android {
    namespace = "io.github.proteu5.onyx"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.proteu5.onyx"
        minSdk = 30          // Android 11: BiometricPrompt authenticators, scoped storage, TLS 1.3
        targetSdk = 36
        versionCode = 3
        versionName = "1.1.0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    // One APK per ABI keeps F-Droid downloads small (Tor + libsignal + Go natives are large).
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    buildTypes {
        release {
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
        jniLibs { useLegacyPackaging = false }
        resources { excludes += listOf("META-INF/*.version", "META-INF/*.kotlin_module", "DebugProbesKt.bin") }
    }

    lint {
        abortOnError = true
        disable += "MissingTranslation"
    }
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
