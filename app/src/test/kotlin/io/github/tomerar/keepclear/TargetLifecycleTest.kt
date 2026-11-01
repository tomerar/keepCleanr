// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import io.github.tomerar.keepclear.backup.BackupTarget
import io.github.tomerar.keepclear.backup.TargetCaps
import io.github.tomerar.keepclear.core.storage.Stat
import io.github.tomerar.keepclear.engine.move.MoveJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream

/** PR #25 review: a network drive's connection is released after every use, also on failure and cancellation. */
class TargetLifecycleTest {
    private class CountingTarget : BackupTarget {
        var closes = 0
        override val id = "t"
        override val label = "t"
        override val caps = TargetCaps(canReadBack = true, readsFromServer = true)

        override suspend fun freeBytes(): Long? = null

        override suspend fun mkdirs(rel: String) = Unit

        override suspend fun create(rel: String): OutputStream = error("unused")

        override suspend fun rename(fromRel: String, toRel: String) = Unit

        override suspend fun stat(rel: String): Stat? = null

        override suspend fun openRead(rel: String): InputStream = error("unused")

        override suspend fun delete(rel: String) = Unit

        override suspend fun list(relDir: String): List<Stat> = emptyList()

        override suspend fun close() {
            closes++
        }
    }

    @Test
    fun `closed after a normal use and after a failure`(): Unit = runBlocking {
        val t = CountingTarget()
        assertEquals(3, t.useTarget { 3 })
        runCatching { t.useTarget { error("drive went away") } }
        assertEquals(2, t.closes)
    }

    @Test
    fun `closed when the work is cancelled`(): Unit = runBlocking {
        val t = CountingTarget()
        val work = async { t.useTarget { awaitCancellation() } }
        yield()
        work.cancel(CancellationException("user stopped"))
        runCatching { work.await() }
        assertEquals(1, t.closes)
    }

    @Test
    fun `forgetting the drive stops only jobs that back up to it (C9)`() {
        assertTrue(
            OpenedTarget.usesNetworkDrive(
                MoveJob("j", "x", "NAS", "KeepClear/P", 1, targetRef = "smb://nas:445/photos/"),
            ),
        )
        assertFalse(
            OpenedTarget.usesNetworkDrive(MoveJob("j", "x", "USB", "KeepClear/P", 1, targetRef = "content://x/tree/y")),
        )
        assertFalse(OpenedTarget.usesNetworkDrive(null))
    }
}
