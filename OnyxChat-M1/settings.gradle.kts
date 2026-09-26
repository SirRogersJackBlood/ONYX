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
        // Guardian Project Maven (tor-android, jtorctl). Scoped so nothing else can resolve from it.
        exclusiveContent {
            forRepository { maven("https://raw.githubusercontent.com/guardianproject/gpmaven/master") }
            filter { includeGroup("info.guardianproject") }
        }
    }
}

rootProject.name = "OnyxChat"
include(":core", ":app")
