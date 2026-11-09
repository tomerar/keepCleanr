// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.gallery

import io.github.tomerar.keepclear.core.safety.ProtectedPaths
import io.github.tomerar.keepclear.engine.classify.ClassifiedItem
import io.github.tomerar.keepclear.engine.gallery.FeatureRecord
import io.github.tomerar.keepclear.engine.gallery.FeatureVersion
import io.github.tomerar.keepclear.engine.gallery.GalleryRun
import io.github.tomerar.keepclear.engine.gallery.SourceVersion

/**
 * The live gate every read must ask before and after touching a source (C212): the current run, permission epoch,
 * scope, exclusions and kept state. It grants nothing by itself.
 */
fun interface GalleryGate {
    suspend fun allows(run: GalleryRun, item: ClassifiedItem): Boolean
}

/** Why a still image was not analysed although it was readable in principle (E-G04, E-G12, E-G13). */
enum class UnsupportedImage { FORMAT, VARIANT, FILE_TOO_LARGE, IMAGE_TOO_LARGE, DAMAGED }

/** Outcome of one bounded read. Only [Read] is a completed record; everything else is skipped with a reason. */
sealed interface GalleryRead {
    data class Read(val record: FeatureRecord) : GalleryRead

    data class Unsupported(val reason: UnsupportedImage) : GalleryRead

    /** Gone, changed, refused by the gate or unreadable right now. */
    data object Unavailable : GalleryRead
}

/**
 * Metadata-only observation of one scanner-provided item (no content is opened). A cached record is looked up only
 * with the version this returns, so an item outside the live scope can never be served from the cache (AC-G1).
 */
fun interface GallerySourceObserver {
    suspend fun observe(
        item: ClassifiedItem,
        run: GalleryRun,
        protectedPaths: ProtectedPaths,
        gate: GalleryGate,
    ): SourceVersion?
}

/** One bounded, cancellable decode of one item into a feature record (GAL-15: only completed records). */
fun interface GalleryImageReader {
    suspend fun read(
        item: ClassifiedItem,
        run: GalleryRun,
        version: FeatureVersion,
        protectedPaths: ProtectedPaths,
        gate: GalleryGate,
    ): GalleryRead
}
