// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    alias(libs.plugins.keepclear.jvm.library)
}

dependencies {
    api(project(":core:storage-api"))
    testImplementation(testFixtures(project(":core:storage-api")))
    // M17: the BackupTarget contract suite, shared by the fake drive and each network target.
    testFixturesApi(project(":core:storage-api"))
    testFixturesApi(libs.kotlinx.coroutines.core)
    testFixturesApi(platform(libs.junit5.bom))
    testFixturesApi(libs.junit5.jupiter)
    testFixturesApi(libs.kotest.assertions)
}
