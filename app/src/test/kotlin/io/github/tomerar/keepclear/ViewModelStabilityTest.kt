// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.Manifest
import androidx.test.core.app.ApplicationProvider
import io.github.tomerar.keepclear.backup.GatewayBackupTarget
import io.github.tomerar.keepclear.backup.TargetCaps
import io.github.tomerar.keepclear.backup.saf.TargetKind
import io.github.tomerar.keepclear.core.database.MoveDao
import io.github.tomerar.keepclear.core.database.MoveFileEntity
import io.github.tomerar.keepclear.core.database.RoomTrashIndex
import io.github.tomerar.keepclear.core.safety.ActionPlan
import io.github.tomerar.keepclear.core.safety.OpKind
import io.github.tomerar.keepclear.core.safety.PlannedOp
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.core.storage.file.FileGateway
import io.github.tomerar.keepclear.engine.classify.Classifier
import io.github.tomerar.keepclear.engine.move.JobState
import io.github.tomerar.keepclear.engine.move.MoveEngine
import io.github.tomerar.keepclear.engine.move.MoveFileState
import io.github.tomerar.keepclear.engine.move.MoveJob
import io.github.tomerar.keepclear.engine.move.MovePlanner
import io.github.tomerar.keepclear.engine.move.PauseReason
import io.github.tomerar.keepclear.engine.scan.ScanItem
import io.github.tomerar.keepclear.engine.trash.TrashEntry
import io.github.tomerar.keepclear.engine.trash.TrashState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Owner review PR E: the ViewModels at work on KeepClear's real dependency graph (Robolectric, in-memory database,
 * real files in a temporary folder): nothing reads files on the main thread (E1), free / check survive leaving the
 * screen and Home refreshes afterwards (E3), Undo restores only its own items (E13), one job per double tap (E8),
 * a cancelled scan stays cancelled (E13), progress reloads stay bounded (E7) and a 10,000-entry trash (E5, E14).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ViewModelStabilityTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var t: TestApp
    private val app get() = t.app

    private fun setUp(): TestApp {
        t = TestApp(tmp.newFolder("phone"))
        // Test mode is on by default in test builds (SAFETY-9); these tests check real file changes.
        app.testMode.turnOff()
        return t
    }

    @After
    fun tearDown() {
        if (::t.isInitialized) t.close()
    }

    private fun path(f: File) = f.absolutePath

    private suspend fun stat(p: String) = app.localGateway.stat(KPath.Local(p))!!

    /** Moves [paths] to KeepClear's trash through the executor, as an earlier clean would have. */
    private fun trashNow(vararg paths: String, withSha: Boolean = false) = runBlocking {
        val ops = paths.map { p ->
            val s = stat(p)
            PlannedOp(
                s.path,
                p,
                OpKind.TRASH,
                false,
                s.size,
                s.mtimeMillis,
                s.identity,
                expectedSha256 = if (withSha) {
                    java.security.MessageDigest.getInstance(
                        "SHA-256",
                    ).digest(File(p).readBytes()).joinToString("") {
                        "%02x".format(it)
                    }
                } else {
                    null
                },
            )
        }
        val plan = ActionPlan(
            UUID.randomUUID().toString(),
            ops,
            "clean",
        ).confirm(userConfirmed(paths.toList()), app.sessionId)
        app.executor().run(plan)
    }

    private fun classified(p: String) = runBlocking {
        val s = stat(p)
        Classifier().classify(
            ScanItem(s.path, p, s.size, s.mtimeMillis, identity = s.identity),
            now = System.currentTimeMillis(),
        )
    }

    @Test
    fun `restoring checks the files' content off the main thread (E1)`() {
        setUp()
        val a = path(t.file("Pictures/Old/a.jpg"))
        trashNow(a, withSha = true)
        val entries = runBlocking { app.trashManager.entries() }
        assertEquals(1, entries.size)
        assertNotNull(entries.single().sha256)
        val vm = t.viewModel(HomeViewModel::class.java)
        val readsBefore = t.opens.get()
        vm.restore(entries, userConfirmed(listOf(a)))
        TestApp.awaitMain("restored") { File(a).exists() && !vm.state.value.busy }
        // The content was read (changed-check and the trash's own check), never on the main thread.
        assertTrue(t.opens.get() > readsBefore)
        assertEquals(0, t.mainThreadReads.get())
    }

    @Test
    fun `clean then refresh, and Undo restores only what that clean trashed (E13)`() {
        setUp()
        val p = path(t.file("Pictures/Old/same-name.jpg", ByteArray(500) { 1 }))
        trashNow(p) // an earlier clean of a file at the same place
        val earlier = runBlocking { app.trashManager.entries() }.single()
        t.file("Pictures/Old/same-name.jpg", ByteArray(700) { 2 }) // a new file there now
        val vm = t.viewModel(HomeViewModel::class.java)
        vm.clean(listOf(classified(p)), userConfirmed(listOf(p)))
        TestApp.awaitMain("cleaned and reloaded") {
            vm.state.value.cleanResult != null && !vm.state.value.busy && vm.state.value.trash.entries.size == 2
        }
        val result = vm.state.value.cleanResult!!
        assertEquals(1, result.done.files)
        assertEquals(1, result.trashedIds.size)
        assertFalse(earlier.id in result.trashedIds)
        assertTrue(vm.state.value.totalBytes >= 0) // refreshed (free space measured)
        assertEquals(0, t.mainThreadReads.get())

        vm.undo(result, userConfirmed(result.trashedPaths))
        TestApp.awaitMain("undone") { File(p).exists() && !vm.state.value.busy && vm.state.value.cleanResult == null }
        TestApp.awaitMain("trash reloaded") { vm.state.value.trash.entries.size == 1 }
        // The new file is back; the older one is still in the trash, and nothing was restored next to it.
        assertEquals(700L, File(p).length())
        assertEquals(listOf(earlier.id), runBlocking { app.trashManager.entries() }.map { it.id })
        assertFalse(File(p).parentFile!!.list()!!.any { "restored" in it })
    }

    // ------------------------------------------------------------------------------- MOVE: free and check (E3)

    private val ref = "davs://drive.test/remote.php/dav/files/u"

    private fun backedUp(confirmed: Boolean, n: Int = 3): Pair<String, List<String>> = runBlocking {
        val sources = (1..n).map { i ->
            path(t.file("DCIM/Camera/IMG_$i.jpg", ByteArray(2_000 + i) { (it * i).toByte() }))
        }
        val drive = tmp.newFolder("drive")
        val target = GatewayBackupTarget(
            FileGateway(),
            KPath.Local(drive.absolutePath),
            "drive-1",
            "Drive",
            TargetCaps(canReadBack = true, readsFromServer = true),
        )
        app.targetOpener = { _, _ -> OpenedTarget.Ok(target, TargetKind.REMOVABLE) }
        val id = "job-" + UUID.randomUUID()
        val items = sources.map { p ->
            stat(p).let { ScanItem(KPath.Local(p), p, it.size, it.mtimeMillis, identity = it.identity) }
        }
        val store = app.moveController.store
        store.putJob(MoveJob(id, "drive-1", "Drive", MovePlanner.rootRel("Phone"), 1, targetRef = ref))
        store.putFiles(MovePlanner.plan(id, items, "Phone", "Camera", target.caps))
        val end = MoveEngine(app.localGateway, target, store, recentMillis = 0).run(id)
        assertEquals(JobState.DONE, end.state)
        if (confirmed) store.files(id).forEach { store.putFile(it.copy(confirmedAt = 1)) }
        t.mainThreadReads.set(0) // the set-up itself copied on this thread
        id to sources
    }

    @Test
    fun `free up space keeps running after leaving the screen, and Home reloads the trash (E3)`() {
        setUp()
        // Owner decision P1: with the keep-in-trash setting the originals go to the trash, which Home reloads.
        app.freeSettings.setKeepInTrash(true)
        val (id, sources) = backedUp(confirmed = true)
        val home = t.viewModel(HomeViewModel::class.java)
        val screen = t.newStore()
        val move = t.viewModel(MoveViewModel::class.java, screen)
        move.freeOriginals(id, userConfirmed(sources))
        // The user leaves KeepClear right away; the screen's ViewModel is gone.
        screen.clear()
        TestApp.awaitMain("freed") {
            app.moveController.task.value == null && app.moveController.results.value.freeResult != null
        }
        assertEquals(3, app.moveController.results.value.freeResult!!.done)
        assertTrue(sources.none { File(it).exists() })
        // Home heard that a task ended: trash reloaded and free space measured again.
        TestApp.awaitMain("home reloaded the trash") { home.state.value.trash.entries.size == 3 }
        assertEquals(0, t.mainThreadReads.get())
        // A new screen shows the result.
        val again = t.viewModel(MoveViewModel::class.java, t.newStore())
        TestApp.awaitMain("result shown") { again.state.value.freeResult?.done == 3 }
    }

    @Test
    fun `free up space after the second check deletes the originals for good, the copies stay (P1)`() {
        setUp()
        val (id, sources) = backedUp(confirmed = true, n = 5)
        val move = t.viewModel(MoveViewModel::class.java)
        move.freeOriginals(id, userConfirmed(sources.take(4)))
        TestApp.awaitMain("freed") {
            app.moveController.task.value == null && app.moveController.results.value.freeResult != null
        }
        val result = app.moveController.results.value.freeResult!!
        assertEquals(4, result.done)
        assertTrue(result.forGood)
        assertFalse(result.simulated)
        assertEquals("Drive", result.targetLabel)
        // Only what the confirmation named; nothing in the trash; every copy still on the drive.
        assertTrue(sources.take(4).none { File(it).exists() })
        assertTrue(File(sources[4]).exists())
        assertEquals(0, runBlocking { app.trashManager.entries() }.size)
        val files = runBlocking { app.moveController.store.files(id) }
        assertTrue(files.all { File(tmp.root, "drive/${it.relTarget}").exists() })
        assertEquals(0, t.mainThreadReads.get())
    }

    @Test
    fun `free up space in test mode deletes nothing and says what it would have freed (P1, SAFETY-9)`() {
        setUp()
        val (id, sources) = backedUp(confirmed = true)
        app.testMode.turnOn()
        val move = t.viewModel(MoveViewModel::class.java)
        move.freeOriginals(id, userConfirmed(sources))
        TestApp.awaitMain("simulated") {
            app.moveController.task.value == null && app.moveController.results.value.freeResult != null
        }
        val result = app.moveController.results.value.freeResult!!
        assertTrue(result.simulated)
        assertEquals(3, result.done)
        assertTrue(sources.all { File(it).exists() })
        assertEquals(0, runBlocking { app.trashManager.entries() }.size)
    }

    @Test
    fun `free up space without the second check deletes nothing (P1)`() {
        setUp()
        val (id, sources) = backedUp(confirmed = false)
        val move = t.viewModel(MoveViewModel::class.java)
        move.freeOriginals(id, userConfirmed(sources))
        TestApp.awaitMain("done") {
            app.moveController.task.value == null && app.moveController.results.value.freeResult != null
        }
        val result = app.moveController.results.value.freeResult!!
        assertEquals(0, result.done)
        assertEquals(3, result.skipped)
        assertTrue(sources.all { File(it).exists() })
    }

    @Test
    fun `a backup paused for the battery goes on when the app opens and the phone charges (P3)`() {
        setUp()
        val (id, _) = backedUp(confirmed = false)
        runBlocking {
            val store = app.moveController.store
            store.putJob(store.job(id)!!.copy(state = JobState.PAUSED, pauseReason = PauseReason.BATTERY_LOW))
        }
        app.batterySource = { io.github.tomerar.keepclear.engine.move.BatteryLevel(8, charging = false) }
        val started = java.util.concurrent.CopyOnWriteArrayList<String>()
        app.moveController.startJobService = { _, job -> started += job }
        // Opening the Backup screen while the battery is still low and not charging: it keeps waiting.
        t.viewModel(MoveViewModel::class.java)
        TestApp.settle(500)
        assertEquals(emptyList<String>(), started.toList())
        // The charger goes in; the next time the screen opens, the backup goes on.
        app.batterySource = { io.github.tomerar.keepclear.engine.move.BatteryLevel(8, charging = true) }
        t.viewModel(MoveViewModel::class.java, t.newStore())
        TestApp.awaitMain("started") { started.isNotEmpty() }
        assertEquals(listOf(id), started.toList())
    }

    @Test
    fun `check backup keeps running after leaving the screen (E3)`() {
        setUp()
        val (id, _) = backedUp(confirmed = false)
        val screen = t.newStore()
        val move = t.viewModel(MoveViewModel::class.java, screen)
        move.checkBackup(id)
        screen.clear()
        TestApp.awaitMain("checked") {
            app.moveController.task.value == null && app.moveController.results.value.check is BackupCheck.Done
        }
        val done = app.moveController.results.value.check as BackupCheck.Done
        assertEquals(3, done.result.ok)
        assertTrue(runBlocking { app.moveController.store.files(id) }.all { it.confirmedAt != null })
        assertEquals(0, t.mainThreadReads.get())
    }

    @Test
    fun `a double tap on Start copying creates one job (E8)`() {
        setUp()
        val sources = (1..2).map { path(t.file("DCIM/Camera/S_$it.jpg")) }
        val drive = tmp.newFolder("drive2")
        val target = GatewayBackupTarget(
            FileGateway(),
            KPath.Local(drive.absolutePath),
            "drive-2",
            "Drive",
            TargetCaps(canReadBack = true),
        )
        val move = t.viewModel(MoveViewModel::class.java)
        runBlocking {
            move.opened(OpenedTarget.Ok(target, TargetKind.REMOVABLE), "Drive", ref, sources.map { classified(it) })
        }
        assertNotNull(move.state.value.picked)
        move.start()
        move.start()
        TestApp.awaitMain("job saved") { runBlocking { app.moveController.store.jobs() }.isNotEmpty() }
        TestApp.settle(1_000)
        assertEquals(1, runBlocking { app.moveController.store.jobs() }.size)
    }

    // ------------------------------------------------------------------------------------------- Home (E13)

    @Test
    fun `a double tap opens a screen once, and Back ends the group's selection (E13, E4)`() {
        setUp()
        val vm = t.viewModel(HomeViewModel::class.java)
        val group = Screen.Group("screenshots", io.github.tomerar.keepclear.core.model.Category.REVIEW)
        vm.open(group)
        vm.open(group)
        assertEquals(listOf(Screen.Home, group), vm.state.value.screens)
        val key = GroupKey.of("screenshots", io.github.tomerar.keepclear.core.model.Category.REVIEW)
        vm.select(key, (1..10_000).map { "/storage/emulated/0/Pictures/Screenshots/$it.png" }.toSet())
        vm.open(Screen.Preview("/storage/emulated/0/Pictures/Screenshots/1.png", "screenshots", group.category))
        vm.back() // from the picture: the selection stays
        assertEquals(10_000, vm.state.value.selections[key]?.size)
        vm.back() // from the group: it ends
        assertEquals(null, vm.state.value.selections[key])
    }

    @Test
    fun `a cancelled scan stays cancelled when coming back to KeepClear (E13)`() {
        setUp()
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).grantPermissions(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO,
        )
        val vm = t.viewModel(HomeViewModel::class.java)
        vm.refresh()
        TestApp.awaitMain("scanned") { vm.state.value.scan !is ScanState.Idle }
        vm.cancelScan()
        assertEquals(ScanState.Idle, vm.state.value.scan)
        vm.refresh() // back from another app
        TestApp.settle(1_500)
        assertEquals(ScanState.Idle, vm.state.value.scan)
        vm.scan() // the user starts it again
        TestApp.awaitMain("scanning again") { vm.state.value.scan !is ScanState.Idle }
    }

    // --------------------------------------------------------------------------------------- scale (E5, E7)

    @Test
    fun `a 10,000-entry trash loads in one go and coming back doesn't check every file again (E5, E14)`() {
        setUp()
        val index = RoomTrashIndex(app.database.trashDao())
        val volume = t.root.absolutePath
        runBlocking {
            for (i in 0 until 10_000) {
                val id = UUID.randomUUID().toString()
                val f = File(t.root, ".KeepClearTrash/20261101/$id/IMG_$i.jpg").apply {
                    parentFile!!.mkdirs()
                    writeBytes(ByteArray(10))
                }
                index.put(
                    TrashEntry(
                        id = id,
                        originalPath = "$volume/DCIM/Camera/IMG_$i.jpg",
                        trashedPath = f.absolutePath,
                        volumeRoot = volume,
                        size = 10,
                        mtimeMillis = f.lastModified(),
                        reason = "TRASH",
                        deletedAt = i.toLong(),
                        expiresAt = Long.MAX_VALUE,
                        deletedAtElapsed = 0,
                        bootId = "b",
                        state = TrashState.ACTIVE,
                        deletedAtUptime = 0,
                    ),
                )
            }
        }
        val vm = t.viewModel(HomeViewModel::class.java)
        val started = System.currentTimeMillis()
        vm.loadTrash()
        TestApp.awaitMain("loaded", timeoutMillis = 120_000) { vm.state.value.trash.entries.size == 10_000 }
        // Generous bound for slow CI machines; on a laptop this takes a few seconds (recovery checks each file once).
        assertTrue("took ${System.currentTimeMillis() - started} ms", System.currentTimeMillis() - started < 90_000)
        assertEquals(9_999L, vm.state.value.trash.entries.first().deletedAt) // newest first
        // Coming back to KeepClear (three resumes in a row; the newest load wins). One entry is new since.
        val statsBefore = t.stats.get()
        runBlocking {
            val f = File(t.root, ".KeepClearTrash/20261102/new/new.jpg").apply {
                parentFile!!.mkdirs()
                writeBytes(ByteArray(10))
            }
            index.put(
                TrashEntry(
                    id = UUID.randomUUID().toString(),
                    originalPath = "$volume/DCIM/new.jpg",
                    trashedPath = f.absolutePath,
                    volumeRoot = volume,
                    size = 10,
                    mtimeMillis = f.lastModified(),
                    reason = "TRASH",
                    deletedAt = 20_000,
                    expiresAt = Long.MAX_VALUE,
                    deletedAtElapsed = 0,
                    bootId = "b",
                    state = TrashState.ACTIVE,
                    deletedAtUptime = 0,
                ),
            )
        }
        repeat(3) { vm.loadTrash() }
        TestApp.awaitMain("reloaded") { vm.state.value.trash.entries.size == 10_001 }
        TestApp.settle(1_000)
        // Only the new entry was checked; the 10,000 known ones weren't checked again.
        val checked = t.stats.get() - statsBefore
        assertTrue("files checked: $checked", checked < 20)
        assertEquals(0, t.mainThreadReads.get())
    }

    @Test
    fun `copy progress reloads only the running job, at most twice a second (E7)`() {
        val calls = ConcurrentHashMap<String, AtomicInteger>()
        setUp()
        app.moveController.daoWrapper = { dao ->
            object : MoveDao by dao {
                override suspend fun files(jobId: String): List<MoveFileEntity> {
                    calls.getOrPut(jobId) { AtomicInteger() }.incrementAndGet()
                    return dao.files(jobId)
                }
            }
        }
        val store = app.moveController.store
        runBlocking {
            for ((id, n, state) in listOf(
                Triple("done", 1_000, JobState.DONE),
                Triple("running", 10, JobState.RUNNING),
            )) {
                store.putJob(MoveJob(id, "t", "Drive", "KeepClear/P", 1, state, targetRef = ref))
                val items = (0 until n).map {
                    ScanItem(
                        KPath.Local("/storage/emulated/0/DCIM/$id-$it.jpg"),
                        "/storage/emulated/0/DCIM/$id-$it.jpg",
                        10,
                        1,
                    )
                }
                store.putFiles(MovePlanner.plan(id, items, "P", "Camera", TargetCaps(canReadBack = true)))
            }
        }
        app.moveController.setRunning("running")
        val move = t.viewModel(MoveViewModel::class.java)
        TestApp.awaitMain("loaded") { move.state.value.jobs.size == 2 }
        calls.clear()
        // 100 files copied in one second (the main thread's clock moves with each step).
        repeat(100) {
            app.moveController.ticks.value++
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(10))
            Thread.sleep(10)
        }
        repeat(60) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(10))
            Thread.sleep(5)
        }
        assertEquals(0, calls["done"]?.get() ?: 0)
        val running = calls["running"]?.get() ?: 0
        assertTrue("running job read $running times", running in 1..5)
    }
}
