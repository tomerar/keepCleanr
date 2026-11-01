// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.backup.dropbox

import io.github.tomerar.keepclear.backup.DigestKind
import io.github.tomerar.keepclear.core.hash.DropboxContentHash
import io.github.tomerar.keepclear.core.storage.StorageException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** M17c: Dropbox-specific behaviour of the target against the fake server: proofs, conflicts, failures, SEC. */
class DropboxBackupTargetTest {
    private val server = FakeDropboxServer()

    @AfterEach
    fun stop() = server.close()

    private fun target(
        folder: String = "KeepClear",
        singleUploadMax: Long = DropboxBackupTarget.SINGLE_UPLOAD_MAX,
        chunkSize: Int = DropboxBackupTarget.CHUNK_SIZE,
        auth: DropboxAuth = server.auth(),
    ) = DropboxBackupTarget(auth, folder, "dbx", "Dropbox", server.endpoints, 5, singleUploadMax, chunkSize)

    private suspend fun DropboxBackupTarget.put(rel: String, bytes: ByteArray) =
        create(rel, bytes.size.toLong()).use { it.write(bytes) }

    private suspend fun DropboxBackupTarget.read(rel: String) = openRead(rel).use { it.readBytes() }

    private fun count(endpoint: String) = server.requests.count { it.startsWith("$endpoint ") }

    private val data = ByteArray(50_000) { (it * 7).toByte() }

    // ------------------------------------------------------------------------------------- content_hash

    @Test
    fun `the server's content hash is offered, follows a rename, and never for another kind`(): Unit = runBlocking {
        val t = target()
        t.caps.serverDigests shouldBe setOf(DigestKind.DROPBOX_CONTENT_HASH)
        t.put("d/.tmp", data)
        t.serverDigest("d/.tmp", DigestKind.DROPBOX_CONTENT_HASH) shouldBe DropboxContentHash.of(data)
        t.rename("d/.tmp", "d/final.jpg")
        t.serverDigest("d/final.jpg", DigestKind.DROPBOX_CONTENT_HASH) shouldBe DropboxContentHash.of(data)
        t.serverDigest("d/.tmp", DigestKind.DROPBOX_CONTENT_HASH) shouldBe null
        t.serverDigest("d/final.jpg", DigestKind.SHA256) shouldBe null
        t.serverDigest("d", DigestKind.DROPBOX_CONTENT_HASH) shouldBe null
    }

    @Test
    fun `a wrong hash is passed on as the server's word, a missing or malformed one is no hash`(): Unit =
        runBlocking {
            val t = target()
            t.put("f", data)
            server.hashMode = FakeDropboxServer.HashMode.WRONG
            val wrong = t.serverDigest("f", DigestKind.DROPBOX_CONTENT_HASH)
            wrong shouldNotBe null
            wrong shouldNotBe DropboxContentHash.of(data)
            server.hashMode = FakeDropboxServer.HashMode.MISSING
            t.serverDigest("f", DigestKind.DROPBOX_CONTENT_HASH) shouldBe null
            server.hashMode = FakeDropboxServer.HashMode.GARBAGE
            t.serverDigest("f", DigestKind.DROPBOX_CONTENT_HASH) shouldBe null
        }

    // ------------------------------------------------------------------------------------------- tokens

    @Test
    fun `an expired access token is refreshed once and the call goes on`(): Unit = runBlocking {
        val t = target()
        t.put("f", data)
        count("/oauth2/token") shouldBe 1
        server.expireAccessTokens()
        t.stat("f")!!.size shouldBe data.size.toLong()
        count("/oauth2/token") shouldBe 2
        server.expireAccessTokens()
        t.put("g", data) // the never-overwrite check refreshes before the upload starts
        t.read("g") shouldBe data
        count("/oauth2/token") shouldBe 3
    }

    @Test
    fun `a revoked refresh token is access denied, never a crash`(): Unit = runBlocking {
        val t = target()
        t.put("f", data)
        server.expireAccessTokens()
        server.refreshRefused = true
        shouldThrow<StorageException.PermissionDenied> { t.stat("f") }
        shouldThrow<StorageException.PermissionDenied> { t.put("g", data) }
        server.exists("KeepClear/g") shouldBe false
    }

