// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    alias(libs.plugins.keepclear.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "io.github.tomerar.keepclear.core.database"
}

android.sourceSets.getByName("test").assets.srcDir("$projectDir/schemas")

room {
    // MIG-1: schemas are committed; every version bump needs an explicit migration + test.
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    api(project(":engine:scan"))
    api(project(":engine:trash"))
    api(project(":engine:move"))
    api(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.kotlinx.coroutines.core)
}
