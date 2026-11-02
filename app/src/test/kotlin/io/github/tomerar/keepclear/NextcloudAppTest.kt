// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.test.core.app.ApplicationProvider
import io.github.tomerar.keepclear.backup.webdav.NextcloudAccount
import io.github.tomerar.keepclear.backup.webdav.NextcloudServer
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.engine.move.JobState
import io.github.tomerar.keepclear.engine.move.MoveEngine
import io.github.tomerar.keepclear.engine.move.MoveFileState
import io.github.tomerar.keepclear.engine.move.MoveJob
import io.github.tomerar.keepclear.engine.move.MovePlanner
import io.github.tomerar.keepclear.engine.move.PauseReason
import io.github.tomerar.keepclear.engine.move.VerifyMethod
import io.github.tomerar.keepclear.engine.scan.ScanItem
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

/**
 * M17b: the Nextcloud wired into the app: forgetting it pauses only jobs that back up to it, a stored `davs://`
 * reference reopens only the saved account, and (with the W4 test server, always in CI) a MOVE end to end.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NextcloudAppTest {
    /** KeepClear's real dependency graph without onCreate (as in DeviceCheckCollectorTest), stand-in key store. */
    private val app = KeepClearApplication().apply {
        val attach = android.content.ContextWrapper::class.java
            .getDeclaredMethod("attachBaseContext", android.content.Context::class.java)
        attach.isAccessible = true
        attach.invoke(this, ApplicationProvider.getApplicationContext<android.app.Application>())
        secretBox = { NextcloudStoreTest.FakeBox() }
    }

    @After
    fun close() {
        app.nextcloud.clear()
        app.moveController.setRunning(null)
        app.moveController.stopper = null
        app.database.close()
    }

    private val account = NextcloudAccount(
        NextcloudServer.parse("http://127.0.0.1:9"),
        "dana",
        "dana",
        "A1b2".repeat(18),
    )

    private fun running(ref: String): MutableList<PauseReason> = runBlocking {
        val id = "job-" + UUID.randomUUID()
        app.moveController.store.putJob(MoveJob(id, "t", "x", "KeepClear/P", 1, JobState.RUNNING, targetRef = ref))
        app.moveController.setRunning(id)
        mutableListOf<PauseReason>().also { reasons -> app.moveController.stopper = { reasons += it } }
    }

    @Test
    fun `forgetting the Nextcloud pauses a backup running to it, and only that one (C27)`(): Unit = runBlocking {
        app.nextcloud.save(account)
        var reasons = running("davs://cloud.example.com/remote.php/dav/files/dana")
        app.forgetNextcloud()
        assertEquals(listOf(PauseReason.TARGET_DISCONNECTED), reasons)
        assertNull(app.nextcloud.hostState.value)
        assertNull(app.nextcloud.load())

        reasons = running("smb://nas:445/photos/")
        app.forgetNextcloud()
        assertTrue(reasons.isEmpty())
        // ...and forgetting the network drive doesn't stop a Nextcloud backup.
        reasons = running("davs://cloud.example.com/remote.php/dav/files/dana")
        app.forgetNetworkDrive()
        assertTrue(reasons.isEmpty())
        assertTrue(OpenedTarget.usesNextcloud(MoveJob("j", "t", "x", "r", 1, targetRef = "davs://h/x")))
        assertFalse(OpenedTarget.usesNetworkDrive(MoveJob("j", "t", "x", "r", 1, targetRef = "davs://h/x")))
    }

    @Test
    fun `a davs reference reopens only the saved Nextcloud, and nothing answering is unavailable`(): Unit =
        runBlocking {
            val ref = NextcloudStore.ref(account)
            // Nothing saved: never guessed.
            assertEquals(OpenedTarget.Unavailable, app.openTargetRef(ref, "x"))
            app.nextcloud.save(account)
            // Another place's reference doesn't open the saved account.
            val other = "davs://other.example/remote.php/dav/files/dana"
            assertEquals(OpenedTarget.Unavailable, app.openTargetRef(other, "x"))
            // The saved one is tried; port 9 on this machine doesn't answer.
            assertEquals(OpenedTarget.Unavailable, app.openTargetRef(ref, "x"))
        }

    @Test
    fun `MOVE to the real Nextcloud from the app - checked by the server, recorded, re-read to free`(): Unit =
        runBlocking {
            val url = System.getenv("KEEPCLEAR_NEXTCLOUD_URL")
            assumeTrue("no Nextcloud test server (scripts/nextcloud-test-server.sh)", url != null)
            val real = NextcloudAccount.verify(
                NextcloudServer.parse(url!!),
                System.getenv("KEEPCLEAR_NEXTCLOUD_USER")!!,
                System.getenv("KEEPCLEAR_NEXTCLOUD_PASSWORD")!!,
            )
            app.nextcloud.save(real)
            val ref = NextcloudStore.ref(real)
            val opened = app.openTargetRef(ref, real.server.host) as OpenedTarget.Ok
            val dir = File(app.cacheDir, "nc-move-${UUID.randomUUID()}").apply { mkdirs() }
            val items = (0 until 3).map { i ->
                val f = File(dir, "IMG_$i.jpg").apply { writeBytes(ByteArray(50_000 + i) { (it * (i + 7)).toByte() }) }
                ScanItem(KPath.Local(f.absolutePath), f.absolutePath, f.length(), f.lastModified())
            }
            val store = app.moveController.store
            val id = "nc-" + UUID.randomUUID()
            val device = "AppTest-${UUID.randomUUID()}"
            val label = real.server.host
            store.putJob(MoveJob(id, opened.target.id, label, MovePlanner.rootRel(device), 1, targetRef = ref))
            MovePlanner.plan(id, items, device, "Camera", opened.target.caps).forEach { store.putFile(it) }
            val engine = MoveEngine(app.localGateway, opened.target, store)
            opened.target.useTarget { assertEquals(JobState.DONE, engine.run(id).state) }
            // Room keeps how each copy was verified; the receipt says the server checked them.
            assertTrue(store.files(id).all { it.state == MoveFileState.VERIFIED && it.confirmedAt != null })
            assertTrue(store.files(id).all { it.verifiedBy == VerifyMethod.SERVER_DIGEST })
            assertEquals(3, engine.receipt(id).checkedByServer)
            // Free time: reopened from the stored reference (same marker, same id), every copy re-read.
            val again = app.openTargetRef(ref, real.server.host) as OpenedTarget.Ok
            assertEquals(opened.target.id, again.target.id)
            val ops = again.target.useTarget { MoveEngine(app.localGateway, it, store).originalsPlan(id) }
            assertEquals(3, ops.size)
            // Nothing was deleted here: freeing is the user's tap, through the executor.
            assertTrue(items.all { File(it.path).exists() })
        }
}
