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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
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

/**
 * F2: up to [max] real thumbnails for a group card's ThumbStrip (design-system.md §8.3), as painters. Files without
 * a picture are left out; without a loader (tests, previews) the list is empty and the card shows none.
 */
@androidx.compose.runtime.Composable
fun rememberThumbPainters(
    items: List<io.github.tomerar.keepclear.engine.classify.ClassifiedItem>,
    max: Int = 4,
    sizeDp: Int = 56,
): List<androidx.compose.ui.graphics.painter.Painter> {
    val loader = LocalThumbnailLoader.current ?: return emptyList()
    val px = with(androidx.compose.ui.platform.LocalDensity.current) { sizeDp.dp.roundToPx() }
    // A few more than shown, so a file without a picture doesn't leave the strip short.
    val candidates = remember(items) { items.take(max * 2).map { it.item } }
    val bitmaps by produceState(emptyList<Bitmap>(), candidates, loader, px) {
        val loaded = mutableListOf<Bitmap>()
        for (item in candidates) {
            if (loaded.size >= max) break
            loader.load(item, px)?.let { loaded += it }
        }
        value = loaded
    }
    return remember(bitmaps) { bitmaps.map { androidx.compose.ui.graphics.painter.BitmapPainter(it.asImageBitmap()) } }
}
