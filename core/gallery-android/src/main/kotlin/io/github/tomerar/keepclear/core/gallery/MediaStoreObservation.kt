// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.gallery

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.os.Bundle
import android.provider.MediaStore
import io.github.tomerar.keepclear.core.model.Category
import io.github.tomerar.keepclear.core.safety.ProtectedPaths
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.engine.classify.ClassifiedItem
import io.github.tomerar.keepclear.engine.gallery.GalleryRun
import io.github.tomerar.keepclear.engine.gallery.SourceKey
import io.github.tomerar.keepclear.engine.gallery.SourceVersion
import io.github.tomerar.keepclear.engine.scan.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Reads one scanner-provided MediaStore row without opening or decoding image content. */
internal class MediaStoreObservation(
    private val resolver: ContentResolver,
    private val storeVersion: (String) -> String?,
) {
    constructor(context: Context) : this(context.contentResolver, { MediaStore.getVersion(context, it) })

    /** The gate must check the live run, permission scope and kept/excluded state. This is not read authority. */
    suspend fun observe(
        item: ClassifiedItem,
        run: GalleryRun,
        protectedPaths: ProtectedPaths,
        gate: suspend (GalleryRun, ClassifiedItem) -> Boolean,
    ): SourceVersion? = withContext(Dispatchers.IO) {
        val scan = item.item
        val ref = scan.ref as? KPath.Media ?: return@withContext null
        if (scan.source != Source.MEDIA_STORE || item.category == Category.PROTECTED || scan.isTrashed ||
            scan.isFavorite || protectedPaths.isProtected(scan.path) || !gate(run, item)
        ) {
            return@withContext null
        }
        currentCoroutineContext().ensureActive()
        try {
            val before = storeVersion(ref.volume)?.takeIf { it.isNotEmpty() && it.length <= 512 }
                ?: return@withContext null
            val uri = ContentUris.withAppendedId(MediaStore.Files.getContentUri(ref.volume), ref.id)
            val columns = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.GENERATION_MODIFIED,
                MediaStore.MediaColumns.IS_PENDING,
                MediaStore.MediaColumns.IS_TRASHED,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.IS_FAVORITE,
                MediaStore.MediaColumns.DATA,
                MediaStore.MediaColumns.VOLUME_NAME,
            )
            val args = Bundle().apply {
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
                putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
            }

            fun read(): SourceVersion? = resolver.query(uri, columns, args, null)?.use { cursor ->
                if (!cursor.moveToFirst() || cursor.count != 1) return@use null
                val index = columns.map(cursor::getColumnIndexOrThrow)
                if (index.any(cursor::isNull)) return@use null
                val size = cursor.getLong(index[1])
                val seconds = cursor.getLong(index[2])
                val generation = cursor.getLong(index[3])
                val mime = cursor.getString(index[6])
                if (cursor.getLong(index[0]) != ref.id || size < 0 || seconds < 0 || seconds > Long.MAX_VALUE / 1_000 ||
                    generation <= 0 || cursor.getInt(index[4]) != 0 || cursor.getInt(index[5]) != 0 ||
                    cursor.getInt(index[7]) != 0 || !mime.startsWith("image/") ||
                    cursor.getString(index[8]) != scan.path || cursor.getString(index[9]) != ref.volume ||
                    protectedPaths.isProtected(cursor.getString(index[8]))
                ) {
                    return@use null
                }
                val mtime = seconds * 1_000
                if (size != scan.size || mtime != scan.mtimeMillis) return@use null
                SourceVersion(
                    key = SourceKey(ref.volume, ref.id.toString()),
                    size = size,
                    mtimeMillis = mtime,
                    revision = "media:${before.length}:$before:$generation",
                    observationId = run.id,
                )
            }
            val observed = read() ?: return@withContext null
            currentCoroutineContext().ensureActive()
            if (read() != observed || storeVersion(ref.volume) != before || !gate(run, item)) return@withContext null
            observed
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            null
        }
    }
}
