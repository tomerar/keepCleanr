// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    alias(libs.plugins.keepclear.android.library)
}

android {
    namespace = "io.github.tomerar.keepclear.core.gallery"
    // C216: the developer-only G2 evaluation harness core is compiled into the JVM tests and the instrumentation
    // APK only, never into the library (and so never into the app).
    sourceSets.getByName("test").kotlin.srcDir("src/evaluation/kotlin")
    sourceSets.getByName("androidTest").kotlin.srcDir("src/evaluation/kotlin")
}

dependencies {
    implementation(project(":engine:gallery"))
    implementation(project(":engine:classify"))
    implementation(project(":core:safety"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit4)
}
