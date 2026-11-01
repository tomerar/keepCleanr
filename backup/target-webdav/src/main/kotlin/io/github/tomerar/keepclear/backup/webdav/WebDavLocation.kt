// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.backup.webdav

import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URISyntaxException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Where a WebDAV/Nextcloud backup goes: what the user typed on the phone (M17). The password (a Nextcloud app
 * password) is never logged.
 *
 * - [url] is the folder backups go into, e.g. `https://cloud.example.com/remote.php/dav/files/dana/KeepClear`.
 *   HTTPS only; plain HTTP is accepted only for this phone itself (`localhost`, tests), never over a network.
 * - [nextcloud]: the server is Nextcloud and computes a SHA-256 of each upload itself (`X-Hash: sha256` →
 *   `X-Hash-SHA256`, ADR 0011). Off for any other WebDAV server, whose copies are verified by reading them back.
 */
class WebDavLocation(
    url: String,
    val user: String,
    val password: String,
    val nextcloud: Boolean = false,
) {
    val https: Boolean
    val host: String
    val port: Int

    /** Decoded segments of the folder's path on the server. */
    val baseSegments: List<String>

    init {
        require(user.isNotEmpty() && ':' !in user && user.none { it.isISOControl() }) { "user name not allowed" }
        require(password.none { it == '\r' || it == '\n' }) { "password not allowed" }
        val uri = try {
            URI(url.trim())
        } catch (e: URISyntaxException) {
            throw IllegalArgumentException("not an address", e)
        }
        val scheme = uri.scheme?.lowercase()
        require(scheme == "https" || scheme == "http") { "https address expected" }
        require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
            "address must not contain a user, query or fragment"
        }
        val h = uri.host?.removePrefix("[")?.removeSuffix("]")?.lowercase()
        require(!h.isNullOrEmpty()) { "address needs a host" }
        // ADR 0011: the password never travels unencrypted over a network.
        require(scheme == "https" || h in LOOPBACK) { "plain http only for this phone itself; use https" }
        https = scheme == "https"
        host = h
        port = if (uri.port == -1) (if (https) 443 else 80) else uri.port
        require(port in 1..65535)
        baseSegments = DavPath.split(uri.rawPath.orEmpty()) ?: throw IllegalArgumentException("folder not allowed")
    }

    /** The folder as a canonical URL (no credentials): the same place always gives the same string. */
    val canonicalUrl: String
        get() {
            val hostPart = if (':' in host) "[$host]" else host
            val portPart = if (port == (if (https) 443 else 80)) "" else ":$port"
            return "${if (https) "https" else "http"}://$hostPart$portPart${DavPath.encode(baseSegments)}"
        }

    override fun toString(): String = "WebDavLocation($canonicalUrl, user=$user, nextcloud=$nextcloud)"

    override fun equals(other: Any?): Boolean = other is WebDavLocation &&
        other.canonicalUrl == canonicalUrl &&
        other.user == user &&
        other.password == password &&
        other.nextcloud == nextcloud

    override fun hashCode(): Int = canonicalUrl.hashCode() * 31 + user.hashCode()

    companion object {
        private val LOOPBACK = setOf("localhost", "127.0.0.1", "::1")

        /**
         * A Nextcloud account's folder: `<server>/remote.php/dav/files/<user>/<folder>` (Nextcloud's WebDAV
         * endpoint). [server] is what the user typed, e.g. `https://cloud.example.com` or with a sub path.
         */
        fun nextcloud(server: String, user: String, appPassword: String, folder: String): WebDavLocation {
            val folderSegments = folder.split('/').filter { it.isNotEmpty() }
            val path = DavPath.encode(listOf("remote.php", "dav", "files", user) + folderSegments)
            return WebDavLocation(server.trim().trimEnd('/') + path, user, appPassword, nextcloud = true)
        }
    }
}

/** Percent-encoding and strict decoding of WebDAV paths (SEC: hrefs from the server are untrusted). */
internal object DavPath {
    private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

    /** True for a segment KeepClear may use: not empty, not `.`/`..`, no separators or control characters. */
    fun isSafeSegment(s: String): Boolean =
        s.isNotEmpty() && s != "." && s != ".." &&
            s.none { it == '/' || it == '\\' || it.code < 0x20 || it == '\u007f' }

    /** `/a/b` for segments [a, b]; `/` for none. Every byte outside the unreserved set is percent-encoded. */
    fun encode(segments: List<String>, trailingSlash: Boolean = false): String {
        val sb = StringBuilder()
        for (s in segments) {
            sb.append('/')
            for (b in s.toByteArray(Charsets.UTF_8)) {
                val c = (b.toInt() and 0xff).toChar()
                if (c in UNRESERVED) sb.append(c) else sb.append('%').append("%02X".format(b.toInt() and 0xff))
            }
        }
        if (sb.isEmpty() || trailingSlash) sb.append('/')
        return sb.toString()
    }

    /**
     * Splits a raw (encoded) absolute path into decoded segments. Null when anything is off: not absolute,
     * an empty segment in the middle (`//`), bad escapes, invalid UTF-8, or a segment that isn't safe
     * ([isSafeSegment], so no `..` and no encoded `/`). A trailing slash is allowed.
     */
    fun split(rawPath: String): List<String>? {
        if (rawPath.isEmpty() || rawPath == "/") return emptyList()
        if (!rawPath.startsWith("/")) return null
        val parts = rawPath.substring(1).removeSuffix("/").split('/')
        return parts.map { part -> decode(part)?.takeIf(::isSafeSegment) ?: return null }
    }

    /** Strict percent-decoding into UTF-8; null on a malformed escape or invalid UTF-8. */
    fun decode(s: String): String? {
        val out = ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            if (s[i] == '%') {
                if (i + 2 >= s.length) return null
                val hi = Character.digit(s[i + 1], 16)
                val lo = Character.digit(s[i + 2], 16)
                if (hi < 0 || lo < 0) return null
                out.write(hi * 16 + lo)
                i += 3
            } else {
                var end = i
                while (end < s.length && s[end] != '%') end++
                val run = s.substring(i, end)
                // Lone surrogates can't be UTF-8: refuse rather than turn them into '?'.
                if (!Charsets.UTF_8.newEncoder().canEncode(run)) return null
                out.write(run.toByteArray(Charsets.UTF_8))
                i = end
            }
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(out.toByteArray()))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }
}
