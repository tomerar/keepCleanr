// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.backup.webdav

import org.xml.sax.Attributes
import org.xml.sax.ErrorHandler
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader
import java.net.URI
import java.net.URISyntaxException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import javax.xml.XMLConstants
import javax.xml.parsers.ParserConfigurationException
import javax.xml.parsers.SAXParserFactory

/** One `<d:response>` of a PROPFIND answer, with its href already checked and made relative to the base folder. */
internal data class DavEntry(
    /** Decoded segments below the base folder; empty for the base folder itself. */
    val rel: List<String>,
    val isCollection: Boolean,
    val size: Long,
    val mtimeMillis: Long,
    val etag: String?,
    val quotaAvailableBytes: Long?,
)

/** The server's XML broke the rules (DTD, entities, not a multistatus, bad UTF-8). Never parsed further. */
internal class DavXmlException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Parses PROPFIND answers. SEC (threat model): the server is untrusted.
 *
 * - The body must be UTF-8 and is parsed from characters, so an `encoding=` declaration can't smuggle in another
 *   charset; any `<!DOCTYPE` or `<!ENTITY` is refused before parsing (no XXE, no entity expansion), and the parser
 *   is configured to refuse DTDs and external entities too (on each platform that supports the switches).
 * - D6: parsed as a **stream** (SAX), never as a document tree, so memory stays proportional to the entries
 *   kept; at most [MAX_ENTRIES] entries, [MAX_DEPTH] levels and [MAX_TEXT] characters per value (the body itself
 *   is capped by the caller, `WebDavBackupTarget.MAX_PROPFIND_BYTES`).
 * - An href counts only if it points to this server and lies under the base folder, after strict decoding
 *   ([DavPath.split]: no `..`, no encoded `/`). Everything else is ignored.
 */
