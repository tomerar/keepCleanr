// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.graphics.Bitmap
import android.os.CancellationSignal
import android.util.Size
import io.github.tomerar.keepclear.core.storage.FakeStorageGateway
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.engine.classify.Classifier
import io.github.tomerar.keepclear.engine.scan.ScanItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** E13 lows: thumbnail decodes stop when their tile goes away; a rescan doesn't hash the same files again. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LowsTest {
    @Test
    fun `leaving a tile cancels its thumbnail decode`(): Unit = runBlocking {
        val decoding = CountDownLatch(1)
        var seen: CancellationSignal? = null
        val loader = object : ThumbnailLoader(null) {
            override fun decode(item: ScanItem, size: Size, signal: CancellationSignal): Bitmap? {
                seen = signal
                decoding.countDown()
                // A large video: Android's decoder checks the signal while it reads.
                val end = System.currentTimeMillis() + 10_000
                while (!signal.isCanceled && System.currentTimeMillis() < end) Thread.sleep(5)
                return null
            }
        }
        val item =
            ScanItem(
                KPath.Local("/storage/emulated/0/DCIM/Camera/big.mp4"),
                "/storage/emulated/0/DCIM/Camera/big.mp4",
                1,
                1,
            )
        val job = async(Dispatchers.Default) { loader.load(item, 96) }
        assertTrue(decoding.await(5, TimeUnit.SECONDS))
        job.cancel()
        withTimeout(5_000) { while (seen?.isCanceled != true) kotlinx.coroutines.delay(5) }
        assertTrue(seen!!.isCanceled)
    }

    @Test
    fun `a rescan reads a Sent copy and its original only once, and again only after a change`(): Unit = runBlocking {
        val gateway = FakeStorageGateway()
        val root = "/storage/emulated/0"
        val sent = "$root/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/Sent/IMG-1.jpg"
        val original = "$root/DCIM/Camera/IMG_1.jpg"
        val bytes = ByteArray(20_000) { (it % 251).toByte() }
        gateway.addFile(sent, bytes)
        gateway.addFile(original, bytes)
        var reads = 0
        gateway.beforeOp = { op, _ -> if (op == FakeStorageGateway.Op.OPEN_READ) reads++ }

        fun items() = listOf(sent, original).map { p ->
            val stat = runBlocking { gateway.stat(KPath.Local(p))!! }
            ScanItem(KPath.Local(p), p, stat.size, stat.mtimeMillis, identity = stat.identity)
        }
        val cache = MemoryHashCache()
        val now = System.currentTimeMillis()
        val (_, first) = classifyRun(gateway, Classifier(), items(), now, cache)
        assertEquals(1, first.size) // the Sent copy is redundant
        val firstReads = reads
        reads = 0
        val (_, second) = classifyRun(gateway, Classifier(), items(), now, cache)
        assertEquals(first, second)
        // Only the cheap head/tail prehash again; no full read of either file.
        assertTrue("reads: $reads of $firstReads", reads <= firstReads - 2)
        // A changed date means the file is read again.
        val changed = items().map { if (it.path == original) it.copy(mtimeMillis = it.mtimeMillis + 1) else it }
        reads = 0
        classifyRun(gateway, Classifier(), changed, now, cache)
        assertTrue(reads >= 3)
    }
}
