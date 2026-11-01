// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.backup.webdav

import io.github.tomerar.keepclear.backup.DigestKind
import io.github.tomerar.keepclear.core.storage.StorageException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** M17: WebDAV/Nextcloud target behaviour against the fake server, including servers that fail or lie. */
class WebDavBackupTargetTest {
    private val server = FakeDavServer()

    @AfterEach
    fun stop() = server.close()

    private fun target(nextcloud: Boolean = false, password: String = server.password, folder: String = "KeepClear") =
        WebDavBackupTarget(server.location(folder, nextcloud, password), "t", "T", timeoutSeconds = 5)

    private suspend fun WebDavBackupTarget.put(rel: String, bytes: ByteArray) =
        create(rel, bytes.size.toLong()).use { it.write(bytes) }

    @Test
    fun `Nextcloud's server-computed SHA-256 is offered and follows the rename`(): Unit = runBlocking {
        server.nextcloud = true
        val t = target(nextcloud = true)
        t.caps.serverDigests shouldBe setOf(DigestKind.SHA256)
        t.mkdirs("d")
        val bytes = ByteArray(200_000) { (it * 7).toByte() }
        t.put("d/.tmp", bytes)
        t.serverDigest("d/.tmp", DigestKind.SHA256) shouldBe FakeDavServer.sha256(bytes)
        t.rename("d/.tmp", "d/final.jpg")
        t.serverDigest("d/.tmp", DigestKind.SHA256) shouldBe null
        t.serverDigest("d/final.jpg", DigestKind.SHA256) shouldBe FakeDavServer.sha256(bytes)
        t.delete("d/final.jpg")
        t.serverDigest("d/final.jpg", DigestKind.SHA256) shouldBe null
        // Nextcloud verification needs no download.
        server.requests.filter { it.startsWith("GET") }.shouldBeEmpty()
    }

    @Test
    fun `a generic WebDAV target never trusts a hash header, and never asks for one`(): Unit = runBlocking {
        server.nextcloud = true // the server would answer X-Hash, but the user didn't set up Nextcloud
        val t = target(nextcloud = false)
        t.mkdirs("")
        t.caps.serverDigests shouldBe emptySet()
        t.put("a", byteArrayOf(1, 2, 3))
        t.serverDigest("a", DigestKind.SHA256) shouldBe null
    }

    @Test
    fun `a malformed hash header is ignored, so the copy is read back instead`(): Unit = runBlocking {
        server.nextcloud = true
        server.hashMode = FakeDavServer.HashMode.GARBAGE
        val t = target(nextcloud = true)
        t.mkdirs("")
        t.put("a", byteArrayOf(1, 2, 3))
        t.serverDigest("a", DigestKind.SHA256) shouldBe null
    }

    @Test
    fun `a wrong password is refused, never treated as a missing server`(): Unit = runBlocking {
        val t = target(password = "wrong")
        shouldThrow<StorageException.PermissionDenied> { t.stat("") }
        shouldThrow<StorageException.PermissionDenied> { t.create("a", 1) }
    }

    @Test
    fun `a full account means no space (507), and no file is left`(): Unit = runBlocking {
        val t = target()
        t.mkdirs("")
        server.quotaBytes = 1_000
        shouldThrow<StorageException.NoSpace> { t.put("big", ByteArray(5_000)) }
        t.stat("big") shouldBe null
        t.freeBytes() shouldBe 1_000L
    }

    @Test
    fun `a connection dropped mid-upload is a disconnect, leaves no file, and the next call reconnects`(): Unit =
        runBlocking {
            val t = target()
            t.mkdirs("")
            server.dropUploadAfter = 10_000
            shouldThrow<StorageException.Disconnected> { t.put("a.bin", ByteArray(2_000_000) { 3 }) }
            t.stat("a.bin") shouldBe null
            t.put("a.bin", ByteArray(1_000) { 4 })
            t.stat("a.bin")!!.size shouldBe 1_000L
        }

