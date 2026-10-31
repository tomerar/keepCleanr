// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.roborazzi) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.spotless)
    id("keepclear.root")
}

spotless {
    kotlin {
        target("**/*.kt")
        targetExclude("**/build/**")
        ktlint(libs.versions.ktlint.get())
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        targetExclude("**/build/**")
        ktlint(libs.versions.ktlint.get())
    }
}

tasks.register("ciCheck") {
    group = "verification"
    description = "Everything CI runs on every push (except emulator tests)."
    dependsOn(
        "spotlessCheck",
        "checkStringParity",
        subprojects.filter { it.buildFile.exists() }.map { "${it.path}:check" },
        ":app:assembleFossDebug",
        ":app:assemblePlayDebug",
        ":app:verifyRoborazziFossDebug",
    )
}
