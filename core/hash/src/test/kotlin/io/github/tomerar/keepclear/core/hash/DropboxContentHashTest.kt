// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.hash

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Random

/**
 * M17: Dropbox's `content_hash`. The expected values follow from the definition (4 MiB blocks, SHA-256 of the
 * concatenated block SHA-256s) and were computed independently with Python's hashlib:
 * `sha256(b"".join(sha256(d[i:i+4 MiB]).digest() for i in range(0, len(d), 4 MiB))).hexdigest()`.
 */
class DropboxContentHashTest {
    private val block = DropboxContentHash.BLOCK_SIZE

    private fun pattern(size: Int) = ByteArray(size) { (it % 251).toByte() }

    @Test
    fun `known answers`() {
        // No blocks: the SHA-256 of an empty concatenation.
        DropboxContentHash.of(ByteArray(0)) shouldBe "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        // One block: SHA-256(SHA-256("a")), the well-known double SHA-256 of "a".
        DropboxContentHash.of("a".toByteArray()) shouldBe
            "bf5d3affb73efd2ec6c36ad3112dd933efed63c4e1cbffcfa88e2759c144f2d8"
        // Exactly one block, and one block plus one byte (two blocks).
        DropboxContentHash.of(pattern(block)) shouldBe
            "b9654428408015906b44a00935b70af33830aa344b780b0eabd535a133150d04"
        DropboxContentHash.of(pattern(block + 1)) shouldBe
            "4a6cc0a344febaa07772e7c974834b2fb1d24594d4ba15f27c97a54699709f44"
    }

    @Test
    fun `matches the definition for sizes around block boundaries`() {
        fun reference(d: ByteArray): String {
            val all = MessageDigest.getInstance("SHA-256")
            var i = 0
            while (i < d.size) {
                val end = minOf(d.size, i + block)
                all.update(MessageDigest.getInstance("SHA-256").digest(d.copyOfRange(i, end)))
                i = end
            }
            return all.digest().joinToString("") { "%02x".format(it) }
        }
        listOf(1, block - 1, block, block + 1, 2 * block, 2 * block + 7).forEach { size ->
            val d = pattern(size)
            DropboxContentHash.of(d) shouldBe reference(d)
            DropboxContentHash.of(d.inputStream()) shouldBe reference(d)
        }
    }

    @Test
    fun `is not the plain SHA-256 of a file larger than one block`() {
        val d = pattern(block + 1)
        DropboxContentHash.of(d) shouldNotBe Sha256.of(d)
    }

    @Test
    fun `any split of the input gives the same hash, and copy computes both digests in one pass`() {
        val d = pattern(block + 12_345)
        val expected = DropboxContentHash.of(d)
        val random = Random(17)
        repeat(20) {
            val h = DropboxContentHash()
            var i = 0
            while (i < d.size) {
                val n = minOf(1 + random.nextInt(3 * 1024 * 1024), d.size - i)
                h.update(d, i, n)
                i += n
            }
            h.hex() shouldBe expected
        }
        val extra = DropboxContentHash()
        val out = ByteArrayOutputStream()
        val (n, sha) = Sha256.copy(d.inputStream(), out, extra)
        n shouldBe d.size.toLong()
        sha shouldBe Sha256.of(d)
        extra.hex() shouldBe expected
    }
}
