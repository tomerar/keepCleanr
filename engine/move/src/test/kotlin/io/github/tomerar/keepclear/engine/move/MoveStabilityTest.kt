// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.move

import io.github.tomerar.keepclear.core.storage.FakeStorageGateway
import io.github.tomerar.keepclear.core.storage.FakeStorageGateway.Op
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.engine.scan.ScanItem
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/** Owner review PR E: stopping a copy mid-file (E2), and a file that never stops changing (E9). */
class MoveStabilityTest {
    @Test
    fun `a pause or the time limit stops a big copy within a buffer, not after the file (E2)`(): Unit = runBlocking {
        val rig = Rig(files = 0)
        val big = "$PHONE/DCIM/Camera/VID_big.mp4"
        val size = 64L * 1024 * 1024
        rig.fake.addFile(big, size, mtime = 1_610_000_000_000L)
        val id = rig.newJob(selection = listOf(ScanItem(KPath.Local(big), big, size, 1_610_000_000_000L)))
        var maxRead = 0L
        lateinit var run: Job
        rig.fake.onReadProgress = { p, bytes ->
            if (p.toString() == big) {
                maxRead = maxOf(maxRead, bytes)
                if (bytes >= 2L * 1024 * 1024) run.cancel()
            }
        }
        run = launch(Dispatchers.Default) {
            MoveEngine(
                rig.fake,
                rig.target,
                rig.store,
                clock = { rig.now },
                cancelReason = { PauseReason.SYSTEM_TIME_LIMIT },
            ).run(id)
        }
        run.join()
        run.isCancelled shouldBe true
        // At most a couple of 1 MiB buffers past the moment it was asked to stop, not the whole 64 MiB.
        maxRead shouldBeLessThan 5L * 1024 * 1024
        rig.store.job(id)!!.pauseReason shouldBe PauseReason.SYSTEM_TIME_LIMIT
        // The half-written copy is cleaned up when the job continues, and the copy completes.
        rig.fake.onReadProgress = null
        rig.engine().run(id).state shouldBe JobState.DONE
        rig.fake.files(USB).none { ".kc-tmp" in it } shouldBe true
    }

    @Test
    fun `a file that keeps changing is settled like a failed one, the rest of the job is done (E9)`(): Unit =
        runBlocking {
            val rig = Rig(files = 3)
            val busy = rig.items.first().path
            var n = 0
            // WhatsApp keeps writing this one: it changes during every copy.
            rig.fake.onReadProgress = { p, _ ->
                if (p.toString() == busy) {
                    rig.fake.modify(
                        busy,
                        FakeStorageGateway.Content.Bytes(ByteArray(11_000 + ++n) { 1 }),
                        mtime = 1_700_000_000_000 + n,
                    )
                }
            }
            val job = rig.engine().run(rig.newJob())
            job.state shouldBe JobState.DONE
            val receipt = rig.engine().receipt(job.id)
            receipt.complete shouldBe true
            receipt.verified shouldBe 2
            receipt.changed shouldBe 1
            // Continuing the job doesn't copy it again and again; it stays settled.
            var opened = 0
            rig.fake.beforeOp = { op, p -> if (op == Op.OPEN_READ && p.toString() == busy) opened++ }
            rig.engine().run(job.id).state shouldBe JobState.DONE
            opened shouldBe 0
        }
}
