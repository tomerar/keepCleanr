// SPDX-License-Identifier: GPL-3.0-or-later
package keepclear.buildlogic

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DependencyAllowlistTest {
    @Test
    fun `androidx and kotlin are allowed`() {
        val ids = listOf(
            "androidx.core:core:1.19.1",
            "org.jetbrains.kotlin:kotlin-stdlib:2.4.21",
            "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0",
            "org.jspecify:jspecify:1.0.0",
        )
        assertEquals(emptyList<String>(), DependencyAllowlist.check(ids))
    }

    @Test
    fun `firebase, play services and analytics are denied`() {
        val ids = listOf(
            "com.google.firebase:firebase-analytics:22.0.0",
            "com.google.android.gms:play-services-base:18.0.0",
            "io.sentry:sentry-android:8.0.0",
        )
        val violations = DependencyAllowlist.check(ids)
        assertEquals(3, violations.size)
        assertTrue(violations.all { it.startsWith("Denied dependency") })
    }

    @Test
    fun `unknown group needs discussion`() {
        val violations = DependencyAllowlist.check(listOf("com.example:lib:1.0"))
        assertTrue(violations.single().contains("not on the allowlist"))
    }

    @Test
    fun `the SMB libraries are allowed as exact modules, not their whole groups (M15)`() {
        val smb = listOf(
            "com.hierynomus:smbj:0.15.0",
            "com.hierynomus:asn-one:0.6.0",
            "org.bouncycastle:bcprov-jdk18on:1.85.2",
            "org.slf4j:slf4j-api:2.0.18",
            "net.engio:mbassador:1.3.2",
        )
        assertTrue(DependencyAllowlist.check(smb).isEmpty())
        // Other modules of the same groups still need discussion.
        assertTrue(DependencyAllowlist.check(listOf("com.hierynomus:sshj:0.40.0")).single().contains("not on the allowlist"))
        assertTrue(DependencyAllowlist.check(listOf("org.slf4j:slf4j-simple:2.0.18")).single().contains("not on the allowlist"))
    }
}
