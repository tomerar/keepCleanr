// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    alias(libs.plugins.keepclear.android.library)
}

android {
    namespace = "io.github.tomerar.keepclear.core.storage.media"
}

dependencies {
    api(project(":engine:scan"))
    api(project(":core:safety"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit4)
    testImplementation(testFixtures(project(":core:storage-api")))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit4)
}
