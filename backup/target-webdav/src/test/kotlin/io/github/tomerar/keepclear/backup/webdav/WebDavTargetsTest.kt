// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.backup.webdav

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.UUID

/** M17: opening a WebDAV folder: its marker gives it an identity; damaged markers are never overwritten. */
class WebDavTargetsTest {
    private val server = FakeDavServer()

    @AfterEach
    fun stop() = server.close()

    private fun fresh() = server.location("KeepClear-${UUID.randomUUID()}/backup")

    @Test
    fun `the first open creates the folder and a marker, the next one finds the same identity`(): Unit = runBlocking {
        val place = fresh()
        val first = WebDavTargets.open(place, "Cloud", timeoutSeconds = 5).shouldBeInstanceOf<WebDavCheck.Ok>()
        val again = WebDavTargets.open(place, "Cloud", timeoutSeconds = 5).shouldBeInstanceOf<WebDavCheck.Ok>()
        first.target.id shouldBe again.target.id
        first.target.id shouldStartWith WebDavTargets.ref(place) + "|"
        val other = WebDavTargets.open(fresh(), "Cloud", timeoutSeconds = 5).shouldBeInstanceOf<WebDavCheck.Ok>()
        other.target.id shouldNotBe first.target.id
    }

    @Test
    fun `a damaged marker is reported and left alone (SEC-3)`(): Unit = runBlocking {
        val folder = "KeepClear-${UUID.randomUUID()}"
        val place = server.location(folder)
        WebDavBackupTarget(place, "raw", "raw", timeoutSeconds = 5).mkdirs("")
        server.putFile("$folder/${WebDavTargets.MARKER}", "not a marker".toByteArray())
        WebDavTargets.open(place, "Cloud", timeoutSeconds = 5) shouldBe WebDavCheck.DamagedMarker
        server.bytes("$folder/${WebDavTargets.MARKER}")!!.toString(Charsets.UTF_8) shouldBe "not a marker"
    }

    @Test
    fun `a wrong password is Refused, a dead address Unreachable`(): Unit = runBlocking {
        WebDavTargets.open(server.location("x", password = "wrong"), "Cloud", timeoutSeconds = 5) shouldBe
            WebDavCheck.Refused
        val dead = WebDavLocation("http://127.0.0.1:1/dav", "u", "p")
        WebDavTargets.open(dead, "Cloud", timeoutSeconds = 2) shouldBe WebDavCheck.Unreachable
    }

    @Test
    fun `marker parsing is strict`() {
        WebDavTargets.parseMarker("0123456789abcdef0123456789abcdef\n".toByteArray()) shouldBe
            "0123456789abcdef0123456789abcdef"
        WebDavTargets.parseMarker("0123456789ABCDEF0123456789ABCDEF".toByteArray()) shouldBe null
        WebDavTargets.parseMarker(ByteArray(65) { '0'.code.toByte() }) shouldBe null
        WebDavTargets.parseMarker("x".toByteArray()) shouldBe null
    }
}
