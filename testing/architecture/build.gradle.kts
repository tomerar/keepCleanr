// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    alias(libs.plugins.keepclear.jvm.library)
}

dependencies {
    testImplementation(libs.konsist)
}

tasks.test {
    // Konsist reads source files of the whole project.
    inputs.files(
        rootProject.fileTree(rootDir) {
            include("**/*.kt")
            exclude("**/build/**", ".gradle/**")
        },
    )
}
