// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.backup.smb

import io.github.tomerar.keepclear.backup.net.HostRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** PR H5: the network form's port, domain and folder fields (table-driven, positive and negative). */
class SmbFieldsTest {
    @Test
    fun `port - empty is 445, otherwise 1 to 65535 in digits only`() {
        mapOf("" to 445, " " to 445, "445" to 445, "1" to 1, "65535" to 65535, " 139 " to 139).forEach { (t, p) ->
            assertEquals(t, p, SmbFields.port(t))
        }
        listOf("0", "65536", "-1", "+445", "44 5", "4e2", "0x1bd", "١٢٣", "999999", "445.0").forEach {
            assertNull(it, SmbFields.port(it))
        }
    }

    @Test
    fun `domain - optional, a plain name only`() {
        listOf("", "WORKGROUP", "home.lan", "my-domain", "CORP_1", "a").forEach {
            assertTrue(it, SmbFields.isDomain(it))
        }
        listOf("a..b", ".lan", "lan.", "-x", "a b", "dom\\user", "user@dom", "a/b", "a:b", "x\u0001", "דומיין")
            .forEach { assertFalse(it, SmbFields.isDomain(it)) }
    }

    @Test
    fun `folder - inside the share only, made plain`() {
        mapOf(
            "" to "",
            "  " to "",
            "Backups" to "Backups",
            "Backups/Phone" to "Backups/Phone",
            "Backups/Phone/" to "Backups/Phone",
            "גיבוי/טלפון" to "גיבוי/טלפון",
            "a..b" to "a..b",
        ).forEach { (t, f) -> assertEquals(t, f, SmbFields.folder(t)) }
        listOf(
            "/abs",
            "\\\\server\\share",
            "\\x",
            "a\\b",
            "..",
            "../up",
            "a/../b",
            "a/./b",
            "a//b",
            "C:/x",
            "a:b",
            "x/\u0001",
        )
            .forEach { assertNull(it, SmbFields.folder(it)) }
    }

    @Test
    fun `the location refuses a domain the form refuses, and stays network-only`() {
        listOf("dom\\user", "a b", "a..b").forEach { d ->
            try {
                SmbLocation("nas", "photos", user = "me", domain = d)
                fail("accepted domain $d")
            } catch (_: IllegalArgumentException) {
            }
        }
        val l = SmbLocation("nas", "photos", folder = "Backups/Phone", domain = "HOME", port = 4455)
        assertEquals(HostRule.NetworkOnly, l.hosts)
    }
}
