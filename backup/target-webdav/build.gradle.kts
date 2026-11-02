// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    alias(libs.plugins.keepclear.android.library)
}

// M17, ADR 0011: the only module that talks to the network for WebDAV/Nextcloud. HTTP/1.1 through backup:net's
// HostHttpClient (OkHttp, confined to backup:net, W3); INTERNET is declared in the target modules' manifests,
// nowhere else.
android {
    namespace = "io.github.tomerar.keepclear.backup.webdav"
    // The tests use JUnit 5 so they can run the shared BackupTarget contract suite (backup:target-api fixtures).
    testOptions.unitTests.all { test ->
        test.useJUnitPlatform()
        // W4: real-server tests against Nextcloud's Docker image (scripts/nextcloud-test-server.sh); CI always
        // provides one.
        listOf("KEEPCLEAR_NEXTCLOUD_URL", "KEEPCLEAR_NEXTCLOUD_USER", "KEEPCLEAR_NEXTCLOUD_PASSWORD", "CI")
            .forEach { name -> System.getenv(name)?.let { test.environment(name, it) } }
    }
}

dependencies {
    api(project(":backup:target-api"))
    implementation(project(":backup:net"))
    implementation(libs.kotlinx.coroutines.core)
    // M17b: Login Flow v2 and the OCS user endpoint answer JSON (tree parser only, no compiler plugin).
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
