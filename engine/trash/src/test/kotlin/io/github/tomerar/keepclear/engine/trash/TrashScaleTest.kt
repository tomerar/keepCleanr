// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.trash

import io.github.tomerar.keepclear.core.hash.Sha256
import io.github.tomerar.keepclear.core.safety.ActionExecutor
import io.github.tomerar.keepclear.core.safety.ActionPlan
import io.github.tomerar.keepclear.core.safety.ExecutionReport
import io.github.tomerar.keepclear.core.safety.OpKind
import io.github.tomerar.keepclear.core.safety.OpResult
import io.github.tomerar.keepclear.core.safety.PlannedOp
import io.github.tomerar.keepclear.core.safety.ProtectedPaths
import io.github.tomerar.keepclear.core.safety.SafetyGuard
import io.github.tomerar.keepclear.core.safety.SkipReason
import io.github.tomerar.keepclear.core.safety.Witness
import io.github.tomerar.keepclear.core.storage.FakeStorageGateway
import io.github.tomerar.keepclear.core.storage.KPath
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Owner review PR E at owner scale: the last-copy check reads the trash index once per plan, not once per file (E6),
 * the daily purge never runs in the middle of a plan (E13), impossible sizes in a sidecar are refused (E13).
 */
class TrashScaleTest {
    private val root = "/storage/emulated/0"
    private val fake = FakeStorageGateway(caseInsensitive = true, volumeRoots = listOf(root))
    private val protectedPaths = ProtectedPaths()
    private var ids = 0

    /** Counts full reads of the index. */
    private class CountingIndex(private val d: InMemoryTrashIndex = InMemoryTrashIndex()) : TrashIndex by d {
        var allCalls = 0

        override suspend fun all(): List<TrashEntry> = d.all().also { allCalls++ }
    }

    private val index = CountingIndex()
    private val clock = FakeClock()
    private val trash = TrashManager(
        fake,
        index,
        FakeSigner(),
        clock,
        protectedPaths,
        listOf(root),
        newId = { "00000000-0000-4000-8000-%012d".format(++ids) },
    )

    private fun executor() = ActionExecutor(fake, SafetyGuard(protectedPaths), protectedPaths, trash, "s")

    private suspend fun op(path: String): PlannedOp {
        val s = fake.stat(KPath.Local(path))!!
        return PlannedOp(s.path, path, OpKind.TRASH, false, s.size, s.mtimeMillis, s.identity)
    }

    @Test
    fun `cleaning 2,000 files reads the trash index once, and still sees what the plan itself trashed (E6)`(): Unit =
        runBlocking {
            // A big trash already (owner: 10,000 entries).
            val content = ByteArray(1_000) { 3 }
            val sha = Sha256.of(content)
            val camera = "$root/DCIM/Camera/IMG_0.jpg"
            val sent = "$root/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/Sent/IMG_0.jpg"
            fake.addFile(camera, content, mtime = 10)
            fake.addFile(sent, content, mtime = 10)
            val files = (1..2_000).map { i -> "$root/Download/old/file_$i.bin".also { fake.addFile(it, 100L + i) } }
            // One plan: the Sent copy (its witness is the camera photo), 2,000 other files, then the camera photo.
            val sentOp = op(sent).copy(
                duplicateGroup = "dup:$sha",
                witnesses = listOf(Witness(KPath.Local(camera), camera, content.size.toLong())),
            )
            val ops = listOf(sentOp) + files.map { op(it) } + op(camera)
            index.allCalls = 0
            val plan = ActionPlan("p", ops, "clean", duplicateGroupSizes = mapOf("dup:$sha" to 2)).confirm(1, "s")
            val report = executor().run(plan).shouldBeInstanceOf<ExecutionReport.Ran>()
            index.allCalls shouldBeLessThanOrEqual 1
            report.done.size shouldBe 2_001
            // The camera photo became the last copy during this very plan: still held back (owner decision 1.3).
            (report.results.last() as OpResult.Skipped).reason shouldBe SkipReason.LAST_COPY
        }

    @Test
    fun `the daily purge waits for a running plan (E13)`(): Unit = runBlocking {
        val photo = "$root/DCIM/Camera/IMG_1.jpg"
        fake.addFile(photo, ByteArray(10) { 1 }, mtime = 10)
        executor().run(ActionPlan("p", listOf(op(photo)), "clean").confirm(1, "s"))
        clock.advance(TrashPolicy.RETENTION_MILLIS + 86_400_000L)
        val inside = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val plan = async(Dispatchers.Default) {
            trash.plan {
                inside.complete(Unit)
                release.await()
            }
        }
        inside.await()
        val purge = async(Dispatchers.Default) { trash.purgeExpired() }
        delay(300)
        purge.isCompleted shouldBe false // it waits while the plan runs
        index.all().size shouldBe 1
        release.complete(Unit)
        plan.await()
        purge.await() shouldBe 10L
        index.all().size shouldBe 0
    }

    @Test
    fun `a sidecar with an impossible size is refused (E13)`() {
        val signer = FakeSigner()

        fun sidecar(size: Long, deletedAt: Long = 1) = Sidecar(
            id = "00000000-0000-4000-8000-000000000001",
            originalPath = "$root/a.jpg",
            trashedName = "a.jpg",
            size = size,
            mtimeMillis = 1,
            reason = "TRASH",
            deletedAt = deletedAt,
            expiresAt = 2,
        )
        Sidecar.decode(Sidecar.encode(sidecar(-5), signer), signer)
            .shouldBeInstanceOf<Sidecar.Companion.Decoded.Rejected>()
        Sidecar.decode(Sidecar.encode(sidecar(5, deletedAt = -1), signer), signer)
            .shouldBeInstanceOf<Sidecar.Companion.Decoded.Rejected>()
        Sidecar.decode(Sidecar.encode(sidecar(5), signer), signer)
            .shouldBeInstanceOf<Sidecar.Companion.Decoded.Ok>()
    }
}
