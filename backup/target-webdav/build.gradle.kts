// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    alias(libs.plugins.keepclear.android.library)
}

// M17, ADR 0011: the only module that talks to the network for WebDAV/Nextcloud. HTTP/1.1 over the JDK's own
// sockets and TLS through backup:net's MiniHttp (no third-party HTTP library, PRIV-1); INTERNET is declared in the
// target modules' manifests, nowhere else.
android {
    namespace = "io.github.tomerar.keepclear.backup.webdav"
    // The tests use JUnit 5 so they can run the shared BackupTarget contract suite (backup:target-api fixtures).
    testOptions.unitTests.all { it.useJUnitPlatform() }
}

dependencies {
    api(project(":backup:target-api"))
    implementation(project(":backup:net"))
    implementation(libs.kotlinx.coroutines.core)
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
