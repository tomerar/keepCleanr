// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.backup.dropbox

import io.github.tomerar.keepclear.core.storage.FakeStorageGateway
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.engine.move.FailReason
import io.github.tomerar.keepclear.engine.move.FreshRead
import io.github.tomerar.keepclear.engine.move.InMemoryMoveJobStore
import io.github.tomerar.keepclear.engine.move.JobState
import io.github.tomerar.keepclear.engine.move.MoveEngine
import io.github.tomerar.keepclear.engine.move.MoveFileState
import io.github.tomerar.keepclear.engine.move.MoveJob
import io.github.tomerar.keepclear.engine.move.MovePlanner
import io.github.tomerar.keepclear.engine.move.PauseReason
import io.github.tomerar.keepclear.engine.scan.ScanItem
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * M17c end to end: the MOVE engine against the fake Dropbox. The no-loss rule: a copy whose `content_hash` doesn't
 * match is **never** verified, and nothing is offered for freeing unless the copy re-hashes at free time.
 */
class DropboxMoveTest {
    private val server = FakeDropboxServer()
    private val phone = "/storage/emulated/0"
    private val fake = FakeStorageGateway(caseInsensitive = true, volumeRoots = listOf(phone))
    private val store = InMemoryMoveJobStore()
    private val items = (0 until 4).map { i ->
        val path = "$phone/DCIM/Camera/IMG_2021010${i}_120000.jpg"
        fake.addFile(path, ByteArray(30_000 + i) { (it * (i + 3)).toByte() }, mtime = 1_610_000_000_000L + i)
        ScanItem(KPath.Local(path), path, (30_000 + i).toLong(), 1_610_000_000_000L + i)
    }

    @AfterEach
    fun stop() = server.close()

    private suspend fun open(): DropboxBackupTarget {
        val ok = DropboxTargets.open(server.auth(), "", "Dropbox", server.endpoints, timeoutSeconds = 5)
        return (ok as DropboxCheck.Ok).target
    }

    private suspend fun job(target: DropboxBackupTarget): String {
        store.putJob(MoveJob("j", target.id, target.label, MovePlanner.rootRel("Phone"), 1))
        MovePlanner.plan("j", items, "Phone", "Camera", target.caps).forEach { store.putFile(it) }
        return "j"
    }

    private fun engine(target: DropboxBackupTarget) = MoveEngine(fake, target, store, clock = { 9L })

    private fun downloads() =
        server.requests.count { it.startsWith("/2/files/download ") && "/camera/" in it.lowercase() }

    private suspend fun nothingVerifiedOrFreeable(target: DropboxBackupTarget, id: String) {
        store.files(id).forEach { it.state shouldBe MoveFileState.FAILED }
        store.files(id).forEach { it.failReason shouldBe FailReason.HASH_MISMATCH }
        engine(target).receipt(id).verified shouldBe 0
        engine(target).originalsPlan(id).shouldBeEmpty()
    }

    @Test
    fun `Dropbox verifies by its own content hash, without downloading, free time re-reads`(): Unit = runBlocking {
        val target = open()
        val id = job(target)
        engine(target).run(id).state shouldBe JobState.DONE
        downloads() shouldBe 0
        store.files(id).all { it.state == MoveFileState.VERIFIED && it.confirmedAt != null } shouldBe true
        engine(target).originalsPlan(id).size shouldBe 4
        downloads() shouldBe 4
        items.forEach { item ->
            val f = store.files(id).single { it.sourcePath == item.path }
            server.bytes(f.relTarget) shouldBe fake.readAllBytes(item.path)
        }
        server.files().filter { ".kc-tmp" in it }.shouldBeEmpty()
    }

    @Test
    fun `a wrong content hash never gets a file verified`(): Unit = runBlocking {
        val target = open()
        server.hashMode = FakeDropboxServer.HashMode.WRONG
        val id = job(target)
        engine(target).run(id)
        nothingVerifiedOrFreeable(target, id)
    }

    @Test
    fun `a malformed content hash falls back to downloading, then the check over a new connection`(): Unit =
        runBlocking {
            val target = open()
            server.hashMode = FakeDropboxServer.HashMode.GARBAGE
            val id = job(target)
            engine(target).run(id).state shouldBe JobState.DONE
            downloads() shouldBe 4
            store.files(id).all { it.state == MoveFileState.VERIFIED && it.confirmedAt == null } shouldBe true
            engine(target).originalsPlan(id).shouldBeEmpty()
            target.close()
            engine(target).confirm(id, FreshRead.NEW_SESSION).ok shouldBe 4
            engine(target).originalsPlan(id).size shouldBe 4
        }

