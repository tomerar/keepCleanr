// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    alias(libs.plugins.keepclear.jvm.library)
}

dependencies {
    api(project(":engine:classify"))
    api(project(":core:storage-api"))
    implementation(project(":core:hash"))
    implementation(project(":core:safety"))
    testImplementation(project(":testing:fixtures"))
    testImplementation(project(":engine:trash"))
    testImplementation(testFixtures(project(":engine:trash")))
}
