// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.enrich

import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.core.storage.StorageGateway
import io.github.tomerar.keepclear.core.storage.Streams.readUpTo
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads only a zip's **central directory** (SEC-2): never extracts, never decompresses. Hostile or
 * unusual archives (ZIP64, too many entries, oversized directory, unsafe names) return null, which the
 * caller treats as "not provably extracted" (REVIEW).
 */
object ZipCentralDirectory {
    const val MAX_ENTRIES = 100_000
    const val MAX_DIRECTORY_BYTES = 4L * 1024 * 1024
    private const val EOCD_SIG = 0x06054b50
    private const val CEN_SIG = 0x02014b50
    private const val EOCD_MIN = 22
    private const val MAX_COMMENT = 65_535

    data class Entry(val name: String, val size: Long, val crc32: Long, val isDirectory: Boolean)

    suspend fun read(gateway: StorageGateway, path: KPath, fileSize: Long): List<Entry>? {
        if (fileSize < EOCD_MIN) return null
        val tailLength = minOf(fileSize, (EOCD_MIN + MAX_COMMENT).toLong()).toInt()
        val tail = readRange(gateway, path, fileSize - tailLength, tailLength) ?: return null
        val eocd = (tail.size - EOCD_MIN downTo 0).firstOrNull { le32(tail, it) == EOCD_SIG } ?: return null
        val count = le16(tail, eocd + 10)
        val cdSize = le32(tail, eocd + 12).toLong() and 0xFFFFFFFFL
        val cdOffset = le32(tail, eocd + 16).toLong() and 0xFFFFFFFFL
        if (count == 0xFFFF || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) return null // ZIP64
        if (count > MAX_ENTRIES || cdSize > MAX_DIRECTORY_BYTES || cdOffset + cdSize > fileSize) return null
        val cd = readRange(gateway, path, cdOffset, cdSize.toInt()) ?: return null
        val entries = ArrayList<Entry>(count)
        var p = 0
        repeat(count) {
            if (p + 46 > cd.size || le32(cd, p) != CEN_SIG) return null
            val crc = le32(cd, p + 16).toLong() and 0xFFFFFFFFL
            val size = le32(cd, p + 24).toLong() and 0xFFFFFFFFL
            val nameLen = le16(cd, p + 28)
            val extraLen = le16(cd, p + 30)
            val commentLen = le16(cd, p + 32)
            if (p + 46 + nameLen > cd.size) return null
            val name = String(cd, p + 46, nameLen, Charsets.UTF_8)
            entries += Entry(name, size, crc, name.endsWith("/"))
            p += 46 + nameLen + extraLen + commentLen
        }
        return entries
    }

    /**
     * Normalised relative path of an entry, or null if it is unsafe (Zip Slip): absolute, drive
     * letter, `..`, `.`, NUL, empty segments.
     */
    fun safeRelativePath(name: String): String? {
        val n = name.replace('\\', '/')
        if (n.isEmpty() || n.startsWith("/") || '\u0000' in n) return null
        if (n.length >= 2 && n[1] == ':') return null
        val segments = n.trimEnd('/').split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) return null
        return segments.joinToString("/")
    }

    private suspend fun readRange(gateway: StorageGateway, path: KPath, offset: Long, length: Int): ByteArray? =
        gateway.openRead(path).use { input ->
            var skipped = 0L
            while (skipped < offset) {
                val n = input.skip(offset - skipped)
                if (n <= 0) {
                    if (input.read() < 0) return null
                    skipped++
                } else {
                    skipped += n
                }
            }
            val bytes = input.readUpTo(length)
            if (bytes.size == length) bytes else null
        }

    private fun le16(b: ByteArray, i: Int) =
        ByteBuffer.wrap(b, i, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF

    private fun le32(b: ByteArray, i: Int) = ByteBuffer.wrap(b, i, 4).order(ByteOrder.LITTLE_ENDIAN).int
}
