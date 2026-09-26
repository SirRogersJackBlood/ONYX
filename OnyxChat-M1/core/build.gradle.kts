// SPDX-License-Identifier: AGPL-3.0-only
// Pure-JVM protocol core: no Android, no network libraries, fully unit-testable.
plugins { id("org.jetbrains.kotlin.jvm") }

java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }
kotlin { jvmToolchain(17) }

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