internal object DavXml {
    private const val DAV = "DAV:"
    private val FORBIDDEN = Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE)
    private val STATUS_OK = Regex("""^HTTP/\d\.\d 200\b""")
    private val PROP_PATH = listOf("multistatus", "response", "propstat", "prop")
    private val PROPS = setOf("resourcetype", "getcontentlength", "getlastmodified", "getetag", "quota-available-bytes")

    /** More `<d:response>` entries than any folder KeepClear lists; a larger answer is refused. */
    const val MAX_ENTRIES = 50_000

    /** Deeper nesting than a multistatus ever needs. */
    const val MAX_DEPTH = 32

    /** Longest href, status or property value kept (hrefs are refused past 8 KiB anyway). */
    const val MAX_TEXT = 16 * 1024

    fun parseMultistatus(bytes: ByteArray, server: HrefScope): List<DavEntry> {
        val text = utf8(bytes)?.removePrefix("\uFEFF") ?: throw DavXmlException("not UTF-8")
        if (FORBIDDEN.containsMatchIn(text)) throw DavXmlException("DTDs and entities are not accepted")
        val handler = Handler(server)
        try {
            reader(handler).parse(InputSource(StringReader(text)))
        } catch (e: Refused) {
            throw DavXmlException(e.message ?: "refused", e)
        } catch (e: SAXException) {
            throw DavXmlException("bad XML", e)
        } catch (e: java.io.IOException) {
            throw DavXmlException("bad XML", e)
        }
        if (!handler.sawRoot) throw DavXmlException("not a multistatus")
        return handler.entries
    }

    /** Thrown inside the parser to stop it; becomes a [DavXmlException]. */
    private class Refused(message: String) : SAXException(message)

    /** Collects entries element by element; keeps only what [DavEntry] needs. */
    private class Handler(private val server: HrefScope) : DefaultHandler() {
        val entries = mutableListOf<DavEntry>()
        var sawRoot = false

        /** DAV: local names from the root down; null for elements in other namespaces. */
        private val stack = ArrayList<String?>()
        private val text = StringBuilder()
        private var capturing = -1

        // The current <d:response>.
        private var href: String? = null
        private var collection = false
        private var size = 0L
        private var mtime = 0L
        private var etag: String? = null
        private var quota: Long? = null

        // The current <d:propstat>: its first status, and the values of its props in document order.
        private var status: String? = null
        private val pending = mutableListOf<Pair<String, String>>()

        // The current <d:prop>: only the first of each property counts.
        private val seenInProp = mutableSetOf<String>()
        private var resourceIsCollection = false

        private fun at(vararg path: String): Boolean =
            stack.size == path.size && path.indices.all { stack[it] == path[it] }

        private fun capture() {
            capturing = stack.size
            text.setLength(0)
        }

        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
            if (stack.isEmpty()) {
                if (uri != DAV || localName != "multistatus") throw Refused("not a multistatus")
                sawRoot = true
            }
            stack += if (uri == DAV) localName else null
            if (stack.size > MAX_DEPTH) throw Refused("nested too deeply")
            when {
                at("multistatus", "response") -> {
                    href = null
                    collection = false
                    size = 0L
                    mtime = 0L
                    etag = null
                    quota = null
                }

                at("multistatus", "response", "href") -> {
                    if (href == null) capture()
                }

                at("multistatus", "response", "propstat") -> {
                    status = null
                    pending.clear()
                }

                at("multistatus", "response", "propstat", "status") -> {
                    if (status == null) capture()
                }

                at("multistatus", "response", "propstat", "prop") -> {
                    seenInProp.clear()
                }

                stack.size == 5 && stack.subList(0, 4) == PROP_PATH -> {
                    val name = stack[4]
                    if (name != null && name in PROPS && seenInProp.add(name)) {
                        if (name == "resourcetype") resourceIsCollection = false else capture()
                    }
                }

                at("multistatus", "response", "propstat", "prop", "resourcetype", "collection") -> {
                    resourceIsCollection = true
                }
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (capturing < 0) return
            if (text.length + length > MAX_TEXT) throw Refused("value too long")
            text.appendRange(ch, start, start + length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            val depth = stack.size
            val name = stack.removeAt(stack.size - 1)
            if (capturing == depth) {
                capturing = -1
                val value = text.toString()
                when (depth) {
                    3 -> href = value.trim()
                    4 -> status = value
                    5 -> pending += name!! to value.trim()
                }
                return
            }
            when {
                depth == 5 && name == "resourcetype" && stack == PROP_PATH -> {
                    pending += "resourcetype" to resourceIsCollection.toString()
                }

                depth == 3 && name == "propstat" && stack == listOf("multistatus", "response") -> {
                    if (STATUS_OK.containsMatchIn(status.orEmpty().trim())) pending.forEach { (k, v) -> apply(k, v) }
                }

                depth == 2 && name == "response" && stack == listOf("multistatus") -> {
                    val rel = href?.let { server.relative(it) } ?: return
                    if (entries.size >= MAX_ENTRIES) throw Refused("too many entries")
                    entries += DavEntry(rel, collection, if (collection) 0L else size, mtime, etag, quota)
                }
            }
        }

        private fun apply(name: String, v: String) {
            when (name) {
                "resourcetype" -> {
                    collection = v == "true"
                }

                "getcontentlength" -> {
                    v.takeIf { it.length in 1..18 && it.all(Char::isDigit) }?.toLong()?.let { size = it }
                }

                "getlastmodified" -> {
                    mtime = httpDate(v) ?: 0L
                }

                "getetag" -> {
                    etag = v.takeIf { it.length in 1..256 && it.none { c -> c.isISOControl() } }
                }

                "quota-available-bytes" -> {
                    // Nextcloud: -1 unknown, -2 unlimited, -3 not computed; all mean "don't know".
                    quota = v.takeIf { it.length in 1..18 && it.all(Char::isDigit) }?.toLong()
                }
            }
        }
    }

    private fun httpDate(v: String): Long? = try {
        ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    }

    private fun utf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    private fun reader(handler: Handler) = SAXParserFactory.newInstance().apply {
        isNamespaceAware = true
        isValidating = false
        // Not every platform's parser knows every switch (Android's doesn't); the DOCTYPE check above is the guard.
        listOf(
            "http://apache.org/xml/features/disallow-doctype-decl" to true,
            "http://xml.org/sax/features/external-general-entities" to false,
            "http://xml.org/sax/features/external-parameter-entities" to false,
            "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false,
            XMLConstants.FEATURE_SECURE_PROCESSING to true,
        ).forEach { (feature, on) ->
            try {
                setFeature(feature, on)
            } catch (_: ParserConfigurationException) {
            } catch (_: SAXException) {
            } catch (_: UnsupportedOperationException) {
            }
        }
    }.newSAXParser().xmlReader.apply {
        contentHandler = handler
        setEntityResolver { _, _ -> throw SAXException("external entities are not accepted") }
        errorHandler = object : ErrorHandler {
            override fun warning(exception: SAXParseException) = Unit

            override fun error(exception: SAXParseException) = throw exception

            override fun fatalError(exception: SAXParseException) = throw exception
        }
    }
}

/** Which hrefs belong to this target: same server, under [base]. */
internal class HrefScope(
    private val https: Boolean,
    private val host: String,
    private val port: Int,
    private val base: List<String>,
) {
    /** Segments of [href] below the base folder, or null when it points elsewhere or is malformed. */
    fun relative(href: String): List<String>? {
        if (href.length > 8 * 1024 || href.any { it.isISOControl() }) return null
        val rawPath = if (href.startsWith("/")) {
            if (href.startsWith("//")) return null // scheme-relative: another host
            if ('?' in href || '#' in href) return null
            href
        } else {
            val uri = try {
                URI(href)
            } catch (_: URISyntaxException) {
                return null
            }
            val scheme = uri.scheme?.lowercase() ?: return null
            if (scheme != (if (https) "https" else "http")) return null
            val h = uri.host?.removePrefix("[")?.removeSuffix("]")?.lowercase() ?: return null
            val p = if (uri.port == -1) (if (https) 443 else 80) else uri.port
            if (h != host || p != port || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) {
                return null
            }
            uri.rawPath ?: return null
        }
        val segments = DavPath.split(rawPath) ?: return null
        if (segments.size < base.size || segments.subList(0, base.size) != base) return null
        return segments.drop(base.size)
    }
}
