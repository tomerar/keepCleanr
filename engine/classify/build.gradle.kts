// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    alias(libs.plugins.keepclear.jvm.library)
}

dependencies {
    api(project(":engine:scan"))
    api(project(":core:model"))
    implementation(project(":core:safety"))
    testImplementation(project(":testing:fixtures"))
}
