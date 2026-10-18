// SPDX-License-Identifier: AGPL-3.0-only
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Only Google Maven and Maven Central: both are on F-Droid's trusted-repository list.
        // tor-android and jtorctl are published to Maven Central.
    }
}

rootProject.name = "OnyxChat"
include(":core", ":app")