    @Test
    fun `tokens never show in toString`() {
        val auth = server.auth()
        val t = target(auth = auth)
        auth.accessToken()
        t.toString() shouldNotContain "sl."
        auth.toString() shouldNotContain "sl."
        DropboxTokens("sl.secret", 100, "refresh-secret").toString().let {
            it shouldNotContain "secret"
        }
        DropboxOAuth.refresh(server.appKey, "refresh-secret").toString() shouldNotContain "secret"
    }

    // ---------------------------------------------------------------------------------------- conflicts

    @Test
    fun `never overwrites, also when the name appears after the check or differs only in case`(): Unit =
        runBlocking {
            val t = target()
            t.put("a.txt", "one".toByteArray())
            shouldThrow<StorageException.AlreadyExists> { t.put("A.TXT", "two".toByteArray()) }
            server.bytes("KeepClear/a.txt") shouldBe "one".toByteArray()
            // Someone else creates the name while our upload is under way: Dropbox refuses (mode=add).
            for (single in listOf(true, false)) {
                val name = if (single) "race1" else "race2"
                val tt = if (single) t else target(singleUploadMax = 0, chunkSize = 4096)
                val out = tt.create(name, data.size.toLong())
                server.putFile("KeepClear/$name", "theirs".toByteArray())
                shouldThrow<StorageException.AlreadyExists> { out.use { it.write(data) } }
                server.bytes("KeepClear/$name") shouldBe "theirs".toByteArray()
            }
            t.put("b", byteArrayOf(2))
            shouldThrow<StorageException.AlreadyExists> { t.rename("a.txt", "B") }
            server.bytes("KeepClear/b") shouldBe byteArrayOf(2)
        }

    @Test
    fun `a full account is no space, and nothing is stored`(): Unit = runBlocking {
        val t = target()
        server.quotaBytes = 10_000
        shouldThrow<StorageException.NoSpace> { t.put("big", data) }
        server.exists("KeepClear/big") shouldBe false
        t.freeBytes() shouldBe 10_000L
    }

    // ----------------------------------------------------------------------------------- busy and gone

    @Test
    fun `429 is disconnected with Retry-After surfaced, 503 is disconnected`(): Unit = runBlocking {
        val t = target()
        t.mkdirs("")
        server.rateLimitNext = 1
        val e = shouldThrow<StorageException.Disconnected> { t.stat("x") }
        e.cause.shouldBeInstanceOf<DropboxRetryAfterException>().retryAfterSeconds shouldBe 7L
        server.retryAfterSeconds = "soon"
        server.rateLimitNext = 1
        val e2 = shouldThrow<StorageException.Disconnected> { t.stat("x") }
        e2.cause.shouldBeInstanceOf<DropboxRetryAfterException>().retryAfterSeconds shouldBe null
        server.serverErrorNext = 1
        shouldThrow<StorageException.Disconnected> { t.stat("x") }
        t.stat("x") shouldBe null
    }

    @Test
    fun `a dropped connection mid-upload is disconnected and leaves nothing`(): Unit = runBlocking {
        val t = target()
        server.dropUploadAfter = 1_000
        shouldThrow<StorageException.Disconnected> { t.put("single", data) }
        server.exists("KeepClear/single") shouldBe false
        val s = target(singleUploadMax = 0, chunkSize = 8_192)
        server.dropUploadAfter = 1_000
        shouldThrow<StorageException.Disconnected> { s.put("session", data) }
        server.exists("KeepClear/session") shouldBe false
        // Afterwards everything works again on a new connection.
        t.put("single", data)
        t.read("single") shouldBe data
    }

    @Test
    fun `no answer at all is disconnected`(): Unit = runBlocking {
        val t = target()
        t.mkdirs("")
        server.close()
        shouldThrow<StorageException.Disconnected> { t.stat("x") }
    }

    // ---------------------------------------------------------------------------------- upload sessions

    @Test
    fun `large files go through an upload session in chunks, small ones in one request`(): Unit = runBlocking {
        val t = target(singleUploadMax = 10_000, chunkSize = 4_096)
        t.put("small", data.copyOf(10_000))
        count("/2/files/upload") shouldBe 1
        count("/2/files/upload_session/start") shouldBe 0
        t.put("large", data)
        count("/2/files/upload_session/start") shouldBe 1
        count("/2/files/upload_session/append_v2") shouldBe 50_000 / 4_096 - 1
        count("/2/files/upload_session/finish") shouldBe 1
        t.read("large") shouldBe data
        server.openSessions() shouldBe 0
    }

