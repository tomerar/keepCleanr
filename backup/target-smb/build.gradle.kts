// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    alias(libs.plugins.keepclear.android.library)
}

// M15, ADR 0011: the only module that talks to the network for SMB. INTERNET is declared here, nowhere else.
android {
    namespace = "io.github.tomerar.keepclear.backup.smb"
    // The release build shrinks code with R8; these rules keep smbj's reflective parts (M15).
    defaultConfig.consumerProguardFiles("consumer-rules.pro")
    testOptions.unitTests.all { test ->
        // Integration tests talk to a real Samba server (scripts/samba-test-server.sh); CI always provides one.
        listOf(
            "KEEPCLEAR_SMB_HOST",
            "KEEPCLEAR_SMB_PORT",
            "KEEPCLEAR_SMB_SHARE",
            "KEEPCLEAR_SMB_USER",
            "KEEPCLEAR_SMB_PASSWORD",
            "KEEPCLEAR_SMB_CONF",
            "KEEPCLEAR_SMB_DROP_SHARE",
            "CI",
        )
            .forEach { name -> System.getenv(name)?.let { test.environment(name, it) } }
    }
}

dependencies {
    api(project(":backup:target-api"))
    implementation(libs.smbj)
    // NTLM needs MD4, which Android's own crypto doesn't offer (research 06 §3.1).
    implementation(libs.bouncycastle.bcprov)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit4)
}
