// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    alias(libs.plugins.keepclear.android.application)
    alias(libs.plugins.keepclear.compose)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "io.github.tomerar.keepclear"
    defaultConfig {
        versionCode = 1 // 0.0.1 dev; scheme in ADR 0009
        versionName = "0.0.1"
    }
    androidResources {
        localeFilters += listOf("en", "iw")
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:i18n"))
    implementation(project(":core:common"))
    implementation(project(":core:storage-file"))
    implementation(project(":core:storage-media"))
    implementation(project(":core:database"))
    implementation(project(":engine:scan"))
    implementation(project(":engine:classify"))
    implementation(project(":engine:trash"))
    implementation(project(":core:safety"))
    implementation(libs.androidx.work.runtime.ktx)
    implementation(project(":core:model"))
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    androidTestImplementation(testFixtures(project(":engine:trash")))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}

roborazzi {
    outputDir.set(layout.projectDirectory.dir("src/test/screenshots"))
}
