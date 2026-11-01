// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.backup.webdav

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
 * M17 end to end: the MOVE engine against the fake WebDAV server. The no-loss rule: a copy whose bytes or hash
 * don't match is **never** verified, and nothing is offered for freeing unless the copy re-hashes at free time.
 */
class WebDavMoveTest {
    private val server = FakeDavServer()
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

    private suspend fun open(nextcloud: Boolean): WebDavBackupTarget {
        server.nextcloud = nextcloud
        val ok = WebDavTargets.open(server.location("KeepClear", nextcloud), "Cloud", timeoutSeconds = 5)
        return (ok as WebDavCheck.Ok).target
    }

    private suspend fun job(target: WebDavBackupTarget): String {
        store.putJob(MoveJob("j", target.id, target.label, MovePlanner.rootRel("Phone"), 1))
        MovePlanner.plan("j", items, "Phone", "Camera", target.caps).forEach { store.putFile(it) }
        return "j"
    }

    private fun engine(target: WebDavBackupTarget) = MoveEngine(fake, target, store, clock = { 9L })

    private fun dataGets() = server.requests.count { it.startsWith("GET") && "/Camera/" in it }

    private suspend fun nothingVerifiedOrFreeable(target: WebDavBackupTarget, id: String) {
        store.files(id).forEach { it.state shouldBe MoveFileState.FAILED }
        store.files(id).forEach { it.failReason shouldBe FailReason.HASH_MISMATCH }
        engine(target).receipt(id).verified shouldBe 0
        engine(target).originalsPlan(id).shouldBeEmpty()
    }

    @Test
    fun `generic WebDAV verifies by reading back, then frees only after the check over a new connection`(): Unit =
        runBlocking {
            val target = open(nextcloud = false)
            val id = job(target)
            engine(target).run(id).state shouldBe JobState.DONE
            dataGets() shouldBe 4
            store.files(id).all { it.state == MoveFileState.VERIFIED && it.confirmedAt == null } shouldBe true
            engine(target).originalsPlan(id).shouldBeEmpty()
            target.close()
            engine(target).confirm(id, FreshRead.NEW_SESSION).ok shouldBe 4
            engine(target).originalsPlan(id).size shouldBe 4
            items.forEach { item ->
                val f = store.files(id).single { it.sourcePath == item.path }
                server.bytes("KeepClear/${f.relTarget}") shouldBe fake.readAllBytes(item.path)
            }
            server.files().filter { ".kc-tmp" in it }.shouldBeEmpty()
        }

    @Test
    fun `Nextcloud verifies by the server's own SHA-256, without downloading`(): Unit = runBlocking {
        val target = open(nextcloud = true)
        val id = job(target)
        engine(target).run(id).state shouldBe JobState.DONE
        dataGets() shouldBe 0
        store.files(id).all { it.state == MoveFileState.VERIFIED && it.confirmedAt != null } shouldBe true
        // Free time still re-reads every copy (ADR 0007 re-check).
        engine(target).originalsPlan(id).size shouldBe 4
        dataGets() shouldBe 4
    }

    @Test
    fun `a Nextcloud that answers a wrong hash never gets a file verified`(): Unit = runBlocking {
        server.hashMode = FakeDavServer.HashMode.WRONG
        val target = open(nextcloud = true)
        val id = job(target)
        engine(target).run(id)
        nothingVerifiedOrFreeable(target, id)
    }

    @Test
    fun `a server that truncates what it stores never gets a file verified`(): Unit = runBlocking {
        val target = open(nextcloud = false)
        server.corruptStored = { it.copyOf(it.size - 1) }
        val id = job(target)
        engine(target).run(id)
        nothingVerifiedOrFreeable(target, id)
    }

    @Test
    fun `a server that flips a bit is caught by the read-back`(): Unit = runBlocking {
        val target = open(nextcloud = false)
        server.corruptStored = { b -> b.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() } }
        val id = job(target)
        engine(target).run(id)
        nothingVerifiedOrFreeable(target, id)
    }

    @Test
    fun `Nextcloud hashing what it received but storing damage is caught at free time`(): Unit = runBlocking {
        val target = open(nextcloud = true)
        server.hashMode = FakeDavServer.HashMode.RECEIVED
        server.corruptStored = { b -> b.copyOf().also { it[0] = (it[0] + 1).toByte() } }
        val id = job(target)
        engine(target).run(id)
        // The server's word is the ADR 0011 proof; the re-hash before freeing still refuses every copy.
        engine(target).originalsPlan(id).shouldBeEmpty()
    }

    @Test
    fun `a dropped connection pauses the job, and it finishes when resumed`(): Unit = runBlocking {
        val target = open(nextcloud = true)
        val id = job(target)
        server.dropUploadAfter = 1_000
        val paused = engine(target).run(id)
        paused.state shouldBe JobState.PAUSED
        paused.pauseReason shouldBe PauseReason.TARGET_DISCONNECTED
        engine(target).run(id).state shouldBe JobState.DONE
        store.files(id).all { it.state == MoveFileState.VERIFIED } shouldBe true
        server.files().filter { ".kc-tmp" in it }.shouldBeEmpty()
    }

    @Test
    fun `a full account pauses the job as full`(): Unit = runBlocking {
        val target = open(nextcloud = false)
        val id = job(target)
        server.quotaBytes = 50_000
        val paused = engine(target).run(id)
        paused.state shouldBe JobState.PAUSED
        paused.pauseReason shouldBe PauseReason.TARGET_FULL
        engine(target).originalsPlan(id).size shouldBe 0
    }
}
