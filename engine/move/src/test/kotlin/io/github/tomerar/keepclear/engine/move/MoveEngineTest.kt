// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.move

import io.github.tomerar.keepclear.backup.TargetCaps
import io.github.tomerar.keepclear.core.hash.Sha256
import io.github.tomerar.keepclear.core.safety.OpKind
import io.github.tomerar.keepclear.core.storage.FakeStorageGateway
import io.github.tomerar.keepclear.core.storage.FakeStorageGateway.Op
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.core.storage.StorageException
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class MoveEngineTest {
    private fun Rig.verifiedTargets() = store.files.values.filter { it.state == MoveFileState.VERIFIED }

    private fun Rig.assertCopiesMatchSources() {
        for (f in verifiedTargets()) {
            Sha256.of(fake.readAllBytes("$USB/${f.relTarget}")) shouldBe Sha256.of(fake.readAllBytes(f.sourcePath))
            f.sourceSha256 shouldBe Sha256.of(fake.readAllBytes(f.sourcePath))
        }
    }

    private fun Rig.temps() = fake.files(USB).filter { ".kc-tmp" in it }

    @Test
    fun `copies, verifies by read-back and writes the manifest (MOVE-6, MOVE-8)`(): Unit = runBlocking {
        val rig = Rig()
        val before = rig.sourceSnapshot()
        val job = rig.engine().run(rig.newJob())
        job.state shouldBe JobState.DONE
        rig.verifiedTargets().size shouldBe 6
        rig.assertCopiesMatchSources()
        rig.temps().shouldBeEmpty()
        val sums = rig.fake.readAllBytes("$USB/KeepClear/OnePlus 10T/SHA256SUMS").toString(Charsets.UTF_8)
            .lines()
            .filter { it.isNotBlank() }
        sums.size shouldBe 6
        val firstSha = Sha256.of(rig.fake.readAllBytes(rig.items.first().path))
        sums.first() shouldBe "$firstSha  Camera/2021/01/IMG_20210100_120000.jpg"
        rig.sourceSnapshot() shouldBe before // the engine never changes a source
        val receipt = rig.engine().receipt("job1")
        receipt.verified shouldBe 6
        receipt.failed shouldBe emptyMap()
    }

    @Test
    fun `sources are only ever read`(): Unit = runBlocking {
        val rig = Rig()
        val sourceOps = mutableSetOf<Op>()
        rig.fake.beforeOp = { op, p -> if (p.toString().startsWith(PHONE)) sourceOps += op }
        rig.engine().run(rig.newJob())
        (sourceOps - setOf(Op.STAT, Op.OPEN_READ, Op.LIST)) shouldBe emptySet()
    }

    private val thirdFileTemp = "KeepClear/OnePlus 10T/Camera/2021/01/.kc-tmp-job1-2"

    @Test
    fun `unplugged drive pauses the job, and it resumes after reconnect (E-MOVE-2, E-MOVE-1)`(): Unit = runBlocking {
        val rig = Rig()
        // The drive goes away halfway through the third file (faults are per output stream).
        rig.fake.failAfterBytes(Op.WRITE, 5_000, matches = { it.toString() == "$USB/$thirdFileTemp" })
        val job = rig.engine().run(rig.newJob())
        job.state shouldBe JobState.PAUSED
        job.pauseReason shouldBe PauseReason.TARGET_DISCONNECTED
        rig.temps().shouldBeEmpty()
        rig.engine().run("job1").state shouldBe JobState.DONE
        rig.assertCopiesMatchSources()
        rig.verifiedTargets().size shouldBe 6
    }

    @Test
    fun `full drive pauses with TARGET_FULL (E-MOVE-3)`(): Unit = runBlocking {
        val rig = Rig(targetCapacity = 25_000)
        val job = rig.engine().run(rig.newJob())
        job.pauseReason shouldBe PauseReason.TARGET_FULL
        rig.temps().shouldBeEmpty()
        rig.verifiedTargets().size shouldBe 2
        rig.assertCopiesMatchSources()
    }

    @Test
    fun `source changing during the copy is detected and retried (E-MOVE-6)`(): Unit = runBlocking {
        val rig = Rig(files = 1)
        val path = rig.items.single().path
        var changes = 1
        rig.fake.onReadProgress = { p, _ ->
            if (p.toString() == path && changes > 0) {
                changes--
                rig.fake.modify(
                    path,
                    FakeStorageGateway.Content.Bytes(ByteArray(12_345) { 5 }),
                    mtime = 1_700_000_000_000,
                )
            }
        }
        rig.engine().run(rig.newJob()).state shouldBe JobState.DONE
        val f = rig.store.files.values.single()
        f.state shouldBe MoveFileState.VERIFIED
        f.sourceSize shouldBe 12_345
        rig.assertCopiesMatchSources()
    }

    @Test
    fun `damaged copy is re-copied, then fails for good (E-MOVE-7)`(): Unit = runBlocking {
        val once = Rig(files = 1).apply { corruptReads = 1 }
        once.engine().run(once.newJob())
        once.store.files.values.single().state shouldBe MoveFileState.VERIFIED

        val always = Rig(files = 1).apply { corruptReads = 100 }
        always.engine().run(always.newJob())
        val f = always.store.files.values.single()
        f.state shouldBe MoveFileState.FAILED
        f.failReason shouldBe FailReason.HASH_MISMATCH
        always.engine().originalsPlan("job1").shouldBeEmpty()
    }

    @Test
    fun `files too large for FAT32 fail alone (E-MOVE-8)`(): Unit = runBlocking {
        val rig = Rig(caps = TargetCaps(canReadBack = true, maxFileSize = 10_002))
        rig.engine().run(rig.newJob())
        rig.store.files.values.count { it.failReason == FailReason.TOO_LARGE_FOR_TARGET } shouldBe 3
        rig.verifiedTargets().size shouldBe 3
    }

    @Test
    fun `identical file on the drive is reused, a different one is never overwritten (E-MOVE-10)`(): Unit =
        runBlocking {
            val rig = Rig(files = 2)
            val first = rig.items[0]
            val dir = "$USB/KeepClear/OnePlus 10T/Camera/2021/01"
            rig.fake.addFile("$dir/IMG_20210100_120000.jpg", rig.fake.readAllBytes(first.path))
            val foreign = "someone else's file".toByteArray()
            rig.fake.addFile("$dir/IMG_20210101_120000.jpg", foreign)
            rig.engine().run(rig.newJob())
            rig.verifiedTargets().size shouldBe 2
            rig.fake.readAllBytes("$dir/IMG_20210101_120000.jpg") shouldBe foreign
            val second = rig.store.files.values.single { it.index == 1 }
            second.relTarget.endsWith("IMG_20210101_120000 (2).jpg") shouldBe true
            rig.assertCopiesMatchSources()
        }

    @Test
    fun `originals plan only has verified, unchanged files whose copy is present (MOVE-10)`(): Unit = runBlocking {
        val rig = Rig(files = 3)
        rig.engine().run(rig.newJob())
        rig.engine().confirm("job1", FreshRead.REATTACHED).ok shouldBe 3
        rig.fake.modify(rig.items[0].path, FakeStorageGateway.Content.Bytes(ByteArray(3)), mtime = 9)
        rig.fake.delete(KPath.Local("$USB/${rig.store.files.values.single { it.index == 1 }.relTarget}"))
        val plan = rig.engine().originalsPlan("job1")
        plan.map { it.displayPath } shouldBe listOf(rig.items[2].path)
        plan.single().kind shouldBe OpKind.MOVE_DELETE_ORIGINAL
        plan.single().expectedSha256 shouldBe Sha256.of(rig.fake.readAllBytes(rig.items[2].path))
        plan.single().verifiedCopy!!.startsWith("SanDisk: KeepClear/") shouldBe true
    }

    @Test
    fun `no deletion is ever offered when the target cannot be read back (MOVE-14)`(): Unit = runBlocking {
        val rig = Rig(caps = TargetCaps(canReadBack = false))
        rig.engine().run(rig.newJob())
        rig.engine().originalsPlan("job1").shouldBeEmpty()
    }

    @Test
    fun `an unexpected storage error pauses the job instead of crashing (review Low)`(): Unit = runBlocking {
        val rig = Rig()
        rig.fake.fail(Op.MKDIR, matches = { it.toString().startsWith(USB) }) { StorageException.Unsupported(it, "odd") }
        val end = rig.engine().run(rig.newJob())
        end.state shouldBe JobState.PAUSED
        end.pauseReason shouldBe PauseReason.UNEXPECTED_ERROR
    }

    @Test
    fun `revoked access pauses the job (E-MOVE-17)`(): Unit = runBlocking {
        val rig = Rig()
        rig.fake.fail(Op.CREATE, matches = { it.toString().startsWith(USB) }) { StorageException.PermissionDenied(it) }
        rig.engine().run(rig.newJob()).pauseReason shouldBe PauseReason.ACCESS_LOST
    }

    @Test
    fun `a reused file that later fails read-back is never deleted, a fresh copy is made instead`(): Unit =
        runBlocking {
            val rig = Rig(files = 1)
            val existing = "$USB/KeepClear/OnePlus 10T/Camera/2021/01/IMG_20210100_120000.jpg"
            val bytes = rig.fake.readAllBytes(rig.items.single().path)
            rig.fake.addFile(existing, bytes)
            rig.skipReads = 1 // the match check reads clean
            rig.corruptReads = 1 // the verify read-back of the reused file fails
            rig.engine().run(rig.newJob()).state shouldBe JobState.DONE
            rig.fake.readAllBytes(existing) shouldBe bytes
            val file = rig.store.files.values.single()
            file.state shouldBe MoveFileState.VERIFIED
            file.reusedExisting shouldBe false
            file.relTarget.endsWith("IMG_20210100_120000 (2).jpg") shouldBe true
            rig.assertCopiesMatchSources()
        }

    @Test
    fun `an existing manifest that can't be read is kept, and this job's entries go to a separate file`(): Unit =
        runBlocking {
            val rig = Rig(files = 1)
            val sums = "$USB/KeepClear/OnePlus 10T/${Manifest.SUMS}"
            rig.fake.addFile(sums, "old entries\n".toByteArray())
            rig.fake.fail(Op.OPEN_READ, matches = { it.toString() == sums })
            rig.engine().run(rig.newJob()).state shouldBe JobState.DONE
            rig.fake.readAllBytes(sums).toString(Charsets.UTF_8) shouldBe "old entries\n"
            rig.fake.readAllBytes("$sums-job1").toString(Charsets.UTF_8).lines().first().length shouldBe 64 + 2 +
                "Camera/2021/01/IMG_20210100_120000.jpg".length
        }

    @Test
    fun `pausing from outside records the reason and the job resumes to the end (MOVE-12)`(): Unit = runBlocking {
        val rig = Rig()
        val id = rig.newJob()
        var verified = 0
        val run = launch(kotlinx.coroutines.Dispatchers.Default) {
            MoveEngine(
                rig.fake,
                rig.target,
                rig.store,
                clock = { rig.now },
                onProgress = {
                    if (it.state == MoveFileState.VERIFIED &&
                        ++verified == 2
                    ) {
                        this.coroutineContext.job.cancel()
                    }
                },
                cancelReason = { PauseReason.SYSTEM_TIME_LIMIT },
            ).run(id)
        }
        run.join()
        run.isCancelled shouldBe true
        val paused = rig.store.job(id)!!
        paused.state shouldBe JobState.PAUSED
        paused.pauseReason shouldBe PauseReason.SYSTEM_TIME_LIMIT
        rig.engine().run(id).state shouldBe JobState.DONE
        rig.verifiedTargets().size shouldBe 6
        rig.assertCopiesMatchSources()
    }

    @Test
    fun `a target that can't be read back keeps every copy, unverified (MOVE-14)`(): Unit = runBlocking {
        val rig = Rig(caps = TargetCaps(canReadBack = false), readable = false)
        rig.engine().run(rig.newJob()).state shouldBe JobState.DONE
        val files = rig.store.files("job1")
        files.map { it.state }.toSet() shouldBe setOf(MoveFileState.COPIED_UNVERIFIED)
        files.forEach { rig.fake.readAllBytes("$USB/${it.relTarget}") shouldBe rig.fake.readAllBytes(it.sourcePath) }
        rig.engine().originalsPlan("job1").shouldBeEmpty()
        val receipt = rig.engine().receipt("job1")
        receipt.verified shouldBe 0
        receipt.unverified shouldBe 6
        receipt.complete shouldBe true
    }

    @Test
    fun `a crash right after renaming to a collision name resumes (checkpointed name)`(): Unit = runBlocking {
        val rig = Rig(files = 1)
        val planned = "$USB/KeepClear/OnePlus 10T/Camera/2021/01/IMG_20210100_120000.jpg"
        val foreign = "not the same".toByteArray()
        rig.fake.addFile(planned, foreign)
        val id = rig.newJob()
        var killed = false
        val crashing = object : MoveJobStore by rig.store {
            override suspend fun putFile(file: MoveFile) {
                if (file.state == MoveFileState.PLACED && !killed) {
                    killed = true
                    throw IllegalStateException("process killed")
                }
                rig.store.putFile(file)
            }
        }
        runCatching { MoveEngine(rig.fake, rig.target, crashing, clock = { rig.now }).run(id) }.isFailure shouldBe true
        rig.engine().run(id).state shouldBe JobState.DONE
        rig.fake.readAllBytes(planned) shouldBe foreign
        val file = rig.store.files(id).single()
        file.state shouldBe MoveFileState.VERIFIED
        file.relTarget.endsWith("IMG_20210100_120000 (2).jpg") shouldBe true
        rig.assertCopiesMatchSources()
    }

    @Test
    fun `an interrupted manifest replace keeps earlier entries`(): Unit = runBlocking {
        val rig = Rig(files = 1)
        val sums = "$USB/KeepClear/OnePlus 10T/SHA256SUMS"
        val earlier = "${"a".repeat(64)}  Camera/2020/01/earlier.jpg\n"
        // As if a previous job was killed after moving the old manifest aside, before installing the new one.
        rig.fake.addFile("$sums.kc-old", earlier.toByteArray())
        rig.fake.addFile("$sums.kc-tmp", "partial".toByteArray())
        rig.engine().run(rig.newJob()).state shouldBe JobState.DONE
        val text = rig.fake.readAllBytes(sums).toString(Charsets.UTF_8)
        text.lines().filter { it.isNotBlank() }.size shouldBe 2
        (earlier.trim() in text) shouldBe true
        rig.fake.exists("$sums.kc-old") shouldBe false
        rig.fake.exists("$sums.kc-tmp") shouldBe false
    }
}
