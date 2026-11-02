// SPDX-License-Identifier: GPL-3.0-or-later
package keepclear.buildlogic

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** W3 + ADR 0011: network libraries may be declared only by the network module that owns them. */
class DependencyConfinementTest {
    private val okhttp = listOf("com.squareup.okhttp3:okhttp", "com.squareup.okio:okio")

    @Test
    fun `backup net may declare OkHttp and Okio`() {
        assertEquals(emptyList<String>(), DependencyConfinement.check(":backup:net", okhttp))
    }

    @Test
    fun `the app, an engine or a target module declaring OkHttp fails`() {
        for (project in listOf(":app", ":engine:move", ":core:safety", ":backup:target-webdav", ":backup:target-smb")) {
            val violations = DependencyConfinement.check(
                project,
                okhttp + "org.jetbrains.kotlinx:kotlinx-coroutines-core",
            )
            assertEquals(2, violations.size, project)
            assertTrue(violations.all { it.startsWith("$project declares com.squareup.") && ":backup:net" in it })
        }
        // Any other module of the groups too, not just the one we use.
        assertEquals(1, DependencyConfinement.check(":app", listOf("com.squareup.okhttp3:mockwebserver")).size)
    }

    @Test
    fun `SMB libraries stay in the SMB target`() {
        val smb = listOf("com.hierynomus:smbj", "org.bouncycastle:bcprov-jdk18on")
        assertEquals(emptyList<String>(), DependencyConfinement.check(":backup:target-smb", smb))
        assertEquals(2, DependencyConfinement.check(":backup:net", smb).size)
        assertEquals(2, DependencyConfinement.check(":app", smb).size)
    }

    @Test
    fun `everything else is not confined`() {
        val ids = listOf("androidx.core:core-ktx", "com.squareup.retrofit2:retrofit", "com.squareup:javapoet")
        assertEquals(emptyList<String>(), DependencyConfinement.check(":app", ids))
    }
}
