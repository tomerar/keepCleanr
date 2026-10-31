// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.content.ContentResolver
import android.content.ContentUris
import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.tomerar.keepclear.engine.scan.ScanItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Loads small previews with platform APIs only (no image library needed): MediaStore thumbnails for
 * indexed media, `ThumbnailUtils` for walked image/video files. Memory cache only (LOW-1: no disk cache).
 */
class ThumbnailLoader(private val resolver: ContentResolver) {
    private val cache = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    suspend fun load(item: ScanItem, sizePx: Int): Bitmap? {
        val key = "${item.path}#$sizePx#${item.mtimeMillis}"
        cache.get(key)?.let { return it }
        val bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val size = Size(sizePx, sizePx)
                val ext = item.path.substringAfterLast('.', "").lowercase()
                when {
                    item.mediaId != null -> resolver.loadThumbnail(
                        ContentUris.withAppendedId(
                            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
                            item.mediaId!!,
                        ),
                        size,
                        null,
                    )

                    ext in imageExtensions -> ThumbnailUtils.createImageThumbnail(File(item.path), size, null)

                    ext in videoExtensions -> ThumbnailUtils.createVideoThumbnail(File(item.path), size, null)

                    else -> null
                }
            }.getOrNull()
        }
        bitmap?.let { cache.put(key, it) }
        return bitmap
    }

    private companion object {
        val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif")
        val videoExtensions = setOf("mp4", "mkv", "mov", "3gp", "webm")
    }
}

/** Null in previews and tests: items show a neutral placeholder. */
val LocalThumbnailLoader = staticCompositionLocalOf<ThumbnailLoader?> { null }