    @Test
    fun `without a known size small writes are one upload and large ones a session`(): Unit = runBlocking {
        val t = target(chunkSize = 4_096)
        t.create("small").use { it.write(data, 0, 100) }
        count("/2/files/upload") shouldBe 1
        t.create("large").use { out -> data.toList().chunked(999).forEach { out.write(it.toByteArray()) } }
        count("/2/files/upload_session/finish") shouldBe 1
        t.read("small") shouldBe data.copyOf(100)
        t.read("large") shouldBe data
    }

    @Test
    fun `more or fewer bytes than announced never leave a file`(): Unit = runBlocking {
        for (t in listOf(target(), target(singleUploadMax = 0, chunkSize = 4_096))) {
            shouldThrow<StorageException.Io> { t.create("more", 10).use { it.write(ByteArray(11)) } }
            shouldThrow<StorageException.Io> { t.create("fewer", 10_000).use { it.write(ByteArray(9_999)) } }
            server.exists("KeepClear/more") shouldBe false
            server.exists("KeepClear/fewer") shouldBe false
        }
    }

    // ----------------------------------------------------------------------------------- untrusted (SEC)

    @Test
    fun `listing entries outside the folder or with unsafe names are ignored`(): Unit = runBlocking {
        val t = target()
        t.mkdirs("d/sub")
        t.put("d/f1", byteArrayOf(1))
        t.put("d/f2", byteArrayOf(2))
        t.put("d/f3", byteArrayOf(3))
        server.hostileEntries = true
        t.list("d").map { it.name } shouldContainExactlyInAnyOrder listOf("sub", "f1", "f2", "f3")
        count("/2/files/list_folder/continue") shouldNotBe 0
    }

    @Test
    fun `oversized, deeply nested or mismatched answers are refused as unreadable`(): Unit = runBlocking {
        val t = target()
        t.put("f", data)
        server.hugeListBytes = DropboxBackupTarget.MAX_RPC_BYTES + 1
        shouldThrow<StorageException.Io> { t.list("") }
        server.hugeListBytes = null
        server.deepJson = 100_000
        shouldThrow<StorageException.Io> { t.stat("f") }
        server.deepJson = null
        server.metadataForOtherPath = true
        shouldThrow<StorageException.Io> { t.stat("f") }
        server.metadataForOtherPath = false
        server.longDownload = true
        shouldThrow<StorageException.Io> { t.read("f") }
    }

    @Test
    fun `unsafe relative paths are refused before anything is sent`(): Unit = runBlocking {
        val t = target()
        listOf("../x", "a/../../x", "a/./b", "/abs", "a\\b").forEach { bad ->
            shouldThrow<IllegalArgumentException> { t.stat(bad) }
        }
        shouldThrow<IllegalArgumentException> { target(folder = "../escape") }
    }

    @Test
    fun `the Dropbox-API-Arg header is plain ASCII, also for Hebrew and emoji names`() {
        val arg = kotlinx.serialization.json.buildJsonObject {
            put("path", kotlinx.serialization.json.JsonPrimitive("/תמונה 🎂\n.jpg"))
        }
        val header = DropboxJson.header(arg)
        header.all { it.code in 0x20..0x7e } shouldBe true
        DropboxJson.parse(header.toByteArray()).toString() shouldBe arg.toString()
    }

    @Test
    fun `folders are never deleted, empty or not, files only in the revision seen`(): Unit = runBlocking {
        val t = target()
        t.caps.canDeleteFolders shouldBe false
        t.put("d/f", data)
        t.mkdirs("d/empty")
        // Dropbox would delete a folder with whatever another device just added; KeepClear never asks.
        shouldThrow<StorageException.Unsupported> { t.delete("d/empty") }
        shouldThrow<StorageException.Unsupported> { t.delete("d") }
        server.isFolder("KeepClear/d/empty") shouldBe true
        server.bytes("KeepClear/d/f") shouldBe data
        t.delete("d/f")
        server.files().shouldBeEmpty()
        server.isFolder("KeepClear/d") shouldBe true
        server.requests.filter { it.startsWith("/2/files/delete_v2 ") } shouldBe
            listOf("/2/files/delete_v2 /KeepClear/d/f")
    }
}
