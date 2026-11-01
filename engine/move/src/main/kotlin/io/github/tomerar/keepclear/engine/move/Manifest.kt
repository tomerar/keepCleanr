// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.move

import io.github.tomerar.keepclear.core.hash.Sha256
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream

/**
 * Backup manifest (MOVE-8, MIG-2, SEC-3): `keepclear-manifest.jsonl` (header line + one line per file)
 * and `SHA256SUMS` in GNU coreutils format, both inside the device folder so `sha256sum -c SHA256SUMS`
 * works from there. Existing files are merged; invalid lines are dropped, never trusted.
 */
object Manifest {
    const val JSONL = "keepclear-manifest.jsonl"
    const val SUMS = "SHA256SUMS"
    const val VERSION = 1

    /** ~100,000 files at ~150 bytes a line. A larger manifest is left alone and a per-job one is written. */
    const val MAX_BYTES = 16 * 1024 * 1024
    private const val MAX_LINE = 8 * 1024

    /**
     * Streams an untrusted manifest line by line (review Low, SEC-3): each line is kept only up to [MAX_LINE]
     * bytes (longer lines are skipped, never buffered), and reading stops as soon as [MAX_BYTES] is passed, which
     * returns null (unusable). `\r\n` endings are accepted.
     */
    fun readLines(input: InputStream): List<String>? {
        val lines = ArrayList<String>()
        val buf = java.io.ByteArrayOutputStream()
        var overlong = false
        var total = 0L
        val stream = input.buffered()

        fun endLine() {
            if (!overlong) {
                val s = buf.toString(Charsets.UTF_8).removeSuffix("\r")
                lines += s
            }
            buf.reset()
            overlong = false
        }
        while (true) {
            val b = stream.read()
            if (b < 0) break
            if (++total > MAX_BYTES) return null
            if (b == '\n'.code) {
                endLine()
            } else if (!overlong) {
                if (buf.size() >= MAX_LINE) {
                    overlong = true
                    buf.reset()
                } else {
                    buf.write(b)
                }
            }
        }
        if (buf.size() > 0 || overlong) endLine()
        return lines
    }

    @Serializable
    data class Header(val v: Int = VERSION, val app: String = "KeepClear")

    @Serializable
    data class Line(
        val rel: String,
        val src: String,
        val size: Long,
        val mtime: Long,
        val sha256: String,
        val verifiedAt: Long,
        val nameChanged: Boolean,
    )

    private val json = Json { ignoreUnknownKeys = false }

    /** Path of a file relative to the manifest folder; null if it escapes it (SEC-3). */
    fun relToRoot(relTarget: String, rootRel: String): String? {
        if (!relTarget.startsWith("$rootRel/")) return null
        val inner = relTarget.removePrefix("$rootRel/")
        if (inner.split('/').any { it.isEmpty() || it == "." || it == ".." } || '\u0000' in inner ||
            '\n' in inner
        ) {
            return null
        }
        return inner
    }

    fun mergeSums(existing: String?, verified: List<MoveFile>, rootRel: String): String =
        mergeSums(existing?.lines(), verified, rootRel)

    fun mergeSums(existing: List<String>?, verified: List<MoveFile>, rootRel: String): String {
        val entries = LinkedHashMap<String, String>()
        existing?.forEach { line ->
            val m = SUM_LINE.matchEntire(line) ?: return@forEach
            val path = m.groupValues[2]
            if (path.split('/').none { it == ".." || it.isEmpty() } &&
                !path.startsWith("/")
            ) {
                entries[path] = m.groupValues[1]
            }
        }
        verified.forEach { f -> relToRoot(f.relTarget, rootRel)?.let { entries[it] = f.sourceSha256!! } }
        return entries.entries.joinToString("") { (path, sha) -> "$sha  $path\n" }
    }

    fun mergeJsonl(existing: String?, verified: List<MoveFile>, rootRel: String): String =
        mergeJsonl(existing?.lines(), verified, rootRel)

    fun mergeJsonl(existing: List<String>?, verified: List<MoveFile>, rootRel: String): String {
        val lines = LinkedHashMap<String, Line>()
        existing?.drop(1)?.forEach { raw ->
            parseLine(raw, rootRel)?.let { lines[it.rel] = it }
        }
        verified.forEach { f ->
            val rel = relToRoot(f.relTarget, rootRel) ?: return@forEach
            lines[rel] =
                Line(rel, f.sourcePath, f.sourceSize, f.sourceMtime, f.sourceSha256!!, f.verifiedAt ?: 0, f.nameChanged)
        }
        val header = json.encodeToString(Header.serializer(), Header())
        return header + "\n" + lines.values.joinToString("") { json.encodeToString(Line.serializer(), it) + "\n" }
    }

    /** Strict parse of one manifest line (SEC-3); null for anything suspicious. */
    fun parseLine(raw: String, rootRel: String): Line? {
        if (raw.isBlank() || raw.length > MAX_LINE) return null
        val line = runCatching { json.decodeFromString(Line.serializer(), raw) }.getOrNull() ?: return null
        if (!Sha256.isValidHex(line.sha256) || line.size < 0) return null
        if (relToRoot("$rootRel/${line.rel}", rootRel) == null) return null
        return line
    }

    private val SUM_LINE = Regex("^([0-9a-f]{64})  (\\S.*)$")
}
