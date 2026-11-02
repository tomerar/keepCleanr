// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.content.ContentResolver
import android.content.ContentUris
import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.os.CancellationSignal
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.tomerar.keepclear.engine.scan.ScanItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.io.File

/**
 * Loads small previews with platform APIs only (no image library needed): MediaStore thumbnails for
 * indexed media, `ThumbnailUtils` for walked image/video files. Memory cache only (LOW-1: no disk cache).
 */
open class ThumbnailLoader(private val resolver: ContentResolver?) {
    private val cache = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    open suspend fun load(item: ScanItem, sizePx: Int): Bitmap? {
        val key = "${item.path}#$sizePx#${item.mtimeMillis}"
        cache.get(key)?.let { return it }
        // E13: leaving a screen (or scrolling past a tile) cancels its decode; Android stops reading the file.
        val signal = CancellationSignal()
        val bitmap = coroutineScope {
            val decoding =
                async(Dispatchers.IO) { runCatching { decode(item, Size(sizePx, sizePx), signal) }.getOrNull() }
            try {
                decoding.await()
            } catch (e: CancellationException) {
                signal.cancel()
                throw e
            }
        }
        bitmap?.let { cache.put(key, it) }
        return bitmap
    }

    /** One decode; it stops when [signal] is cancelled. */
    protected open fun decode(item: ScanItem, size: Size, signal: CancellationSignal): Bitmap? {
        val ext = item.path.substringAfterLast('.', "").lowercase()
        return when {
            item.mediaId != null && resolver != null -> resolver.loadThumbnail(
                ContentUris.withAppendedId(
                    MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
                    item.mediaId!!,
                ),
                size,
                signal,
            )

            ext in imageExtensions -> ThumbnailUtils.createImageThumbnail(File(item.path), size, signal)

            ext in videoExtensions -> ThumbnailUtils.createVideoThumbnail(File(item.path), size, signal)

            else -> null
        }
    }

    private companion object {
        val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif")
        val videoExtensions = setOf("mp4", "mkv", "mov", "3gp", "webm")
    }
}

/** Null in previews and tests: items show a neutral placeholder. */
val LocalThumbnailLoader = staticCompositionLocalOf<ThumbnailLoader?> { null }
