// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.gallery

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaStoreGalleryReadsTest {
    @Test
    fun `every still-decoder skip reason keeps its own coordinator reason`() {
        val mapped = StillSkip.entries.associateWith(MediaStoreGalleryReads::unsupported)
        assertEquals(StillSkip.entries.size, mapped.values.toSet().size)
        assertEquals(UnsupportedImage.FORMAT, mapped[StillSkip.UNSUPPORTED_FORMAT])
        assertEquals(UnsupportedImage.VARIANT, mapped[StillSkip.UNSUPPORTED_VARIANT])
        assertEquals(UnsupportedImage.FILE_TOO_LARGE, mapped[StillSkip.TOO_LARGE_FILE])
        assertEquals(UnsupportedImage.IMAGE_TOO_LARGE, mapped[StillSkip.TOO_LARGE_IMAGE])
        assertEquals(UnsupportedImage.DAMAGED, mapped[StillSkip.DAMAGED])
    }
}
