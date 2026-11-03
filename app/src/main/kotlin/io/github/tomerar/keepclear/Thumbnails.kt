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

    /**
     * H1c: a preview of a file by its path (a trashed file: KeepClear's trash is on the phone), photos and videos
     * only. Read-only; a file that can't be decoded gives null and the tile shows its type icon.
     */
    open suspend fun loadFile(path: String, mtimeMillis: Long, sizePx: Int): Bitmap? {
        if (!FileKinds.hasPicture(path.substringAfterLast('/'))) return null
        return cached("$path#$sizePx#$mtimeMillis", sizePx) { size, signal -> decodeFile(path, size, signal) }
    }

    /**
     * H1c: Android's own thumbnail of a document on a local drive (a USB stick or SD card through Android's file
     * provider), for the restore grid. Android makes it from the drive on the phone; nothing goes over a network.
     */
    open suspend fun loadDocument(uri: android.net.Uri, sizePx: Int): Bitmap? {
        val resolver = resolver ?: return null
        return cached("$uri#$sizePx", sizePx) { size, signal -> resolver.loadThumbnail(uri, size, signal) }
    }

    /** E13: like [load], a decode stops when its tile leaves the screen. */
    private suspend fun cached(key: String, px: Int, decode: (Size, CancellationSignal) -> Bitmap?): Bitmap? {
        cache.get(key)?.let { return it }
        val signal = CancellationSignal()
        val bitmap = coroutineScope {
            val decoding = async(Dispatchers.IO) { runCatching { decode(Size(px, px), signal) }.getOrNull() }
            try {
                decoding.await()
            } catch (e: CancellationException) {
                signal.cancel()
                throw e
            }
        }
        return bitmap?.also { cache.put(key, it) }
    }

    private fun decodeFile(path: String, size: Size, signal: CancellationSignal): Bitmap? =
        when (FileKinds.of(path.substringAfterLast('/'))) {
            FileKind.IMAGE -> ThumbnailUtils.createImageThumbnail(File(path), size, signal)
            FileKind.VIDEO -> ThumbnailUtils.createVideoThumbnail(File(path), size, signal)
            else -> null
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

/**
 * H1c: one grid tile's picture, loaded while the tile is on screen (a tile scrolled away cancels its decode).
 * [load] is null without a loader (tests, previews) or for a file without a picture: then the tile shows its icon.
 */
@androidx.compose.runtime.Composable
fun rememberTilePainter(
    key: Any,
    sizeDp: Int = 128,
    load: (suspend (ThumbnailLoader, Int) -> Bitmap?)?,
): androidx.compose.ui.graphics.painter.Painter? {
    val loader = LocalThumbnailLoader.current ?: return null
    if (load == null) return null
    val px = with(androidx.compose.ui.platform.LocalDensity.current) { sizeDp.dp.roundToPx() }
    val bitmap by produceState<Bitmap?>(null, key, loader, px) { value = load(loader, px) }
    return remember(bitmap) { bitmap?.let { androidx.compose.ui.graphics.painter.BitmapPainter(it.asImageBitmap()) } }
}
