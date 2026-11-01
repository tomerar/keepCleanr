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
    fun `isolates a Hebrew file name`() {
        Bidi.isolate("תמונה של אמא 🎂.jpg") shouldBe "⁨תמונה של אמא 🎂.jpg⁩"
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
