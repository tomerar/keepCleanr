// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.hash

import java.io.InputStream
import java.security.MessageDigest

/** A digest fed chunk by chunk while [Sha256.copy] copies, so a second digest needs no second read of the source. */
interface StreamingDigest {
    fun update(bytes: ByteArray, offset: Int, length: Int)

    /** Lowercase hex of everything fed so far. Call once, at the end. */
    fun hex(): String
}

/**
 * Dropbox's `content_hash` (M17, ADR 0011): split the bytes into 4 MiB blocks, SHA-256 each block, concatenate
 * the binary block digests and SHA-256 that. Lowercase hex, like Dropbox's API.
 * Definition: https://docs.dropboxapi.com/dropbox-api/docs/technical-reference/content-hash
 *
 * Consequences of the definition (the known-answer tests rely on them): an empty input has no blocks, so its hash
 * is the SHA-256 of nothing (`e3b0c442…b855`); an input of at most one block hashes to SHA-256(SHA-256(input)).
 */
class DropboxContentHash : StreamingDigest {
    private val block = MessageDigest.getInstance("SHA-256")
    private val overall = MessageDigest.getInstance("SHA-256")
    private var inBlock = 0
    private var done = false

    override fun update(bytes: ByteArray, offset: Int, length: Int) {
        check(!done) { "already finished" }
        var off = offset
        var left = length
        while (left > 0) {
            val n = minOf(left, BLOCK_SIZE - inBlock)
            block.update(bytes, off, n)
            inBlock += n
            off += n
            left -= n
            if (inBlock == BLOCK_SIZE) {
                overall.update(block.digest())
                inBlock = 0
            }
        }
    }

    override fun hex(): String {
        check(!done) { "already finished" }
        done = true
        if (inBlock > 0) overall.update(block.digest())
        return overall.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** Dropbox's block size: 4 MiB. */
        const val BLOCK_SIZE = 4 * 1024 * 1024

        fun of(bytes: ByteArray): String = DropboxContentHash().apply { update(bytes, 0, bytes.size) }.hex()

        fun of(input: InputStream): String {
            val h = DropboxContentHash()
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                h.update(buffer, 0, n)
            }
            return h.hex()
        }
    }
}
