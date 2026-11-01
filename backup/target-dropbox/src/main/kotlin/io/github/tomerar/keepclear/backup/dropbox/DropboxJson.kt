// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.backup.dropbox

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Locale

/** Dropbox's answer wasn't valid, bounded JSON of the expected shape (SEC: every answer is untrusted). */
internal class DropboxJsonException(message: String) : Exception(message)

/** One file or folder as Dropbox describes it (`FileMetadata` / `FolderMetadata`), already checked. */
internal data class DbxEntry(
    val isFolder: Boolean,
    val name: String,
    /** Dropbox's lower-cased full path, e.g. `/keepclear/phone/img.jpg`. */
    val pathLower: String,
    val size: Long,
    val mtimeMillis: Long,
    /** Files only: the revision, used so a delete only ever removes the version KeepClear saw. */
    val rev: String?,
    /** Files only: the server's `content_hash` when well-formed (64 lowercase hex), else null. */
    val contentHash: String?,
)

/**
 * Strict, bounded JSON for the Dropbox API (M17c). kotlinx.serialization's tree parser, plus what it doesn't
 * check by itself: valid UTF-8, a nesting limit (a deeply nested answer can't exhaust the stack), and field types.
 */
internal object DropboxJson {
    /** The default configuration is strict: no lenient syntax, no NaN, no trailing commas. */
    private val json = Json

    /** Deepest nesting accepted; Dropbox's answers use fewer than 8 levels. */
    const val MAX_DEPTH = 32

    private val HEX64 = Regex("^[0-9a-f]{64}$")
    private val REV = Regex("^[0-9a-f]{9,64}$")

    fun parse(bytes: ByteArray): JsonObject {
        val text = strictUtf8(bytes) ?: throw DropboxJsonException("not UTF-8")
        checkDepth(text)
        val element = try {
            json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            throw DropboxJsonException("bad JSON")
        } catch (e: IllegalArgumentException) {
            throw DropboxJsonException("bad JSON")
        }
        return element as? JsonObject ?: throw DropboxJsonException("not a JSON object")
    }

    fun encode(element: JsonElement): ByteArray = json.encodeToString(JsonElement.serializer(), element).toByteArray()

    /**
     * JSON for the `Dropbox-API-Arg` header. HTTP header values are ASCII, so (as Dropbox asks) every character
     * outside printable ASCII is written as `\uXXXX`; JSON already escapes control characters, so no CR/LF can
     * reach the header.
     */
    fun header(element: JsonElement): String {
        val text = json.encodeToString(JsonElement.serializer(), element)
        val sb = StringBuilder(text.length + 16)
        for (c in text) {
            if (c.code in 0x20..0x7e) sb.append(c) else sb.append("\\u").append("%04x".format(c.code))
        }
        return sb.toString()
    }

    /** The `error_summary` of an error answer (e.g. `path/not_found/..`), or "" when there is none. */
    fun errorSummary(bytes: ByteArray?): String {
        if (bytes == null) return ""
        return runCatching { parse(bytes).string("error_summary") }.getOrNull()?.take(512) ?: ""
    }

    private fun strictUtf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    private fun checkDepth(text: String) {
        var depth = 0
        var inString = false
        var escaped = false
        for (c in text) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{', '[' -> if (++depth > MAX_DEPTH) throw DropboxJsonException("nested too deeply")
                '}', ']' -> depth--
            }
        }
    }

    fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

    fun JsonObject.bool(name: String): Boolean? =
        (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()

    fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject

    fun JsonObject.array(name: String): JsonArray? = this[name] as? JsonArray

    /** Lower-cases a path the way KeepClear compares Dropbox paths (both sides, so they always agree with us). */
    fun fold(path: String): String = path.lowercase(Locale.ROOT)

    /**
     * Reads a polymorphic `Metadata` object (`get_metadata`, `list_folder` entries), which Dropbox tags with
     * `.tag`: null when it isn't a well-formed file or folder (no or another tag such as `deleted`, missing fields,
     * a name that isn't a single safe path segment, or a `path_lower` whose last segment isn't that name).
     */
    fun entry(o: JsonObject): DbxEntry? = when (o.string(".tag")) {
        "file" -> fields(o, isFolder = false)
        "folder" -> fields(o, isFolder = true)
        else -> null
    }

    /**
     * Reads a concrete `FileMetadata` object: what `upload`, `upload_session/finish` and the download's
     * `Dropbox-API-Result` header return. Dropbox sends it **without** `.tag` (the type is fixed by the endpoint),
     * so none is required; a tag that is present must still say `file`.
     */
    fun fileEntry(o: JsonObject): DbxEntry? {
        if (o.containsKey(".tag") && o.string(".tag") != "file") return null
        return fields(o, isFolder = false)
    }

    private fun fields(o: JsonObject, isFolder: Boolean): DbxEntry? {
        val name = o.string("name")?.takeIf(DropboxPaths::isSafeSegment) ?: return null
        val pathLower = o.string("path_lower")?.takeIf { it.startsWith("/") && it.length <= 4096 } ?: return null
        if (fold(pathLower.substringAfterLast('/')) != fold(name)) return null
        if (isFolder) return DbxEntry(true, name, pathLower, 0, 0, null, null)
        val size = o.long("size")?.takeIf { it in 0..MAX_FILE_SIZE } ?: return null
        val mtime = o.string("server_modified")?.let {
            try {
                Instant.parse(it).toEpochMilli()
            } catch (_: DateTimeParseException) {
                null
            }
        } ?: 0L
        val rev = o.string("rev")?.takeIf { REV.matches(it) }
        val hash = o.string("content_hash")?.takeIf { HEX64.matches(it) }
        return DbxEntry(false, name, pathLower, size, mtime, rev, hash)
    }

    /** Larger than any file Dropbox stores; bigger sizes are nonsense. */
    private const val MAX_FILE_SIZE = 1L shl 50
}

/** Paths inside the App folder (M17c). */
internal object DropboxPaths {
    /** One file or folder name: never empty, `.`, `..`, or containing a separator or control character. */
    fun isSafeSegment(s: String): Boolean = s.isNotEmpty() && s != "." && s != ".." && s.length <= 255 &&
        s.none { it == '/' || it == '\\' || it < ' ' || it == '\u007f' }

    /** Dropbox's path for [segments] under the App folder: "" for the App folder itself, else "/a/b". */
    fun path(segments: List<String>): String = if (segments.isEmpty()) "" else "/" + segments.joinToString("/")
}
