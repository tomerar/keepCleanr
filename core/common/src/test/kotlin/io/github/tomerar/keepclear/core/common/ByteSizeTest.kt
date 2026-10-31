// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.common

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.Locale

class ByteSizeTest {
    private fun en(b: Long) = Bidi.strip(ByteSize.format(b, Locale.US))

    private fun he(b: Long) = Bidi.strip(ByteSize.format(b, Locale.forLanguageTag("he-IL")))

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
