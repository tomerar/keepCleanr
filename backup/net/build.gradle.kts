// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    alias(libs.plugins.keepclear.jvm.library)
}

// M17, ADR 0011, W3 (owner 2026-11-02): the one HTTP client the network backup targets share
// (HostHttpClient on OkHttp). Pure JVM. OkHttp and Okio are confined to this module: it is the only one allowed
// to declare them (checkDependencyConfinement), and their types never appear in its public API, so the targets
// (`implementation(project(":backup:net"))`) can't import them either (Konsist). It declares no permission:
// INTERNET comes only from the target modules' manifests.
dependencies {
    implementation(libs.okhttp) {
        // Since Kotlin 1.8 these are empty shells around kotlin-stdlib (which we already have, newer); without
        // the exclusion OkHttp/Okio pull in 1.9-era copies.
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
    }
}
