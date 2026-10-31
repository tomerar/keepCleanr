// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    `kotlin-dsl`
}

kotlin { jvmToolchain(21) }

dependencies {
    compileOnly(libs.android.gradle.plugin)
    compileOnly(libs.kotlin.gradle.plugin)
    compileOnly(libs.compose.compiler.gradle.plugin)
    testImplementation(platform(libs.junit5.bom))
    testImplementation(libs.junit5.jupiter)
    testRuntimeOnly(libs.junit5.launcher)
}

tasks.test { useJUnitPlatform() }

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "keepclear.android.application"
            implementationClass = "keepclear.buildlogic.AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "keepclear.android.library"
            implementationClass = "keepclear.buildlogic.AndroidLibraryConventionPlugin"
        }
        register("jvmLibrary") {
            id = "keepclear.jvm.library"
            implementationClass = "keepclear.buildlogic.JvmLibraryConventionPlugin"
        }
        register("root") {
            id = "keepclear.root"
            implementationClass = "keepclear.buildlogic.RootConventionPlugin"
        }
        register("compose") {
            id = "keepclear.compose"
            implementationClass = "keepclear.buildlogic.ComposeConventionPlugin"
        }
    }
}
