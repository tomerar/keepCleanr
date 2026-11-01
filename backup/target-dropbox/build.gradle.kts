// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    alias(libs.plugins.keepclear.android.library)
    alias(libs.plugins.kotlin.serialization)
}

// M17c, ADR 0011: the only module that talks to Dropbox (HTTP API v2, App folder). HTTP/1.1 over the JDK's own
// sockets and TLS through backup:net's MiniHttp (no third-party HTTP library or Dropbox SDK, PRIV-1); JSON through
// kotlinx.serialization, already in the app. INTERNET is declared in this module's manifest. Not wired into the
// app yet (needs the owner's Dropbox app key, see docs/progress.md).
android {
    namespace = "io.github.tomerar.keepclear.backup.dropbox"
    // The tests use JUnit 5 so they can run the shared BackupTarget contract suite (backup:target-api fixtures).
    testOptions.unitTests.all { it.useJUnitPlatform() }
}

dependencies {
    api(project(":backup:target-api"))
    implementation(project(":backup:net"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(testFixtures(project(":backup:target-api")))
    testImplementation(testFixtures(project(":core:storage-api")))
    testImplementation(project(":engine:move"))
    testImplementation(testFixtures(project(":engine:move")))
    testImplementation(project(":core:hash"))
    testImplementation(platform(libs.junit5.bom))
    testImplementation(libs.junit5.jupiter)
    testImplementation(libs.kotest.assertions)
    testRuntimeOnly(libs.junit5.launcher)
}
