// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.common

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.Locale

class ByteSizeTest {
    private fun en(b: Long) = Bidi.strip(ByteSize.format(b, Locale.US))

    private fun he(b: Long) = Bidi.strip(ByteSize.format(b, Locale.forLanguageTag("he-IL")))

    @Test
    fun `a negative or corrupt size never crashes the screen (E13)`() {
        ByteSize.format(-1, java.util.Locale.US) shouldBe ByteSize.format(0, java.util.Locale.US)
        ByteSize.format(Long.MIN_VALUE, java.util.Locale.US) shouldBe ByteSize.format(0, java.util.Locale.US)
    }

    @Test
    fun `decimal units like Android Settings`() {
        en(0) shouldBe "0 B"
        en(999) shouldBe "999 B"
        en(1_500) shouldBe "1.5 KB"
        en(412_000_000) shouldBe "412 MB"
        en(16_800_000_000) shouldBe "16.8 GB"
        en(75_200_000_000) shouldBe "75.2 GB"
        en(240_000_000_000) shouldBe "240.0 GB"
        en(1_200_000_000_000) shouldBe "1.2 TB"
    }

    @Test
    fun `progress shows the done part in the unit of the total (H1 item 4)`() {
        fun he(d: Long, t: Long) = ByteSize.progress(d, t, Locale.forLanguageTag("he-IL")).let {
            Bidi.strip(it.first) to Bidi.strip(it.second)
        }
        he(12_900_000_000, 31_000_000_000) shouldBe ("12.9" to "31.0GB")
        // 900 MB of 31 GB is 0.9 (GB), not "900".
        he(900_000_000, 31_000_000_000) shouldBe ("0.9" to "31.0GB")
        he(450_000_000, 900_000_000) shouldBe ("450" to "900MB")
        // Clamped: never more than the total, never negative.
        he(40_000_000_000, 31_000_000_000) shouldBe ("31.0" to "31.0GB")
        he(-5, 31_000_000_000) shouldBe ("0.0" to "31.0GB")
        ByteSize.progress(1, 2, Locale.US).first.first() shouldBe Bidi.LRI
    }

    @Test
    fun `hebrew writes the unit without a space`() {
        he(16_800_000_000) shouldBe "16.8GB"
        he(412_000_000) shouldBe "412MB"
    }

    @Test
    fun `result is an LTR isolate`() {
        ByteSize.format(1, Locale.US).first() shouldBe Bidi.LRI
        ByteSize.format(1, Locale.US).last() shouldBe Bidi.PDI
    }
}
