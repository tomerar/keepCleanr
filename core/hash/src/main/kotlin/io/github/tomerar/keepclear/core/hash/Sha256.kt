// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.hash

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Streaming SHA-256 (ADR 0007). Hex output is lowercase, like `sha256sum`. */
object Sha256 {
    private const val BUFFER = 1 shl 20

    fun of(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
        return digest.digest().toHex()
    }

    fun of(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    /** Copies [input] to [output] and returns the SHA-256 of the bytes copied (single read, MOVE-6). */
    fun copy(input: InputStream, output: OutputStream, onProgress: (Long) -> Unit = {}): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
            output.write(buffer, 0, n)
            total += n
            onProgress(total)
        }
        return total to digest.digest().toHex()
    }

    fun isValidHex(value: String): Boolean = value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
