// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.backup.smb

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

/** M15: opening a network drive: its marker gives it an identity; damaged markers are never overwritten. */
class SmbTargetsTest {
    private val env = SmbTestEnv.location()

    private fun fresh() = env!!.copy(folder = "KeepClear-${UUID.randomUUID()}/backup")

    @Test
    fun `the first open creates the folder and a marker, the next one finds the same identity`(): Unit = runBlocking {
        assumeTrue(env != null)
        val place = fresh()
        val first = SmbTargets.open(place, "NAS", timeoutSeconds = 10) as SmbCheck.Ok
        val again = SmbTargets.open(place, "NAS", timeoutSeconds = 10) as SmbCheck.Ok
        assertEquals(first.target.id, again.target.id)
        assertTrue(first.target.id.startsWith(SmbTargets.ref(place) + "|"))
        // A different folder is a different target, even on the same share.
        val other = SmbTargets.open(fresh(), "NAS", timeoutSeconds = 10) as SmbCheck.Ok
        assertTrue(other.target.id != first.target.id)
    }

    @Test
    fun `a damaged marker is reported and left alone (SEC-3)`(): Unit = runBlocking {
        assumeTrue(env != null)
        val place = fresh()
        val raw = SmbBackupTarget(place, id = "raw", label = "raw", timeoutSeconds = 10)
        raw.mkdirs("")
        raw.create(SmbTargets.MARKER).use { it.write("not a marker".toByteArray()) }
        assertEquals(SmbCheck.DamagedMarker, SmbTargets.open(place, "NAS", timeoutSeconds = 10))
        assertEquals("not a marker", raw.openRead(SmbTargets.MARKER).use { String(it.readBytes()) })
        raw.close()
    }

    @Test
    fun `a wrong password is Refused, a dead address Unreachable`(): Unit = runBlocking {
        assumeTrue(env != null)
        assertEquals(SmbCheck.Refused, SmbTargets.open(fresh().copy(password = "wrong"), "NAS", timeoutSeconds = 10))
        assertEquals(SmbCheck.Unreachable, SmbTargets.open(fresh().copy(port = 1), "NAS", timeoutSeconds = 3))
    }

    @Test
    fun `marker parsing is strict`() {
        assertEquals(
            "0123456789abcdef0123456789abcdef",
            SmbTargets.parseMarker("0123456789abcdef0123456789abcdef\n".toByteArray()),
        )
        assertNull(SmbTargets.parseMarker("0123456789ABCDEF0123456789ABCDEF".toByteArray()))
        assertNull(SmbTargets.parseMarker(ByteArray(65) { '0'.code.toByte() }))
        assertNull(SmbTargets.parseMarker("x".toByteArray()))
    }
}
