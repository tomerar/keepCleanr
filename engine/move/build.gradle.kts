// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    alias(libs.plugins.keepclear.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":backup:target-api"))
    api(project(":engine:scan"))
    api(project(":core:safety"))
    implementation(project(":core:hash"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(project(":testing:fixtures"))
    testImplementation(project(":core:storage-file"))
    testImplementation(testFixtures(project(":core:storage-api")))
    testFixturesApi(project(":backup:target-api"))
}
