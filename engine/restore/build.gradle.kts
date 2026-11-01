// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    alias(libs.plugins.keepclear.jvm.library)
}

// MOVE-17: restore from a backup. Writes only new files on the phone; its only destructive calls are on its own
// temporary file (Konsist rule "engine restore only touches its own temp file").
dependencies {
    api(project(":engine:move"))
    implementation(project(":engine:trash"))
    implementation(project(":core:hash"))
    testImplementation(testFixtures(project(":core:storage-api")))
    testImplementation(project(":testing:fixtures"))
    testImplementation(testFixtures(project(":engine:move")))
}