    @Test
    fun `an upload must be exactly the announced size`(): Unit = runBlocking {
        val t = target()
        t.mkdirs("")
        shouldThrow<StorageException> { t.create("short", 10).use { it.write(ByteArray(5)) } }
        shouldThrow<StorageException> { t.create("long", 10).use { it.write(ByteArray(11)) } }
        t.stat("short") shouldBe null
        t.stat("long") shouldBe null
        server.files().shouldBeEmpty()
    }

    @Test
    fun `a read cut short by the server is a disconnect, never a short file`(): Unit = runBlocking {
        val t = target()
        t.mkdirs("")
        t.put("a", ByteArray(100_000) { 1 })
        server.shortGet = true
        shouldThrow<StorageException.Disconnected> { t.openRead("a").use { it.readBytes() } }
    }

    @Test
    fun `hrefs outside the folder are ignored (SEC)`(): Unit = runBlocking {
        val t = target()
        t.mkdirs("d")
        t.put("d/mine.txt", byteArrayOf(1))
        server.hostileHrefs = true
        t.list("d").map { it.name } shouldContainExactlyInAnyOrder listOf("mine.txt")
    }

    @Test
    fun `an XML answer with a DTD or entity is refused (XXE)`(): Unit = runBlocking {
        val t = target()
        t.mkdirs("")
        server.xxe = true
        val e = shouldThrow<StorageException.Io> { t.list("") }
        e.message.orEmpty() shouldNotContain "localhost"
        shouldThrow<StorageException.Io> { t.stat("x") }
    }

    @Test
    fun `an oversized listing is refused`(): Unit = runBlocking {
        val t = target()
        t.mkdirs("")
        server.hugePropfindBytes = WebDavBackupTarget.MAX_PROPFIND_BYTES + 1
        shouldThrow<StorageException.Io> { t.list("") }
    }

    @Test
    fun `redirects are never followed, so the password goes nowhere else`(): Unit = runBlocking {
        FakeDavServer().use { other ->
            val t = target()
            server.redirectTo = other.url("KeepClear/x")
            shouldThrow<StorageException> { t.stat("x") }
            other.requests.shouldBeEmpty()
        }
    }

    @Test
    fun `close drops the connection, so the next read runs on a new one (NEW_SESSION)`(): Unit = runBlocking {
        val t = target()
        t.mkdirs("")
        t.put("a", byteArrayOf(1))
        t.openRead("a").use { it.readBytes() }
        val before = server.clientPorts.last()
        t.openRead("a").use { it.readBytes() }
        server.clientPorts.last() shouldBe before // reused while open
        t.close()
        t.openRead("a").use { it.readBytes() }
        server.clientPorts.last() shouldNotBe before
    }

    @Test
    fun `rename never overwrites even when the destination appears (Overwrite F)`(): Unit = runBlocking {
        val t = target()
        t.mkdirs("")
        t.put("a", byteArrayOf(1))
        server.putFile("KeepClear/b", byteArrayOf(9))
        shouldThrow<StorageException.AlreadyExists> { t.rename("a", "b") }
        server.bytes("KeepClear/b") shouldBe byteArrayOf(9)
    }

    @Test
    fun `paths with dot-dot are refused before anything is sent`(): Unit = runBlocking {
        val t = target()
        shouldThrow<IllegalArgumentException> { t.stat("../other") }
        shouldThrow<IllegalArgumentException> { t.create("a/../../b") }
        server.requests.shouldBeEmpty()
    }

    @Test
    fun `an unreachable server means the place is gone for now (pause, never delete)`(): Unit = runBlocking {
        val port = server.port
        server.close()
        val t = WebDavBackupTarget(
            WebDavLocation("http://127.0.0.1:$port/dav", "u", "p"),
            "x",
            "x",
            timeoutSeconds = 2,
        )
        shouldThrow<StorageException.Disconnected> { t.stat("") }
    }
}
