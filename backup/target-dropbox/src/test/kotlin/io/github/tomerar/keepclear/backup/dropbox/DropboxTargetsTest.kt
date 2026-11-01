// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.backup.dropbox

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** M17c: opening a Dropbox folder: the marker file identifies it; a damaged marker is never overwritten (SEC-3). */
class DropboxTargetsTest {
    private val server = FakeDropboxServer()

    @AfterEach
    fun stop() = server.close()

    private suspend fun open(folder: String = "KeepClear") =
        DropboxTargets.open(server.auth(), folder, "Dropbox", server.endpoints, timeoutSeconds = 5)

    @Test
    fun `the first open writes a marker, the next one reads the same id`(): Unit = runBlocking {
        val first = open().shouldBeInstanceOf<DropboxCheck.Ok>().target
        val marker = server.bytes("KeepClear/.keepclear-target")
        marker shouldNotBe null
        DropboxTargets.parseMarker(marker!!) shouldNotBe null
        val second = open().shouldBeInstanceOf<DropboxCheck.Ok>().target
        second.id shouldBe first.id
        first.id shouldBe DropboxTargets.targetId("KeepClear", DropboxTargets.parseMarker(marker)!!)
        DropboxTargets.ref("/KeepClear/") shouldBe "dropbox:/KeepClear"
        // In the App folder itself too.
        open("").shouldBeInstanceOf<DropboxCheck.Ok>().target.id shouldNotBe first.id
    }

    @Test
    fun `a damaged or foreign marker is reported and left alone`(): Unit = runBlocking {
        server.putFile("KeepClear/.keepclear-target", "not ours".toByteArray())
        open() shouldBe DropboxCheck.DamagedMarker
        server.bytes("KeepClear/.keepclear-target") shouldBe "not ours".toByteArray()
        server.putFile("Other/.keepclear-target/inside", byteArrayOf(1))
        open("Other") shouldBe DropboxCheck.DamagedMarker
        server.putFile("Big/.keepclear-target", ByteArray(100) { 'a'.code.toByte() })
        open("Big") shouldBe DropboxCheck.DamagedMarker
    }

    @Test
    fun `a refused sign-in and an unreachable Dropbox are told apart`(): Unit = runBlocking {
        server.refreshRefused = true
        open() shouldBe DropboxCheck.Refused
        server.refreshRefused = false
        server.rateLimitNext = 100
        open() shouldBe DropboxCheck.Unreachable
        server.close()
        open() shouldBe DropboxCheck.Unreachable
    }
}
