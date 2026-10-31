// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    alias(libs.plugins.keepclear.jvm.library)
}

dependencies {
    api(project(":core:storage-api"))
    testImplementation(testFixtures(project(":core:storage-api")))
}
