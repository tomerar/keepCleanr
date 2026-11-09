// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.gallery

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
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

/** Outcome of one guarded read. Only [Read] carries features; the other two never count as "analyzed". */
internal sealed interface GuardedStill {
    data class Read(val record: FeatureRecord) : GuardedStill

    /** The current, in-scope source is a format or variant the bounded decoders do not analyze, or is damaged. */
    data class Unsupported(val reason: StillSkip) : GuardedStill

    /** Out of scope, changed, inaccessible or cancelled-by-gate; retry only in a later authorized run. */
    data object Unavailable : GuardedStill
}

/**
 * Internal orchestration only; it has no app caller or authority to choose a scope. The decoder is chosen by the
 * content signature (C204), never by the file name or MediaStore MIME type.
 */
internal class GuardedMediaStillRead(
    private val resolver: ContentResolver,
    private val observations: MediaStoreObservation,
    private val openStream: (Uri) -> InputStream? = resolver::openInputStream,
) {
    /** The gate must check current run, permission epoch, scope, exclusions and kept state on every invocation. */
    suspend fun read(
        item: ClassifiedItem,
        run: GalleryRun,
        version: FeatureVersion,
        protectedPaths: ProtectedPaths,
        gate: suspend (GalleryRun, ClassifiedItem) -> Boolean,
    ): GuardedStill = withContext(Dispatchers.IO) {
        if (version != FeatureVersion.V1) return@withContext GuardedStill.Unavailable
        val ref = item.item.ref as? KPath.Media ?: return@withContext GuardedStill.Unavailable
        val before = observations.observe(item, run, protectedPaths, gate)
            ?: return@withContext GuardedStill.Unavailable
        currentCoroutineContext().ensureActive()
        try {
            val outcome = if (before.size > BoundedStillDecoder.MAX_BYTES) {
                // Never open a source that the byte cap would refuse anyway.
                StillDecode.Skipped(StillSkip.TOO_LARGE_FILE)
            } else {
                val uri = ContentUris.withAppendedId(MediaStore.Files.getContentUri(ref.volume), ref.id)
                val stream = openStream(uri)?.let(::CountingStream) ?: return@withContext GuardedStill.Unavailable
                val decoded = BoundedStillDecoder.decode(stream)
                // Every outcome must come from exactly the observed bytes; a grown or shrunk file is a changed source.
                if (stream.count != before.size) return@withContext GuardedStill.Unavailable
                decoded
            }
            currentCoroutineContext().ensureActive()
            val after = observations.observe(item, run, protectedPaths, gate)
                ?: return@withContext GuardedStill.Unavailable
            if (before != after) return@withContext GuardedStill.Unavailable
            when (outcome) {
                is StillDecode.Decoded -> GuardedStill.Read(FeatureRecord(after, version, outcome.facts))
                is StillDecode.Skipped -> GuardedStill.Unsupported(outcome.reason)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            GuardedStill.Unavailable
        } catch (_: RuntimeException) {
            GuardedStill.Unavailable
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
