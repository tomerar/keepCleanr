// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    alias(libs.plugins.keepclear.android.application)
    alias(libs.plugins.keepclear.compose)
    alias(libs.plugins.roborazzi)
}

// L6 (owner review 2026-11-02): CI signs test APKs with a private test key when the owner has added it as Actions
// secrets (docs/progress.md, "Waiting for owner"). The CI step writes the keystore to a temporary file and passes
// its path and passwords in these variables; without them, the public key in keystore/ signs, as before.
val privateTestKeystore: File? = providers.environmentVariable("KEEPCLEAR_TEST_KEYSTORE").orNull
    ?.takeIf { it.isNotBlank() }
    ?.let(::File)
    ?.takeIf { it.isFile }

android {
    namespace = "io.github.tomerar.keepclear"
    defaultConfig {
        versionCode = 1 // 0.0.1 dev; scheme in ADR 0009
        versionName = "0.0.1"
        // L6: true only in a test build signed with the private test key; the set-up screens of a test build signed
        // with the public key say "use a test account" (anyone can sign an update of it that reads what is saved).
        buildConfigField("boolean", "PRIVATE_TEST_KEY", (privateTestKeystore != null).toString())
    }
    androidResources {
        localeFilters += listOf("en", "iw")
    }
    // PR H1b: README.html on the backup drive is written in Hebrew and English whatever the phone's language, and the
    // in-app language switch needs both too: a Play bundle keeps every language in the base APK (two languages only).
    bundle {
        language {
            enableSplit = false
        }
    }
    signingConfigs {
        // Test builds only: a committed, non-secret key so CI test APKs update each other (keystore/README.md).
        // Releases are signed by the owner's key, never from this repository (ADR 0009).
        getByName("debug") {
            if (privateTestKeystore != null) {
                // L6: the owner's private test key (never committed; an Actions secret written to a temp file).
                storeFile = privateTestKeystore
                storePassword = providers.environmentVariable("KEEPCLEAR_TEST_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("KEEPCLEAR_TEST_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("KEEPCLEAR_TEST_KEY_PASSWORD").get()
            } else {
                storeFile = rootProject.file("keystore/debug-test.keystore")
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }
    buildFeatures.resValues = true
    // M17b: the app's Nextcloud tests also run against the real server of W4 when one is given (CI always).
    testOptions.unitTests.all { test ->
        listOf("KEEPCLEAR_NEXTCLOUD_URL", "KEEPCLEAR_NEXTCLOUD_USER", "KEEPCLEAR_NEXTCLOUD_PASSWORD", "CI")
            .forEach { name -> System.getenv(name)?.let { test.environment(name, it) } }
    }
    buildTypes {
        debug {
            // Review PR B (M3): the public test key must never be able to update a real install, so test builds
            // are a separate app: io.github.tomerar.keepclear.test, named "KeepClear test" (ADR 0009 note).
            applicationIdSuffix = ".test"
            resValue("string", "launcher_name", "KeepClear test")
        }
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
    implementation(project(":core:storage-saf"))
    implementation(project(":core:database"))
    implementation(project(":backup:target-saf"))
    implementation(project(":backup:target-smb"))
    // M17b: Nextcloud under Settings › Advanced (OkHttp stays inside backup:net, W3).
    implementation(project(":backup:target-webdav"))
    // D3: the host rule of the network places (HostRule). OkHttp itself stays confined to backup:net (W3).
    implementation(project(":backup:net"))
    implementation(project(":engine:move"))
    implementation(project(":engine:restore"))
    implementation(project(":engine:scan"))
    implementation(project(":engine:classify"))
    implementation(project(":engine:trash"))
    implementation(project(":engine:enrich"))
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
    // Owner review PR E: ViewModel tests on the real graph with the engines' fakes (FakeSigner, FakeStorageGateway).
    testImplementation(testFixtures(project(":engine:trash")))
    testImplementation(testFixtures(project(":core:storage-api")))
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    // Fake USB drive for instrumented MOVE tests; inert until a test enables it. Never in release.
    debugImplementation(project(":testing:usb-provider"))

    androidTestImplementation(testFixtures(project(":engine:trash")))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}

roborazzi {
    outputDir.set(layout.projectDirectory.dir("src/test/screenshots"))
}
