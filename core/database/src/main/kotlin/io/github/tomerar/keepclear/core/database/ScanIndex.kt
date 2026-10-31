// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.database

import androidx.room.withTransaction
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.engine.scan.ScanItem
import io.github.tomerar.keepclear.engine.scan.Source

/**
 * Persists scan results (design.md §4 step 1). Items of an incomplete scan are kept alongside the
 * previous ones; only a complete scan removes items that disappeared.
 */
class ScanIndex(private val db: KeepClearDatabase, private val clock: () -> Long = System::currentTimeMillis) {
    private val dao = db.scanDao()

    suspend fun startRun(): Long = dao.insertRun(ScanRunEntity(startedAt = clock()))

    suspend fun write(runId: Long, items: List<ScanItem>) {
        db.withTransaction { dao.upsertItems(items.map { it.toEntity(runId) }) }
    }

    suspend fun finish(runId: Long, complete: Boolean, files: Int, bytes: Long, generation: Long?) {
        db.withTransaction {
            dao.finishRun(runId, clock(), complete, files, bytes, generation)
            if (complete) dao.replaceStale(runId)
        }
    }

    suspend fun previous(): Map<String, ScanItem> = dao.allItems().associate { it.path to it.toItem() }

    suspend fun lastGeneration(): Long? = dao.lastCompleteRun()?.mediaGeneration

    companion object {
        fun ScanItem.toEntity(runId: Long) = ScanItemEntity(
            path = path,
            refKind = when (ref) {
                is KPath.Local -> "local"
                is KPath.Media -> "media"
                is KPath.Saf -> "saf"
            },
            refValue = when (val r = ref) {
                is KPath.Local -> r.absolute
                is KPath.Media -> "${r.volume}:${r.id}"
                is KPath.Saf -> "${r.treeUri}\u0000${r.documentId}"
            },
            size = size,
            mtimeMillis = mtimeMillis,
            identity = identity,
            mime = mime,
            mediaId = mediaId,
            isFavorite = isFavorite,
            isTrashed = isTrashed,
            source = source.name,
            scanRunId = runId,
        )

        fun ScanItemEntity.toItem() = ScanItem(
            ref = when (refKind) {
                "local" -> KPath.Local(refValue)
                "media" -> KPath.Media(refValue.substringBeforeLast(':'), refValue.substringAfterLast(':').toLong())
                else -> KPath.Saf(refValue.substringBefore('\u0000'), refValue.substringAfter('\u0000'))
            },
            path = path,
            size = size,
            mtimeMillis = mtimeMillis,
            identity = identity,
            mime = mime,
            mediaId = mediaId,
            isFavorite = isFavorite,
            isTrashed = isTrashed,
            source = Source.valueOf(source),
        )
    }
}
