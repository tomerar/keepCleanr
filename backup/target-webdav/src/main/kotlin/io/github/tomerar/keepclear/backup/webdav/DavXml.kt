// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.backup.webdav

import org.w3c.dom.Element
import org.xml.sax.ErrorHandler
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
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
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException

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
 * - An href counts only if it points to this server and lies under the base folder, after strict decoding
 *   ([DavPath.split]: no `..`, no encoded `/`). Everything else is ignored.
 */
internal object DavXml {
    private const val DAV = "DAV:"
    private val FORBIDDEN = Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE)

    fun parseMultistatus(bytes: ByteArray, server: HrefScope): List<DavEntry> {
        val text = utf8(bytes)?.removePrefix("\uFEFF") ?: throw DavXmlException("not UTF-8")
        if (FORBIDDEN.containsMatchIn(text)) throw DavXmlException("DTDs and entities are not accepted")
        val doc = try {
            builder().parse(InputSource(StringReader(text)))
        } catch (e: SAXException) {
            throw DavXmlException("bad XML", e)
        } catch (e: java.io.IOException) {
            throw DavXmlException("bad XML", e)
        }
        val root = doc.documentElement
        if (root.namespaceURI != DAV || root.localName != "multistatus") throw DavXmlException("not a multistatus")
        return children(root, "response").mapNotNull { response -> entry(response, server) }
    }

    private fun entry(response: Element, server: HrefScope): DavEntry? {
        val href = children(response, "href").firstOrNull()?.textContent?.trim() ?: return null
        val rel = server.relative(href) ?: return null
        var collection = false
        var size = 0L
        var mtime = 0L
        var etag: String? = null
        var quota: Long? = null
        for (propstat in children(response, "propstat")) {
            val status = children(propstat, "status").firstOrNull()?.textContent.orEmpty()
            if (!Regex("""^HTTP/\d\.\d 200\b""").containsMatchIn(status.trim())) continue
            for (prop in children(propstat, "prop")) {
                children(prop, "resourcetype").firstOrNull()?.let { rt ->
                    collection = children(rt, "collection").isNotEmpty()
                }
                children(prop, "getcontentlength").firstOrNull()?.textContent?.trim()?.let { v ->
                    v.takeIf { it.length in 1..18 && it.all(Char::isDigit) }?.toLong()?.let { size = it }
                }
                children(prop, "getlastmodified").firstOrNull()?.textContent?.trim()?.let { v ->
                    mtime = httpDate(v) ?: 0L
                }
                children(prop, "getetag").firstOrNull()?.textContent?.trim()?.let { v ->
                    etag = v.takeIf { it.length in 1..256 && it.none { c -> c.isISOControl() } }
                }
                children(prop, "quota-available-bytes").firstOrNull()?.textContent?.trim()?.let { v ->
                    // Nextcloud: -1 unknown, -2 unlimited, -3 not computed; all mean "don't know".
                    quota = v.takeIf { it.length in 1..18 && it.all(Char::isDigit) }?.toLong()
                }
            }
        }
        return DavEntry(rel, collection, if (collection) 0L else size, mtime, etag, quota)
    }

    private fun children(parent: Element, localName: String): List<Element> {
        val out = mutableListOf<Element>()
        var n = parent.firstChild
        while (n != null) {
            if (n is Element && n.namespaceURI == DAV && n.localName == localName) out += n
            n = n.nextSibling
        }
        return out
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

    private fun builder() = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isValidating = false
        isExpandEntityReferences = false
        isCoalescing = true
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
            } catch (_: UnsupportedOperationException) {
            }
        }
    }.newDocumentBuilder().apply {
        setEntityResolver { _, _ -> throw SAXException("external entities are not accepted") }
        setErrorHandler(
            object : ErrorHandler {
                override fun warning(exception: SAXParseException) = Unit

                override fun error(exception: SAXParseException) = throw exception

                override fun fatalError(exception: SAXParseException) = throw exception
            },
        )
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
