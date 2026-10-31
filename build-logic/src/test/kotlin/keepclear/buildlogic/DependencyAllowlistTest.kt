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
}
