// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    alias(libs.plugins.keepclear.android.library)
}

android {
    namespace = "io.github.tomerar.keepclear.core.storage.saf"
    // JUnit's jars (only its annotations are used on the device) each carry the same license notices.
    packaging.resources.excludes += setOf("META-INF/LICENSE-notice.md", "META-INF/LICENSE.md")
}

dependencies {
    api(project(":core:storage-api"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit4)
    androidTestImplementation(project(":testing:usb-provider"))
    // The JVM contract suite (tasks.md M1/M12), run on the device by a JUnit4 adapter: only the JUnit 5
    // annotations and kotest assertions are needed, not the JUnit Platform engine.
    androidTestImplementation(testFixtures(project(":core:storage-api")))
    androidTestImplementation(project(":engine:trash"))
    androidTestImplementation(testFixtures(project(":engine:trash")))
}

configurations.matching { it.name.contains("AndroidTest") }.configureEach {
    exclude(group = "org.junit.platform")
    exclude(group = "org.junit.jupiter", module = "junit-jupiter-engine")
    exclude(group = "org.junit.jupiter", module = "junit-jupiter-params")
}
