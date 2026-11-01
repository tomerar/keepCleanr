// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.move

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/** Review Low / SEC-3: manifests from the drive are read line by line with a line and a total cap. */
class ManifestReadTest {
    @Test
    fun `lines are streamed, an overlong line is dropped without buffering it, the rest is kept`() {
        val big = "x".repeat(5 * 1024 * 1024)
        val text = "a\n$big\nb\r\nc"
        Manifest.readLines(ByteArrayInputStream(text.toByteArray())) shouldBe listOf("a", "b", "c")
    }

    @Test
    fun `a manifest over the total cap is unusable, however it is shaped`() {
        val endless = object : InputStream() {
            var n = 0L

            override fun read(): Int = if (n++ < Manifest.MAX_BYTES + 10L) 'y'.code else -1
        }
        Manifest.readLines(endless) shouldBe null
        (endless.n <= Manifest.MAX_BYTES + 16 * 1024L) shouldBe true // stops right after the cap (+ read-ahead)
    }

    @Test
    fun `the cap fits a large photo library but not much more (16 MB)`() {
        Manifest.MAX_BYTES shouldBe 16 * 1024 * 1024
    }
}
