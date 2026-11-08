// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.gallery

import android.content.ContentResolver
import android.content.ContentUris
import android.provider.MediaStore
import io.github.tomerar.keepclear.core.safety.ProtectedPaths
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.engine.classify.ClassifiedItem
import io.github.tomerar.keepclear.engine.gallery.FeatureRecord
import io.github.tomerar.keepclear.engine.gallery.FeatureVersion
import io.github.tomerar.keepclear.engine.gallery.GalleryRun
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/** Internal orchestration only; it has no app caller or authority to choose a scope. */
internal class GuardedMediaPngRead(
    private val resolver: ContentResolver,
    private val observations: MediaStoreObservation,
) {
    /** The gate must check current run, permission epoch, scope, exclusions and kept state on every invocation. */
    suspend fun read(
        item: ClassifiedItem,
        run: GalleryRun,
        version: FeatureVersion,
        protectedPaths: ProtectedPaths,
        gate: suspend (GalleryRun, ClassifiedItem) -> Boolean,
    ): FeatureRecord? = withContext(Dispatchers.IO) {
        if (version != FeatureVersion.V1) return@withContext null
        val ref = item.item.ref as? KPath.Media ?: return@withContext null
        val before = observations.observe(item, run, protectedPaths, gate) ?: return@withContext null
        currentCoroutineContext().ensureActive()
        try {
            val uri = ContentUris.withAppendedId(MediaStore.Files.getContentUri(ref.volume), ref.id)
            val stream = resolver.openInputStream(uri)?.let(::CountingStream) ?: return@withContext null
            val facts = BoundedPngDecoder.decode(stream) ?: return@withContext null
            if (stream.count != before.size) return@withContext null
            currentCoroutineContext().ensureActive()
            val after = observations.observe(item, run, protectedPaths, gate) ?: return@withContext null
            if (before != after) return@withContext null
            FeatureRecord(after, version, facts)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    private class CountingStream(source: InputStream) : FilterInputStream(source) {
        var count = 0L
            private set

        override fun read(): Int = super.read().also { if (it >= 0) count++ }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            super.read(buffer, offset, length).also { if (it > 0) count += it }
    }
}
