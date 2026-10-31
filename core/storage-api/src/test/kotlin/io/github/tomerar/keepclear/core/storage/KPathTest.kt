// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.storage

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class KPathTest {
    @Test
    fun `parent, name and child`() {
        val p = KPath.Local("/storage/emulated/0/DCIM/Camera/IMG_1.jpg")
        p.name shouldBe "IMG_1.jpg"
        p.parent shouldBe KPath.Local("/storage/emulated/0/DCIM/Camera")
        KPath.Local("/").parent shouldBe null
        KPath.Local("/a").parent shouldBe KPath.Local("/")
        KPath.Local("/").child("a") shouldBe KPath.Local("/a")
    }

    @Test
    fun `dot segments, double slashes and NUL are rejected`() {
        for (bad in listOf("relative", "/a/../b", "/a/./b", "/a//b", "/a/", "/a\u0000b")) {
            shouldThrow<IllegalArgumentException> { KPath.Local(bad) }
        }
        shouldThrow<IllegalArgumentException> { KPath.Local("/a").child("..") }
        shouldThrow<IllegalArgumentException> { KPath.Local("/a").child("b/c") }
    }

    @Test
    fun `isInside respects segment boundaries`() {
        KPath.Local("/sd/WhatsApp/Databases/x").isInside(KPath.Local("/sd/WhatsApp")) shouldBe true
        KPath.Local("/sd/WhatsAppX/y").isInside(KPath.Local("/sd/WhatsApp")) shouldBe false
    }

    @Test
    fun `names longer than 255 UTF-8 bytes are invalid`() {
        KPath.isValidName("א".repeat(127)) shouldBe true // 254 bytes
        KPath.isValidName("א".repeat(128)) shouldBe false // 256 bytes
    }
}
