// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    alias(libs.plugins.keepclear.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core:safety"))
    api(project(":core:storage-api"))
    implementation(project(":core:hash"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(project(":testing:fixtures"))
    testImplementation(project(":core:storage-file"))
    testFixturesApi(project(":core:storage-api"))
}
