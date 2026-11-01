// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.storage.media

import android.content.ContentResolver
import android.content.ContentUris
import android.provider.MediaStore
import io.github.tomerar.keepclear.core.safety.ActionHandler
import io.github.tomerar.keepclear.core.safety.OpKind
import io.github.tomerar.keepclear.core.safety.PlannedOp
import io.github.tomerar.keepclear.core.safety.SkipReason
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.core.storage.StorageException
import io.github.tomerar.keepclear.core.storage.StorageGateway

/** Android's MediaStore write requests, behind an interface so the batching is testable on the JVM. */
interface MediaRequests {
    /** `MediaStore.createTrashRequest(uris, trashed)`; returns what the UI launches. */
    fun trash(rows: List<KPath.Media>, trashed: Boolean): Any

    /** `MediaStore.createDeleteRequest(uris)` for rows already in Android's trash ("Empty now"). */
    fun delete(rows: List<KPath.Media>): Any
}

/**
 * M12, ADR 0006/0010: MediaStore-only items (the `play` flavor, or no All files access) go to **Android's own
 * trash** (30 days, then Android deletes them). Android shows its own confirmation, so these ops are re-checked
 * by the executor and then handed over in batches of at most [MAX_BATCH]; everything else goes to [files]
 * (KeepClear's folder trash).
 */
class MediaConsentHandler(
    private val files: ActionHandler,
    private val requests: MediaRequests,
) : ActionHandler {
    override suspend fun perform(op: PlannedOp, gateway: StorageGateway) {
        if (op.ref is KPath.Media) throw StorageException.Unsupported(op.ref, "MediaStore items need Android's consent")
        files.perform(op, gateway)
    }

    override suspend fun veto(op: PlannedOp, gateway: StorageGateway): SkipReason? {
        if (op.ref !is KPath.Media) return files.veto(op, gateway)
        // "Empty now" / restore only for rows that are in Android's trash right now; trash only for rows that aren't.
        val trashed = gateway.stat(op.ref)?.identity?.let { MediaGateway.isTrashed(it) } ?: return SkipReason.MISSING
        return when (op.kind) {
            OpKind.PURGE, OpKind.RESTORE -> if (trashed) null else SkipReason.CHANGED
            OpKind.TRASH, OpKind.MOVE_DELETE_ORIGINAL -> if (trashed) SkipReason.CHANGED else null
            OpKind.RESTORE_CHANGED -> SkipReason.CHANGED
        }
    }

    override fun needsConsent(op: PlannedOp): Boolean = op.ref is KPath.Media

    override suspend fun requestConsent(ops: List<PlannedOp>): List<Any> = batches(ops).map { (kind, rows) ->
        when (kind) {
            OpKind.PURGE -> requests.delete(rows)
            OpKind.RESTORE -> requests.trash(rows, trashed = false)
            else -> requests.trash(rows, trashed = true)
        }
    }

    companion object {
        /** Android caps a single request; larger selections become several dialogs. */
        const val MAX_BATCH = 2_000

        fun batches(ops: List<PlannedOp>): List<Pair<OpKind, List<KPath.Media>>> =
            ops.filter { it.ref is KPath.Media }
                .groupBy { it.kind }
                .flatMap { (kind, list) -> list.map { it.ref as KPath.Media }.chunked(MAX_BATCH).map { kind to it } }
    }
}

/** The real requests (Android 11+). */
class AndroidMediaRequests(private val resolver: ContentResolver) : MediaRequests {
    private fun uris(rows: List<KPath.Media>) =
        rows.map { ContentUris.withAppendedId(MediaStore.Files.getContentUri(it.volume), it.id) }

    override fun trash(rows: List<KPath.Media>, trashed: Boolean): Any =
        MediaStore.createTrashRequest(resolver, uris(rows), trashed).intentSender

    override fun delete(rows: List<KPath.Media>): Any = MediaStore.createDeleteRequest(
        resolver,
        uris(rows),
    ).intentSender
}
