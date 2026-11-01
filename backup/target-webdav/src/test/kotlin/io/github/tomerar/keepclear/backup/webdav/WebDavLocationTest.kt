// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.backup.webdav

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/** M17: what the user may type as a WebDAV address, and how server paths are read (pure checks, no server). */
class WebDavLocationTest {
    @Test
    fun `https only, plain http only for this phone itself (ADR 0011)`() {
        WebDavLocation("https://cloud.example.com/remote.php/dav/files/dana/KeepClear", "dana", "pw").https shouldBe
            true
        WebDavLocation("http://localhost:8080/dav", "u", "p").port shouldBe 8080
        WebDavLocation("http://127.0.0.1/dav", "u", "p").port shouldBe 80
        WebDavLocation("http://[::1]:81/dav", "u", "p").host shouldBe "::1"
        listOf(
            "http://cloud.example.com/dav",
            "http://192.168.1.20/dav",
            "ftp://cloud.example.com/",
            "https://user:pw@cloud.example.com/dav",
            "https://cloud.example.com/dav?x=1",
            "https://cloud.example.com/dav#f",
            "https://cloud.example.com/a/../b",
            "https://cloud.example.com/a/%2e%2e/b",
            "https://cloud.example.com/a%2Fb",
            "cloud.example.com/dav",
            "https:///nohost",
        ).forEach { url -> shouldThrow<IllegalArgumentException> { WebDavLocation(url, "u", "p") } }
        shouldThrow<IllegalArgumentException> { WebDavLocation("https://h/dav", "a:b", "p") }
        shouldThrow<IllegalArgumentException> { WebDavLocation("https://h/dav", "", "p") }
    }

    @Test
    fun `the password never appears in logs or in the stored reference`() {
        val l = WebDavLocation("https://cloud.example.com/dav/KeepClear", "dana", "s3cret-app-pw")
        l.toString() shouldNotContain "s3cret"
        WebDavTargets.ref(l) shouldBe "davs://cloud.example.com/dav/KeepClear"
        WebDavTargets.ref(WebDavLocation("http://127.0.0.1:8080/dav", "u", "p")) shouldBe "dav://127.0.0.1:8080/dav"
    }

    @Test
    fun `the Nextcloud helper builds the account's WebDAV folder`() {
        val l = WebDavLocation.nextcloud("https://cloud.example.com/", "dana b", "pw", "Backups/Phone")
        l.canonicalUrl shouldBe "https://cloud.example.com/remote.php/dav/files/dana%20b/Backups/Phone"
        l.nextcloud shouldBe true
        l.baseSegments shouldBe listOf("remote.php", "dav", "files", "dana b", "Backups", "Phone")
    }

    @Test
    fun `server paths are decoded strictly`() {
        DavPath.split("/a/b%20c/") shouldBe listOf("a", "b c")
        DavPath.split("/%D7%AA%D7%9E%D7%95%D7%A0%D7%94") shouldBe listOf("תמונה")
        listOf("a/b", "/a//b", "/a/%2e%2e", "/a/..", "/a/%2F", "/a/%zz", "/a/%C3", "/a/%00", "/a/b%5Cc").forEach {
            DavPath.split(it) shouldBe null
        }
        DavPath.encode(listOf("a b", "50%", "#?")) shouldBe "/a%20b/50%25/%23%3F"
    }

    @Test
    fun `only hrefs on this server and under the folder count`() {
        val scope = HrefScope(https = true, host = "cloud.example.com", port = 443, base = listOf("dav", "me"))
        scope.relative("/dav/me/") shouldBe emptyList()
        scope.relative("/dav/me/a/b.txt") shouldBe listOf("a", "b.txt")
        scope.relative("https://cloud.example.com/dav/me/x") shouldBe listOf("x")
        scope.relative("https://CLOUD.example.com:443/dav/me/x") shouldBe listOf("x")
        listOf(
            "https://evil.example/dav/me/x",
            "http://cloud.example.com/dav/me/x",
            "https://cloud.example.com:8443/dav/me/x",
            "//evil.example/dav/me/x",
            "/dav/other/x",
            "/dav/me/../other/x",
            "/dav/me/%2e%2e/x",
            "x",
            "/dav/me/x?y",
            "https://u:p@cloud.example.com/dav/me/x",
        ).forEach { scope.relative(it) shouldBe null }
    }

    @Test
    fun `XML with a DTD, an entity or another charset is refused`() {
        val scope = HrefScope(https = true, host = "h", port = 443, base = emptyList())
        val ok = """<d:multistatus xmlns:d="DAV:"><d:response><d:href>/a</d:href><d:propstat><d:prop>
            <d:getcontentlength>5</d:getcontentlength><d:resourcetype/></d:prop>
            <d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>"""
        DavXml.parseMultistatus(ok.toByteArray(), scope).single().size shouldBe 5L
        listOf(
            """<!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]>$ok""",
            """<?xml version="1.0"?><!doctype x SYSTEM "http://evil.example/x.dtd">$ok""",
            """<!ENTITY a "b">$ok""",
            "<html>not dav</html>",
            "<d:multistatus xmlns:d=\"DAV:\">",
        ).forEach { bad ->
            shouldThrow<DavXmlException> { DavXml.parseMultistatus(bad.toByteArray(), scope) }
        }
        shouldThrow<DavXmlException> { DavXml.parseMultistatus(ok.toByteArray(Charsets.UTF_16), scope) }
        // A property whose propstat failed (404) is not taken.
        val failed = ok.replace("200 OK", "404 Not Found")
        DavXml.parseMultistatus(failed.toByteArray(), scope).single().size shouldBe 0L
    }
}
