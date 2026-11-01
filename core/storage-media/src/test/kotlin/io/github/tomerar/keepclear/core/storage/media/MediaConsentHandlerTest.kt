// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.storage.media

import io.github.tomerar.keepclear.core.safety.ActionHandler
import io.github.tomerar.keepclear.core.safety.OpKind
import io.github.tomerar.keepclear.core.safety.PlannedOp
import io.github.tomerar.keepclear.core.safety.SkipReason
import io.github.tomerar.keepclear.core.storage.FakeStorageGateway
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.core.storage.Stat
import io.github.tomerar.keepclear.core.storage.StorageGateway
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** M12: MediaStore items go to Android's trash in batches; restore and "Empty now" only for trashed rows. */
class MediaConsentHandlerTest {
    private val built = mutableListOf<MediaBatch>()

    /** Rows with an even id stand for image/video/audio; odd ids for files Android's trash won't take. */
    private val requests = object : MediaRequests {
        override suspend fun canAsk(row: KPath.Media) = row.id % 2 == 0L

        override suspend fun build(batch: MediaBatch): Any = "request".also { built += batch }
    }
    private val files = ActionHandler { _, _ -> }
    private val handler = MediaConsentHandler(files, requests)

    private fun op(id: Long, kind: OpKind = OpKind.TRASH) =
        PlannedOp(KPath.Media("external_primary", id), "/storage/emulated/0/DCIM/$id.jpg", kind, false, 1, 1)

    @Test
    fun `only MediaStore rows need Android's consent`() {
        assertTrue(handler.needsConsent(op(1)))
        assertFalse(
            handler.needsConsent(
                PlannedOp(KPath.Local("/storage/emulated/0/a"), "/storage/emulated/0/a", OpKind.TRASH, false, 1, 1),
            ),
        )
    }

    @Test
    fun `selections are split into batches of 2000, one request kind each`(): Unit = runBlocking {
        val ops = (1L..4_500L).map { op(it) } + listOf(op(9_000, OpKind.RESTORE))
        val batches = handler.requestConsent(ops).map { it as MediaBatch }
        assertEquals(
            listOf(OpKind.TRASH to 2000, OpKind.TRASH to 2000, OpKind.TRASH to 500, OpKind.RESTORE to 1),
            batches.map { it.kind to it.ops.size },
        )
        // Each batch carries exactly its own ops, so an approved dialog counts only what it covered.
        assertEquals(ops, batches.flatMap { it.ops })
    }

    @Test
    fun `no Android request is created up front, the UI builds one per dialog`(): Unit = runBlocking {
        handler.requestConsent((1L..4_500L).map { op(it) })
        assertTrue(built.isEmpty())
    }

    @Test
    fun `rows Android's trash can't take are skipped, not handed over`(): Unit = runBlocking {
        assertEquals(SkipReason.UNSUPPORTED, handler.veto(op(3, OpKind.TRASH), gatewayWith(trashed = false)))
        assertNull(handler.veto(op(4, OpKind.TRASH), gatewayWith(trashed = false)))
    }

    private fun gatewayWith(trashed: Boolean?) = object : StorageGateway by FakeStorageGateway() {
        override suspend fun stat(path: KPath) =
            trashed?.let { Stat(path, "x", Stat.Kind.FILE, 1, 1, MediaGateway.identity(3, it)) }
    }

    @Test
    fun `empty-now and restore only for rows in Android's trash, trash only for others`(): Unit = runBlocking {
        assertNull(handler.veto(op(2, OpKind.PURGE), gatewayWith(trashed = true)))
        assertEquals(SkipReason.CHANGED, handler.veto(op(2, OpKind.PURGE), gatewayWith(trashed = false)))
        assertNull(handler.veto(op(2, OpKind.TRASH), gatewayWith(trashed = false)))
        assertEquals(SkipReason.CHANGED, handler.veto(op(2, OpKind.TRASH), gatewayWith(trashed = true)))
        assertEquals(SkipReason.MISSING, handler.veto(op(2, OpKind.RESTORE), gatewayWith(trashed = null)))
    }
}
