// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.gallery

import android.content.Context

/**
 * The one adapter binding the coordinator's read ports to today's guarded MediaStore reads (C210). The PNG-only
 * read cannot say why it refused, so a non-PNG item is reported as an unsupported format before anything is
 * opened and any other refusal is [GalleryRead.Unavailable]. The JPEG/still dispatch replaces only this file.
 */
internal class MediaStoreGalleryReads(
    private val observation: MediaStoreObservation,
    private val guarded: GuardedMediaPngRead,
) {
    val observer = GallerySourceObserver { item, run, protectedPaths, gate ->
        observation.observe(item, run, protectedPaths) { r, i -> gate.allows(r, i) }
    }

    val reader = GalleryImageReader { item, run, version, protectedPaths, gate ->
        if (item.item.mime != "image/png") return@GalleryImageReader GalleryRead.Unsupported(UnsupportedImage.FORMAT)
        guarded.read(item, run, version, protectedPaths) { r, i -> gate.allows(r, i) }
            ?.let { GalleryRead.Read(it) } ?: GalleryRead.Unavailable
    }

    companion object {
        fun of(context: Context): MediaStoreGalleryReads {
            val observation = MediaStoreObservation(context)
            return MediaStoreGalleryReads(observation, GuardedMediaPngRead(context.contentResolver, observation))
        }
    }
}
