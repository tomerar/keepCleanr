// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.gallery

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream

/** The real platform JPEG path: header policy, ImageDecoder orientation and sampling, canonical features. */
@RunWith(AndroidJUnit4::class)
class BoundedJpegDecoderDeviceTest {
    @Test
    fun everyOrientationGivesTheUprightFeaturesOnce(): Unit = runBlocking {
        val reference = facts(BoundedJpegDecoder.decodeBytes(JpegFixtures.oriented(1)))
        for (orientation in 1..8) {
            val facts = facts(BoundedJpegDecoder.decodeBytes(JpegFixtures.oriented(orientation)))
            assertEquals("dHash orientation $orientation", reference.dHash, facts.dHash)
            assertEquals("pHash orientation $orientation", reference.pHash, facts.pHash)
            assertEquals("aspect orientation $orientation", 2.0, facts.aspect, 0.0)
        }
    }

    @Test
    fun boundedDecodeAndRejections(): Unit = runBlocking {
        val large = facts(BoundedStillDecoder.decode(ByteArrayInputStream(JpegFixtures.encode(1024, 768))))
        assertEquals(1024L * 768, large.quality.usablePixels)
        val valid = JpegFixtures.encode(64, 32)
        assertEquals(
            StillDecode.Skipped(StillSkip.DAMAGED),
            BoundedJpegDecoder.decodeBytes(valid.copyOf(valid.size / 2)),
        )
        assertEquals(StillDecode.Skipped(StillSkip.UNSUPPORTED_VARIANT), BoundedJpegDecoder.decodeBytes(valid + 0))
        assertTrue(BoundedStillDecoder.decode(ByteArrayInputStream(JpegFixtures.png())) is StillDecode.Decoded)
    }

    private fun facts(result: StillDecode) = (result as StillDecode.Decoded).facts
}
