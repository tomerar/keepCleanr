// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    alias(libs.plugins.keepclear.jvm.library)
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    testFixturesApi(libs.kotlinx.coroutines.core)
    testFixturesApi(platform(libs.junit5.bom))
    testFixturesApi(libs.junit5.jupiter)
    testFixturesApi(libs.kotest.assertions)
}
