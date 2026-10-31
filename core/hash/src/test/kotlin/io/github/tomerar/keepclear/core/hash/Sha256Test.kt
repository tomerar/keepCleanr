// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.hash

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

class Sha256Test {
    @Test
    fun `known vectors match sha256sum`() {
        Sha256.of(ByteArray(0)) shouldBe "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        Sha256.of("abc".toByteArray()) shouldBe "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    }

    @Test
    fun `stream and copy agree with one-shot hashing`() {
        val data = ByteArray(3_000_001) { (it % 251).toByte() }
        val out = ByteArrayOutputStream()
        val (n, hash) = Sha256.copy(data.inputStream(), out)
        n shouldBe data.size.toLong()
        out.toByteArray().contentEquals(data) shouldBe true
        hash shouldBe Sha256.of(data)
        Sha256.of(data.inputStream()) shouldBe hash
    }

    @Test
    fun `hex validation`() {
        Sha256.isValidHex("a".repeat(64)) shouldBe true
        Sha256.isValidHex("A".repeat(64)) shouldBe false
        Sha256.isValidHex("a".repeat(63)) shouldBe false
    }
}
