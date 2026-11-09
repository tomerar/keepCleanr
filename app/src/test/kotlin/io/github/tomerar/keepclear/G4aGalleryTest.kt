// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import io.github.tomerar.keepclear.core.gallery.GalleryExclusions
import io.github.tomerar.keepclear.core.model.Category
import io.github.tomerar.keepclear.core.safety.ProtectedPaths
import io.github.tomerar.keepclear.engine.classify.ClassifiedItem
import io.github.tomerar.keepclear.engine.gallery.GalleryScope
import io.github.tomerar.keepclear.engine.gallery.KeeperReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * G4a: the read-only gallery comparison wired into the app (GAL-2/4/6/10/21, AC-G1/3/5, E-G20). A real coordinator
 * over fakes ([GalleryFakes]); the app's controller maps its progress to what the screens show.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class G4aGalleryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val fakes = GalleryFakes()
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var created = 0
    private val controller = GalleryController(
        coordinator = lazyCoordinator(),
        liveScope = { fakes.scope },
        exclusions = { { GalleryExclusions(ProtectedPaths()) } },
        scope = work,
    )

    private fun lazyCoordinator(): () -> io.github.tomerar.keepclear.core.gallery.GalleryCoordinator {
        val c by lazy {
            created++
            fakes.coordinator()
        }
        return { c }
    }

    @After
    fun end() = work.cancel()

    /** The [n]th decode waits for [gate] (a stop or pause cancels the wait; the photo stays not reached). */
    private fun blockRead(n: Int, gate: CompletableDeferred<Unit>, fakes: GalleryFakes = this.fakes) {
        val reads = java.util.concurrent.atomic.AtomicInteger()
        fakes.beforeRead = { if (reads.incrementAndGet() == n) gate.await() }
    }

    /** Polls (a state equal to the last one is not emitted again, and [created] is not part of it). */
    private fun until(predicate: (GalleryUi) -> Boolean): GalleryUi {
        val end = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < end) {
            controller.state.value.takeIf(predicate)?.let { return it }
            Thread.sleep(5)
        }
        throw AssertionError("timed out; last state: ${controller.state.value}")
    }

    /** Two look-alike pairs (one with a clear keeper, one tied) and three unrelated photos. */
    private fun library(memories: Boolean = false, fakes: GalleryFakes = this.fakes): List<ClassifiedItem> {
        val a = GalleryFakes.photo(1, if (memories) Category.MOVE else Category.REVIEW)
        val b = GalleryFakes.photo(2, Category.REVIEW)
        val c = GalleryFakes.photo(3)
        val d = GalleryFakes.photo(4)
        fakes.family[a.item.path] = 0
        fakes.family[b.item.path] = 0
        fakes.pixels[a.item.path] = 4_000_000L // four times the pixels: a clear keeper
        fakes.family[c.item.path] = 1
        fakes.family[d.item.path] = 1 // equal quality: no clear recommendation
        return listOf(a, b, c, d) + (5..7).map { GalleryFakes.photo(it) }
    }

    @Test
    fun `a complete run over all photos shows groups, a reasoned keeper and an abstention, nothing chosen`() {
        val items = library()
        val summary = GalleryFakes.summary(items)
        val before = summary.total(Category.REVIEW)
        controller.start(summary, scanComplete = true)
        val done = until { it.phase == GalleryPhase.DONE }
        assertEquals(GalleryScope.FULL_IMAGES, done.scope)
        assertEquals(7, done.total)
        assertEquals(7, done.compared)
        assertEquals(0, done.skipped)
        assertTrue("only a whole, unskipped run may say all photos", done.complete)
        assertEquals(2, done.groups.size)
        val clear = done.groups.single { it.members.any { m -> m.item.item.path.endsWith("IMG_1.jpg") } }
        assertEquals(listOf(KeeperReason.HIGHER_USABLE_RESOLUTION), clear.reasons)
        assertEquals(items[0], clear.suggested!!.item)
        assertEquals(1, clear.members.count { it.suggested })
        val tied = done.groups.single { it != clear }
        assertTrue("abstention: no reasons", tied.reasons.isEmpty())
        assertNull("abstention: no marker", tied.suggested)
        // GAL-21: virtual groups change no lane total; the scan's summary is only read.
        assertEquals(before, summary.total(Category.REVIEW))
    }

    @Test
    fun `selected photos or a skipped photo never read as all photos`() {
        fakes.scope.scope = GalleryScope.SELECTED
        controller.start(GalleryFakes.summary(library()), scanComplete = true)
        val selected = until { it.phase == GalleryPhase.DONE }
        assertEquals(GalleryScope.SELECTED, selected.scope)
        assertFalse(selected.complete)

        fakes.scope.scope = GalleryScope.FULL_IMAGES
        val items = library()
        fakes.unsupported += items.last().item.path
        controller.start(GalleryFakes.summary(items), scanComplete = true)
        val skipped = until { it.phase == GalleryPhase.DONE && it.scope == GalleryScope.FULL_IMAGES }
        assertEquals(1, skipped.skipped)
        assertEquals(6, skipped.compared)
        assertFalse("an unsupported photo is not compared, so this is not every photo", skipped.complete)

        fakes.unsupported.clear()
        controller.start(GalleryFakes.summary(library()), scanComplete = false)
        assertFalse("an incomplete scan", until { it.phase == GalleryPhase.DONE && it.skipped == 0 }.complete)
    }

    @Test
    fun `no access reads nothing and shows no groups`() {
        fakes.scope.scope = GalleryScope.NONE
        controller.checkScope()
        assertEquals(GalleryPhase.NO_ACCESS, until { it.scope == GalleryScope.NONE }.phase)
        controller.start(GalleryFakes.summary(library()), scanComplete = true)
        val ui = until { it.phase == GalleryPhase.NO_ACCESS && created == 1 }
        assertTrue(ui.groups.isEmpty())
        assertEquals(0, fakes.decoded.get())
    }

    @Test
    fun `nothing is created before the user starts, and no scan means nothing to compare`() {
        controller.checkScope()
        until { it.scope == GalleryScope.FULL_IMAGES }
        assertEquals("checking the scope creates no coordinator", 0, created)
        controller.start(null, scanComplete = false)
        assertEquals(GalleryPhase.NO_SCAN, until { it.phase == GalleryPhase.NO_SCAN }.phase)
        assertEquals(0, created)
    }

    @Test
    fun `stop leaves an honest partial result and Continue finishes the same run`() {
        val gate = CompletableDeferred<Unit>()
        val items = library()
        blockRead(4, gate)
        controller.start(GalleryFakes.summary(items), scanComplete = true)
        until { it.running && it.done == 3 }
        controller.stop()
        val stopped = until { it.phase == GalleryPhase.STOPPED }
        assertFalse(stopped.complete)
        assertEquals(3, stopped.compared)
        assertEquals(4, stopped.details.notReached)
        assertTrue(stopped.resumable)
        fakes.beforeRead = {}
        controller.continueRun()
        val done = until { it.phase == GalleryPhase.DONE }
        assertEquals(7, done.compared)
        assertEquals(2, done.groups.size)
    }

    @Test
    fun `keep this time hides a group for this session only, and the next comparison offers it again`() {
        val items = library()
        controller.start(GalleryFakes.summary(items), scanComplete = true)
        val done = until { it.phase == GalleryPhase.DONE }
        val id = done.groups.first().id
        controller.keepThisTime(id)
        val after = until { it.groups.size == 1 }
        assertTrue(after.groups.none { it.id == id })
        controller.start(GalleryFakes.summary(items), scanComplete = true)
        assertTrue(until { it.phase == GalleryPhase.DONE && it.groups.size == 2 }.groups.any { it.id == id })
    }

    @Test
    fun `don't suggest this again takes the photo out of its group at once`() {
        val items = library()
        controller.start(GalleryFakes.summary(items), scanComplete = true)
        until { it.phase == GalleryPhase.DONE }
        controller.dontOffer(items[0].item.path)
        // A pair with one photo left is no group.
        val after = until { it.groups.size == 1 }
        assertTrue(after.groups.single().members.none { it.item == items[0] })
    }

    @Test
    fun `memories are flagged for backup and keep their lane`() {
        val items = library(memories = true)
        controller.start(GalleryFakes.summary(items), scanComplete = true)
        val done = until { it.phase == GalleryPhase.DONE }
        val mixed = done.groups.single { it.hasMemories }
        assertEquals(Category.MOVE, mixed.members.single { it.item == items[0] }.item.category)
        assertEquals(Category.REVIEW, mixed.members.single { it.item == items[1] }.item.category)
        assertFalse(done.groups.single { it != mixed }.hasMemories)
    }

    @Test
    fun `an access change after the run hides the groups (AC-G1, E-G02)`() {
        controller.start(GalleryFakes.summary(library()), scanComplete = true)
        until { it.phase == GalleryPhase.DONE && it.groups.isNotEmpty() }
        fakes.scope.scope = GalleryScope.SELECTED
        controller.checkScope()
        val changed = until { it.phase == GalleryPhase.ACCESS_CHANGED }
        assertTrue(changed.groups.isEmpty())
        assertEquals(GalleryScope.SELECTED, changed.scope)
    }

    // ------------------------------------------------------------------ E-G20: file actions take priority

    private fun runningOnApp(test: TestApp): CompletableDeferred<Unit> {
        // Its own fakes: a cache shared with an earlier run would answer without reads.
        val own = GalleryFakes()
        val gate = CompletableDeferred<Unit>()
        val items = library(fakes = own)
        blockRead(3, gate, own)
        test.app.galleryFactory = { own.coordinator() }
        val c =
            GalleryController({ test.app.gallery }, { own.scope }, { { GalleryExclusions(ProtectedPaths()) } }, work)
        c.start(GalleryFakes.summary(items), scanComplete = true)
        runBlocking { withTimeout(10_000) { c.state.first { it.running && it.done >= 2 } } }
        return gate
    }

    @Test
    fun `the clean and trash executor pauses a running comparison`() {
        val test = TestApp(tmp.newFolder())
        runningOnApp(test)
        runBlocking { test.app.executor() }
        val paused = runBlocking {
            withTimeout(10_000) { test.app.gallery.progress.first { !it.state.running() } }
        }
        assertEquals(io.github.tomerar.keepclear.core.gallery.GalleryRunState.PAUSED_FOR_FILE_ACTION, paused.state)
        assertTrue(paused.partial)
    }

    @Test
    fun `a backup start and a free, check or restore task pause a running comparison`() {
        for (action in listOf("backup", "task")) {
            val test = TestApp(tmp.newFolder())
            test.app.moveController.startJobService = { _, _ -> }
            runningOnApp(test)
            when (action) {
                "backup" -> test.app.moveController.start(test.app, "job")
                else -> test.app.moveController.runTask(test.app, TaskKind.CHECK) { }
            }
            val paused = runBlocking {
                withTimeout(10_000) { test.app.gallery.progress.first { !it.state.running() } }
            }
            assertEquals(
                action,
                io.github.tomerar.keepclear.core.gallery.GalleryRunState.PAUSED_FOR_FILE_ACTION,
                paused.state,
            )
        }
    }

    @Test
    fun `the clean flow pauses the comparison before it plans, and the hook never creates the coordinator`() {
        val idle = TestApp(tmp.newFolder())
        var made = 0
        idle.app.galleryFactory = {
            made++
            fakes.coordinator()
        }
        runBlocking { idle.app.executor() }
        idle.app.pauseGalleryForFileAction()
        assertEquals(0, made)

        val test = TestApp(tmp.newFolder())
        runningOnApp(test)
        val home = test.viewModel(HomeViewModel::class.java)
        home.clean(emptyList(), io.github.tomerar.keepclear.core.safety.Confirmation.of(emptyList(), 1L))
        val paused = runBlocking {
            withTimeout(10_000) { test.app.gallery.progress.first { !it.state.running() } }
        }
        assertEquals(io.github.tomerar.keepclear.core.gallery.GalleryRunState.PAUSED_FOR_FILE_ACTION, paused.state)
    }

    @Test
    fun `the ViewModel creates nothing until a run starts`() {
        val test = TestApp(tmp.newFolder())
        var made = 0
        test.app.galleryFactory = {
            made++
            fakes.coordinator()
        }
        test.viewModel(GalleryViewModel::class.java)
        assertEquals(0, made)
    }

    private fun io.github.tomerar.keepclear.core.gallery.GalleryRunState.running() =
        this == io.github.tomerar.keepclear.core.gallery.GalleryRunState.RUNNING ||
            this == io.github.tomerar.keepclear.core.gallery.GalleryRunState.IDLE
}
