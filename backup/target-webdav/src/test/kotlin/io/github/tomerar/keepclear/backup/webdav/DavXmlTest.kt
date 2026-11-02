// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.backup.webdav

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** D6: the PROPFIND parser is a bounded stream parser and reads the same answers the tree parser did. */
class DavXmlTest {
    private val scope = HrefScope(https = true, host = "cloud.example.com", port = 443, base = listOf("dav"))

    private fun parse(xml: String) = DavXml.parseMultistatus(xml.toByteArray(), scope)

    private fun response(href: String, props: String, status: String = "HTTP/1.1 200 OK") =
        "<d:response><d:href>$href</d:href><d:propstat><d:prop>$props</d:prop>" +
            "<d:status>$status</d:status></d:propstat></d:response>"

    private fun multistatus(vararg responses: String) =
        """<?xml version="1.0" encoding="utf-8"?><d:multistatus xmlns:d="DAV:" xmlns:x="urn:other">""" +
            responses.joinToString("") + "</d:multistatus>"

    @Test
    fun `entries, folders, sizes, dates, etags and quota are read as before`() {
        val entries = parse(
            multistatus(
                response(
                    "/dav/",
                    "<d:resourcetype><d:collection/></d:resourcetype><d:quota-available-bytes>99" +
                        "</d:quota-available-bytes>",
                ),
                response(
                    "/dav/a%20b.jpg",
                    "<d:resourcetype/><d:getcontentlength> 1234 </d:getcontentlength>" +
                        "<d:getlastmodified>Mon, 12 Jul 2021 10:00:00 GMT</d:getlastmodified>" +
                        "<d:getetag>\"e1\"</d:getetag>",
                ),
                // Nextcloud: the unknown props come in a second propstat with 404, which is ignored.
                "<d:response><d:href>https://cloud.example.com/dav/sub/</d:href>" +
                    "<d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>" +
                    "<d:status>HTTP/1.1 200 OK</d:status></d:propstat>" +
                    "<d:propstat><d:prop><d:getcontentlength>77</d:getcontentlength></d:prop>" +
                    "<d:status>HTTP/1.1 404 Not Found</d:status></d:propstat></d:response>",
            ),
        )
        entries.map { it.rel } shouldBe listOf(emptyList(), listOf("a b.jpg"), listOf("sub"))
        entries[0].isCollection shouldBe true
        entries[0].quotaAvailableBytes shouldBe 99L
        entries[1].size shouldBe 1234L
        entries[1].mtimeMillis shouldBe 1626084000000
        entries[1].etag shouldBe "\"e1\""
        entries[2].isCollection shouldBe true
        entries[2].size shouldBe 0L
    }

    @Test
    fun `props in a non-200 propstat, in another namespace or in the wrong place are ignored`() {
        val e = parse(
            multistatus(
                response("/dav/f", "<d:getcontentlength>5</d:getcontentlength>", status = "HTTP/1.1 403 Forbidden"),
                response(
                    "/dav/g",
                    "<x:getcontentlength>6</x:getcontentlength><d:getcontentlength>7</d:getcontentlength>",
                ),
                "<d:response><d:href>/dav/h</d:href><d:getcontentlength>8</d:getcontentlength></d:response>",
                // Only the first href of a response counts.
                "<d:response><d:href>/dav/i</d:href><d:href>/elsewhere/j</d:href></d:response>",
            ),
        )
        e.map { it.rel to it.size } shouldBe
            listOf(listOf("f") to 0L, listOf("g") to 7L, listOf("h") to 0L, listOf("i") to 0L)
    }

    @Test
    fun `not a multistatus, DTDs, too many entries, too deep or too long values are refused`() {
        shouldThrow<DavXmlException> { parse("""<x:multistatus xmlns:x="urn:other"/>""") }
        shouldThrow<DavXmlException> { parse("""<!DOCTYPE d [<!ENTITY e "x">]><d:multistatus xmlns:d="DAV:"/>""") }
        shouldThrow<DavXmlException> { parse("not xml") }
        shouldThrow<DavXmlException> {
            parse(multistatus(*Array(DavXml.MAX_ENTRIES + 1) { response("/dav/f$it", "<d:resourcetype/>") }))
        }
        parse(multistatus(*Array(DavXml.MAX_ENTRIES) { response("/dav/f$it", "") })).size shouldBe DavXml.MAX_ENTRIES
        val deep = "<d:x>".repeat(DavXml.MAX_DEPTH) + "</d:x>".repeat(DavXml.MAX_DEPTH)
        shouldThrow<DavXmlException> { parse(multistatus(deep)) }
        shouldThrow<DavXmlException> { parse(multistatus(response("/dav/" + "a".repeat(DavXml.MAX_TEXT), ""))) }
    }
}
