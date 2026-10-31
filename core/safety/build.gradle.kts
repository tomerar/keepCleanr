// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    alias(libs.plugins.keepclear.jvm.library)
}

dependencies {
    api(project(":core:storage-api"))
    implementation(project(":core:common"))
    implementation(project(":core:hash"))
    testImplementation(project(":testing:fixtures"))
}
