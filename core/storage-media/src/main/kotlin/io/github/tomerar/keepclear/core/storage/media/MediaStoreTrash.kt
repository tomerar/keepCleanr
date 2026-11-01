// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.storage.media

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import io.github.tomerar.keepclear.core.safety.ActionHandler
import io.github.tomerar.keepclear.core.safety.ConsentRequest
import io.github.tomerar.keepclear.core.safety.OpKind
import io.github.tomerar.keepclear.core.safety.PlannedOp
import io.github.tomerar.keepclear.core.safety.SkipReason
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.core.storage.StorageException
import io.github.tomerar.keepclear.core.storage.StorageGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One Android dialog: [kind] decides trash, restore or delete; [ops] are the rows it covers. */
data class MediaBatch(val kind: OpKind, override val ops: List<PlannedOp>) : ConsentRequest {
    val rows: List<KPath.Media> get() = ops.map { it.ref as KPath.Media }
}

/** Android's MediaStore write requests, behind an interface so the batching is testable on the JVM. */
interface MediaRequests {
    /**
     * Whether Android's trash/delete dialog accepts this row. Android takes only image, video and audio items (and
     * subtitles); other files in MediaStore can't go to its trash, so they are skipped and reported.
     */
    suspend fun canAsk(row: KPath.Media): Boolean

    /**
     * Builds the request for one batch (`createTrashRequest` / `createDeleteRequest`), returning what the UI
     * launches. Called for one batch at a time, right before it is shown: creating a second request of the same
     * kind cancels the first one's `IntentSender`.
     */
    suspend fun build(batch: MediaBatch): Any
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
        val row = op.ref as? KPath.Media ?: return files.veto(op, gateway)
        // "Empty now" / restore only for rows that are in Android's trash right now; trash only for rows that aren't.
        val trashed = gateway.stat(row)?.identity?.let { MediaGateway.isTrashed(it) } ?: return SkipReason.MISSING
        val stateOk = when (op.kind) {
            OpKind.PURGE, OpKind.RESTORE -> trashed
            OpKind.TRASH, OpKind.MOVE_DELETE_ORIGINAL -> !trashed
            OpKind.RESTORE_CHANGED -> false
        }
        return when {
            !stateOk -> SkipReason.CHANGED
            !requests.canAsk(row) -> SkipReason.UNSUPPORTED
            else -> null
        }
    }

    override fun needsConsent(op: PlannedOp): Boolean = op.ref is KPath.Media

    /** Only describes the batches; [MediaRequests.build] makes each Android request when it is about to show. */
    override suspend fun requestConsent(ops: List<PlannedOp>): List<ConsentRequest> = batches(ops)

    companion object {
        /** Android caps a single request; larger selections become several dialogs. */
        const val MAX_BATCH = 2_000

        fun batches(ops: List<PlannedOp>): List<MediaBatch> =
            ops.filter { it.ref is KPath.Media }
                .groupBy { it.kind }
                .flatMap { (kind, list) -> list.chunked(MAX_BATCH).map { MediaBatch(kind, it) } }
    }
}

/** The real requests (Android 11+). */
class AndroidMediaRequests(private val resolver: ContentResolver) : MediaRequests {
    /** The item URI in its media collection (Android rejects `Files` URIs for images, video and audio). */
    private suspend fun itemUri(row: KPath.Media): Uri? = withContext(Dispatchers.IO) {
        val args = Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE) }
        val filesUri = ContentUris.withAppendedId(MediaStore.Files.getContentUri(row.volume), row.id)
        val type = resolver.query(filesUri, arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE), args, null)?.use {
            if (it.moveToFirst()) it.getInt(0) else null
        }
        collectionFor(type, row.volume)?.let { ContentUris.withAppendedId(it, row.id) }
    }

    override suspend fun canAsk(row: KPath.Media): Boolean = itemUri(row) != null

    override suspend fun build(batch: MediaBatch): Any {
        val uris = batch.rows.map { itemUri(it) ?: throw StorageException.Unsupported(it, "not image/video/audio") }
        return when (batch.kind) {
            OpKind.PURGE -> MediaStore.createDeleteRequest(resolver, uris)
            OpKind.RESTORE -> MediaStore.createTrashRequest(resolver, uris, false)
            else -> MediaStore.createTrashRequest(resolver, uris, true)
        }.intentSender
    }

    companion object {
        fun collectionFor(mediaType: Int?, volume: String): Uri? = when (mediaType) {
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> MediaStore.Images.Media.getContentUri(volume)
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> MediaStore.Video.Media.getContentUri(volume)
            MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO -> MediaStore.Audio.Media.getContentUri(volume)
            else -> null
        }
    }
}