    @Test
    fun `a missing content hash falls back to downloading`(): Unit = runBlocking {
        val target = open()
        server.hashMode = FakeDropboxServer.HashMode.MISSING
        val id = job(target)
        engine(target).run(id).state shouldBe JobState.DONE
        downloads() shouldBe 4
        store.files(id).all { it.state == MoveFileState.VERIFIED && it.confirmedAt == null } shouldBe true
    }

    @Test
    fun `Dropbox hashing what it received but storing damage is caught at free time`(): Unit = runBlocking {
        val target = open()
        server.hashMode = FakeDropboxServer.HashMode.RECEIVED
        server.corruptStored = { b -> b.copyOf().also { it[0] = (it[0] + 1).toByte() } }
        val id = job(target)
        engine(target).run(id)
        // The server's word is the ADR 0011 proof; the re-hash before freeing still refuses every copy.
        store.files(id).all { it.state == MoveFileState.VERIFIED } shouldBe true
        engine(target).originalsPlan(id).shouldBeEmpty()
    }

    @Test
    fun `a dropped connection pauses the job, and it finishes when resumed`(): Unit = runBlocking {
        val target = open()
        val id = job(target)
        server.dropUploadAfter = 1_000
        val paused = engine(target).run(id)
        paused.state shouldBe JobState.PAUSED
        paused.pauseReason shouldBe PauseReason.TARGET_DISCONNECTED
        engine(target).run(id).state shouldBe JobState.DONE
        store.files(id).all { it.state == MoveFileState.VERIFIED && it.confirmedAt != null } shouldBe true
        server.files().filter { ".kc-tmp" in it }.shouldBeEmpty()
    }

    @Test
    fun `rate limiting pauses the job as disconnected`(): Unit = runBlocking {
        val target = open()
        val id = job(target)
        server.rateLimitNext = 1
        engine(target).run(id).pauseReason shouldBe PauseReason.TARGET_DISCONNECTED
        engine(target).run(id).state shouldBe JobState.DONE
    }

    @Test
    fun `a full account pauses the job as full`(): Unit = runBlocking {
        val target = open()
        val id = job(target)
        server.quotaBytes = 50_000
        val paused = engine(target).run(id)
        paused.state shouldBe JobState.PAUSED
        paused.pauseReason shouldBe PauseReason.TARGET_FULL
        // The one copy that fit is verified by Dropbox's hash; only that one may be freed.
        store.files(id).count { it.state == MoveFileState.VERIFIED } shouldBe 1
        engine(target).originalsPlan(id).size shouldBe 1
    }

    @Test
    fun `a signed-out account pauses the job as access lost`(): Unit = runBlocking {
        val target = open()
        val id = job(target)
        server.expireAccessTokens()
        server.refreshRefused = true
        engine(target).run(id).pauseReason shouldBe PauseReason.ACCESS_LOST
        store.files(id).none { it.state == MoveFileState.VERIFIED } shouldBe true
    }

    @Test
    fun `large files go through upload sessions and verify by the block hash`(): Unit = runBlocking {
        val big = "$phone/DCIM/Camera/VID_20210101_120000.mp4"
        val bytes = ByteArray(9 * 1024 * 1024 + 123) { (it * 13 + it / 4096).toByte() }
        fake.addFile(big, bytes, mtime = 1_610_000_000_100L)
        val ok = DropboxTargets.open(server.auth(), "", "Dropbox", server.endpoints, timeoutSeconds = 10)
        val opened = (ok as DropboxCheck.Ok).target
        val target = DropboxBackupTarget(
            server.auth(),
            "",
            opened.id,
            "Dropbox",
            server.endpoints,
            timeoutSeconds = 10,
            singleUploadMax = 1024 * 1024,
            chunkSize = 4 * 1024 * 1024,
        )
        store.putJob(MoveJob("v", target.id, target.label, MovePlanner.rootRel("Phone"), 1))
        val item = ScanItem(KPath.Local(big), big, bytes.size.toLong(), 1_610_000_000_100L)
        MovePlanner.plan("v", listOf(item), "Phone", "Camera", target.caps).forEach { store.putFile(it) }
        MoveEngine(fake, target, store, clock = { 9L }).run("v").state shouldBe JobState.DONE
        server.requests.count { it.startsWith("/2/files/upload_session/append_v2 ") } shouldBe 1
        downloads() shouldBe 0
        val f = store.files("v").single()
        f.state shouldBe MoveFileState.VERIFIED
        f.confirmedAt shouldBe 9L
        server.bytes(f.relTarget) shouldBe bytes
    }
}
