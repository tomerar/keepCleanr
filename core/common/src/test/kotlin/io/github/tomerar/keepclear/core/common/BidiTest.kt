// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.common

import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class BidiTest {
    @Test
    fun `a short drive name stays on one line, a long one may wrap (H2 item 12)`() {
        Bidi.name("SanDisk USB") shouldBe "⁨SanDisk\u00A0USB⁩"
        Bidi.name(" ‏Samsung T7 Shield ") shouldBe "⁨Samsung\u00A0T7\u00A0Shield⁩"
        val long = "Very long network share name on the NAS"
        Bidi.name(long) shouldBe "⁨$long⁩"
    }

    @Test
    fun `isolates a Hebrew file name`() {
        Bidi.isolate("תמונה של אמא 🎂.jpg") shouldBe "⁨תמונה של אמא 🎂.jpg⁩"
    }

    @Test
    fun `a Hebrew count that starts with digits is kept together with right-to-left marks (design QA 8)`() {
        Bidi.isolate("4,983 קבצים") shouldBe "${Bidi.RLM}4,983 קבצים${Bidi.RLM}"
        Bidi.isolate("2 קבצים") shouldBe "${Bidi.RLM}2 קבצים${Bidi.RLM}"
        // Stray marks are removed first, so wrapping twice gives the same result.
        Bidi.isolate(Bidi.isolate("4,983 קבצים")) shouldBe Bidi.isolate("4,983 קבצים")
        // Everything else keeps its isolate: left-to-right values, digits only, and Hebrew that starts with a letter.
        Bidi.isolate("7.9GB") shouldBe "${Bidi.FSI}7.9GB${Bidi.PDI}"
        Bidi.isolate("4,983 files") shouldBe "${Bidi.FSI}4,983 files${Bidi.PDI}"
        Bidi.isolate("4,983") shouldBe "${Bidi.FSI}4,983${Bidi.PDI}"
        Bidi.isolate("קובץ אחד") shouldBe "${Bidi.FSI}קובץ אחד${Bidi.PDI}"
    }

    @Test
    fun `paths are isolated left to right`() {
        Bidi.isolateLtr("DCIM/Camera/IMG_1.jpg") shouldBe "⁦DCIM/Camera/IMG_1.jpg⁩"
    }

    @Test
    fun `stray controls are removed so isolates stay balanced`() {
        Bidi.isolate("a‮b⁩c") shouldBe "⁨abc⁩"
    }

    @Test
    fun `isolate always has exactly one opening and one closing mark`(): Unit = runBlocking {
        checkAll(Arb.string(0..40)) { s ->
            val out = Bidi.isolate(s)
            out.count { it == Bidi.FSI } shouldBe 1
            out.count { it == Bidi.PDI } shouldBe 1
            out.first() shouldBe Bidi.FSI
            out.last() shouldBe Bidi.PDI
        }
    }

    @Test
    fun `a Hebrew file name keeps its number and extension together (review PR C)`() {
        Bidi.fileName("צילום מסך 2023.png") shouldBe "${Bidi.FSI}צילום מסך ${Bidi.LRI}2023.png${Bidi.PDI}${Bidi.PDI}"
        Bidi.fileName("IMG_1.jpg") shouldBe "${Bidi.FSI}${Bidi.LRI}IMG_1.jpg${Bidi.PDI}${Bidi.PDI}"
        Bidi.fileName("תמונה.jpg") shouldBe Bidi.isolate("תמונה.jpg")
        Bidi.fileName("README") shouldBe Bidi.isolate("README")
    }

    @Test
    fun `file names always have balanced isolates`(): Unit = runBlocking {
        checkAll(Arb.string(0..40)) { s ->
            val out = Bidi.fileName(s)
            out.count { it == Bidi.FSI || it == Bidi.LRI } shouldBe out.count { it == Bidi.PDI }
        }
    }
}
