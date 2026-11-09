// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.gallery

import android.content.Context

/**
 * The one adapter binding the coordinator's read ports to the guarded MediaStore still read (C210, C204). The read
 * chooses the PNG or JPEG decoder from the content signature, so the item's name or MIME type is never trusted here;
 * its explicit unsupported reasons map one to one to [UnsupportedImage], and any other refusal is
 * [GalleryRead.Unavailable].
 */
internal class MediaStoreGalleryReads(
    private val observation: MediaStoreObservation,
    private val guarded: GuardedMediaStillRead,
) {
    val observer = GallerySourceObserver { item, run, protectedPaths, gate ->
        observation.observe(item, run, protectedPaths) { r, i -> gate.allows(r, i) }
    }

    val reader = GalleryImageReader { item, run, version, protectedPaths, gate ->
        val outcome = guarded.read(item, run, version, protectedPaths) { r, i -> gate.allows(r, i) }
        when (outcome) {
            is GuardedStill.Read -> GalleryRead.Read(outcome.record)
            is GuardedStill.Unsupported -> GalleryRead.Unsupported(unsupported(outcome.reason))
            GuardedStill.Unavailable -> GalleryRead.Unavailable
        }
    }

    companion object {
        fun of(context: Context): MediaStoreGalleryReads {
            val observation = MediaStoreObservation(context)
            return MediaStoreGalleryReads(observation, GuardedMediaStillRead(context.contentResolver, observation))
        }

        fun unsupported(reason: StillSkip): UnsupportedImage = when (reason) {
            StillSkip.UNSUPPORTED_FORMAT -> UnsupportedImage.FORMAT
            StillSkip.UNSUPPORTED_VARIANT -> UnsupportedImage.VARIANT
            StillSkip.TOO_LARGE_FILE -> UnsupportedImage.FILE_TOO_LARGE
            StillSkip.TOO_LARGE_IMAGE -> UnsupportedImage.IMAGE_TOO_LARGE
            StillSkip.DAMAGED -> UnsupportedImage.DAMAGED
        }
    }
}
