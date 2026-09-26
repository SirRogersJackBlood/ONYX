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
        // Guardian Project Maven (tor-android). Scoped to their group only; jtorctl and
        // other info.guardianproject artifacts may also resolve from Maven Central above.
        maven("https://raw.githubusercontent.com/guardianproject/gpmaven/master") {
            content { includeGroup("info.guardianproject") }
        }
    }
}

rootProject.name = "OnyxChat"
include(":core", ":app")
