// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    alias(libs.plugins.keepclear.android.library)
    alias(libs.plugins.keepclear.compose)
}

android {
    namespace = "io.github.tomerar.keepclear.core.designsystem"
}

dependencies {
    // The one byte formatter (decimal units, ByteSize).
    implementation(project(":core:common"))

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
