// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.storage.media

import android.content.ContentResolver
import android.os.Bundle
import android.provider.MediaStore
import io.github.tomerar.keepclear.engine.scan.MediaRow
import io.github.tomerar.keepclear.engine.scan.MediaSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads MediaStore's Files collection on every external volume, including rows already in a
 * MediaStore trash (another app's "Recently deleted", SAFE-8). Incremental via
 * `GENERATION_MODIFIED` (SCAN-5). Read-only.
 */
class MediaStoreSource(private val resolver: ContentResolver) : MediaSource {
    override suspend fun query(sinceGeneration: Long?): List<MediaRow> = withContext(Dispatchers.IO) {
        val uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.VOLUME_NAME,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.IS_FAVORITE,
            MediaStore.MediaColumns.IS_TRASHED,
            MediaStore.MediaColumns.GENERATION_MODIFIED,
        )
        val args = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_EXCLUDE)
            if (sinceGeneration != null) {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.GENERATION_MODIFIED} > ?")
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(sinceGeneration.toString()))
            }
        }
        val rows = mutableListOf<MediaRow>()
        resolver.query(uri, projection, args, null)?.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val volume = c.getColumnIndexOrThrow(MediaStore.MediaColumns.VOLUME_NAME)
            val data = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
            val size = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val modified = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val mime = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val favorite = c.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_FAVORITE)
            val trashed = c.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_TRASHED)
            val generation = c.getColumnIndexOrThrow(MediaStore.MediaColumns.GENERATION_MODIFIED)
            while (c.moveToNext()) {
                val path = c.getString(data) ?: continue
                rows += MediaRow(
                    id = c.getLong(id),
                    volume = c.getString(volume) ?: MediaStore.VOLUME_EXTERNAL_PRIMARY,
                    path = path,
                    size = c.getLong(size),
                    mtimeMillis = c.getLong(modified) * 1000,
                    mime = c.getString(mime),
                    isFavorite = c.getInt(favorite) == 1,
                    isTrashed = c.getInt(trashed) == 1,
                    generation = c.getLong(generation),
                )
            }
        }
        rows
    }
}
