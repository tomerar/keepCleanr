// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    alias(libs.plugins.keepclear.android.library)
}

android {
    namespace = "io.github.tomerar.keepclear.backup.saf"
}

dependencies {
    api(project(":backup:target-api"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit4)
    androidTestImplementation(libs.kotlinx.coroutines.core)
    androidTestImplementation(project(":engine:move"))
    androidTestImplementation(testFixtures(project(":engine:move")))
    androidTestImplementation(project(":core:storage-file"))
    androidTestImplementation(project(":testing:usb-provider"))
    androidTestImplementation(project(":core:hash"))
}
