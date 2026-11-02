// SPDX-License-Identifier: GPL-3.0-or-later
@file:OptIn(ThisDeviceInTests::class)

package io.github.tomerar.keepclear.backup.smb

import io.github.tomerar.keepclear.backup.net.TestsOnThisDevice
import io.github.tomerar.keepclear.backup.net.ThisDeviceInTests
import io.github.tomerar.keepclear.core.storage.StorageException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** D3 (owner review 2026-11-02): signing, guest places, path segments and hosts, without a server. */
class SmbSecurityTest {
    private fun target(location: SmbLocation) = SmbBackupTarget(location, id = "t", label = "NAS")

    @Test
    fun `a signed-in place requires signing and prefers encryption, a guest place is copy-only`() {
        val user = target(SmbLocation("nas", "photos", user = "me", password = "pw"))
        assertTrue(user.config().isSigningRequired)
        assertTrue(user.config().isEncryptData)
        assertTrue(user.caps.canFreeOriginals)
        val guest = target(SmbLocation("nas", "photos"))
        // smbj can't sign a guest session; it is accepted unsigned, but only for copies.
        assertFalse(guest.config().isSigningRequired)
        assertTrue(guest.config().isEncryptData)
        assertFalse(guest.caps.canFreeOriginals)
        assertTrue(SmbLocation("nas", "photos").isGuest)
    }

    @Test
    fun `a user's session that is unsigned or quietly a guest is refused, a guest place is not`() {
        assertNull(SmbSessionCheck.problem(false, guest = false, anonymous = false, signed = true, encrypted = false))
        assertNull(SmbSessionCheck.problem(false, guest = false, anonymous = false, signed = false, encrypted = true))
        assertTrue(
            SmbSessionCheck.problem(false, guest = false, anonymous = false, signed = false, encrypted = false) != null,
        )
        assertTrue(
            SmbSessionCheck.problem(false, guest = true, anonymous = false, signed = true, encrypted = false) != null,
        )
        assertTrue(
            SmbSessionCheck.problem(false, guest = false, anonymous = true, signed = false, encrypted = false) != null,
        )
        assertNull(SmbSessionCheck.problem(true, guest = true, anonymous = false, signed = false, encrypted = false))
    }

    @Test
    fun `every path segment sent to the share is checked`() {
        listOf("..", ".", "a\\b", "a:stream", "a:\$DATA", "tab\there", "nul\u0000", "del\u007f", "a/b", "").forEach {
            assertFalse(it, SmbPaths.isSafeSegment(it))
        }
        listOf("IMG_1.jpg", "תמונה.jpg", "a b", "a..b", ".keepclear-target").forEach {
            assertTrue(it, SmbPaths.isSafeSegment(it))
        }
        val t = target(SmbLocation("nas", "photos", folder = "Phone/Backups", user = "me", password = "pw"))
        assertEquals("Phone\\Backups\\Camera\\a.jpg", t.smbPath("Camera/a.jpg"))
        listOf("../x", "a/../b", "a\\b", "a:zone", "a/b:Zone.Identifier", "x/\u0001").forEach { rel ->
            try {
                t.smbPath(rel)
                fail("accepted $rel")
            } catch (_: StorageException.Unsupported) {
            }
        }
        // The folder and share typed (or stored) are checked the same way.
        listOf("../up", "a\\b", "c:d", "x/..").forEach { folder ->
            try {
                SmbLocation("nas", "photos", folder = folder)
                fail("accepted folder $folder")
            } catch (_: IllegalArgumentException) {
            }
        }
        listOf("..", "a:b", "x\u0001").forEach { share ->
            try {
                SmbLocation("nas", share)
                fail("accepted share $share")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun `the app's parser refuses this phone and odd numbers, only tests opt in`() {
        listOf("localhost", "LOCALHOST", "127.0.0.1", "127.0.0.2", "0.0.0.0", "nas.localhost").forEach { host ->
            try {
                SmbLocation(host, "photos")
                fail("accepted $host")
            } catch (_: IllegalArgumentException) {
            }
            assertTrue(host, SmbHosts.isThisPhone(host))
        }
        listOf("127.1", "2130706433", "010.0.0.1", "0x7f.1").forEach { assertFalse(it, SmbHosts.isPlainHost(it)) }
        assertEquals("127.0.0.1", SmbLocation("127.0.0.1", "photos", hosts = TestsOnThisDevice).host)
        assertEquals("192.168.1.20", SmbLocation("192.168.1.20", "photos").host)
        assertFalse(SmbHosts.isThisPhone("192.168.1.20"))
    }
}
