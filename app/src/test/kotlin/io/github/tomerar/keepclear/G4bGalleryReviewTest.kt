// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import io.github.tomerar.keepclear.core.gallery.GalleryExclusions
import io.github.tomerar.keepclear.core.gallery.GalleryProgress
import io.github.tomerar.keepclear.core.gallery.GalleryRunState
import io.github.tomerar.keepclear.core.model.Category
import io.github.tomerar.keepclear.core.safety.Confirmation
import io.github.tomerar.keepclear.core.safety.OpKind
import io.github.tomerar.keepclear.core.safety.ProtectedPaths
import io.github.tomerar.keepclear.core.safety.SafetyGuard
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.engine.classify.ClassifiedItem
import io.github.tomerar.keepclear.engine.gallery.GalleryDiagnostics
import io.github.tomerar.keepclear.engine.gallery.GalleryGroup
import io.github.tomerar.keepclear.engine.gallery.GalleryPolicy
import io.github.tomerar.keepclear.engine.gallery.GalleryResult
import io.github.tomerar.keepclear.engine.gallery.GalleryRun
import io.github.tomerar.keepclear.engine.gallery.GalleryScope
import io.github.tomerar.keepclear.engine.gallery.GroupEvidence
import io.github.tomerar.keepclear.engine.gallery.GroupVersion
import io.github.tomerar.keepclear.engine.gallery.KeeperAdvice
import io.github.tomerar.keepclear.engine.gallery.SourceKey
import io.github.tomerar.keepclear.engine.gallery.SourceVersion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * G4b (GAL-4/6/7/8/9/21, AC-G3/5/6, E-G07/08/09): the bridge from a similar-photos group to the existing review-trash
 * path. A real coordinator over [GalleryFakes]; the app's real executor, media handler and test mode over a stand-in
 * media provider ([FakeMediaProvider]) for the clean, Android's dialog and Undo.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class G4bGalleryReviewTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val fakes = GalleryFakes()
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val coordinator by lazy { fakes.coordinator() }
    private val controller = GalleryController(
        coordinator = { coordinator },
        liveScope = { fakes.scope },
        exclusions = { { GalleryExclusions(ProtectedPaths()) } },
        scope = work,
    )
    private var app: TestApp? = null

    @After
    fun end() {
        work.cancel()
        app?.close()
    }

    private fun until(predicate: (GalleryUi) -> Boolean): GalleryUi {
        val end = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < end) {
            controller.state.value.takeIf(predicate)?.let { return it }
            Thread.sleep(5)
        }
        throw AssertionError("timed out; last state: ${controller.state.value}")
    }

    /** A memory with a saved look-alike (one group), two saved look-alikes (another), and three unrelated photos. */
    private fun library(): List<ClassifiedItem> {
        val a = GalleryFakes.photo(1, Category.MOVE)
        val b = GalleryFakes.photo(2, Category.REVIEW)
        val c = GalleryFakes.photo(3)
        val d = GalleryFakes.photo(4)
        fakes.family[a.item.path] = 0
        fakes.family[b.item.path] = 0
        fakes.family[c.item.path] = 1
        fakes.family[d.item.path] = 1
        return listOf(a, b, c, d) + (5..7).map { GalleryFakes.photo(it) }
    }

    private fun compared(items: List<ClassifiedItem> = library()): GalleryUi {
        controller.start(GalleryFakes.summary(items), scanComplete = true)
        return until { it.phase == GalleryPhase.DONE }
    }

    private fun GalleryUi.groupWith(id: Int) =
        groups.single { g -> g.members.any { it.item.item.path.endsWith("IMG_$id.jpg") } }

    private fun path(id: Int) = GalleryFakes.photo(id).item.path.let {
        if (id == 1) GalleryFakes.photo(1, Category.MOVE).item.path else it
    }

    // ------------------------------------------------------------------ choices (GAL-4, GAL-6, GAL-8)

    @Test
    fun `nothing is chosen at first, only REVIEW photos can be chosen, and never the last of a group`() {
        val done = compared()
        assertTrue("nothing preselected", done.groups.all { it.chosen.isEmpty() })
        val memory = done.groupWith(1)
        assertEquals("the memory is never offered, the saved copy is", setOf(path(2)), memory.choosable)
        controller.toggleChoice(memory.id, path(1))
        assertTrue("a memory can't be chosen", controller.state.value.groupWith(1).chosen.isEmpty())
        controller.toggleChoice(memory.id, path(2))
        assertEquals(setOf(path(2)), controller.state.value.groupWith(1).chosen)

        val pair = done.groupWith(3)
        assertEquals(setOf(path(3), path(4)), pair.choosable)
        controller.toggleChoice(pair.id, path(3))
        val after = controller.state.value.groupWith(3)
        assertEquals("the last photo of the pair is not offered", emptySet<String>(), after.choosable)
        controller.toggleChoice(pair.id, path(4))
        assertEquals("choosing every member is impossible", setOf(path(3)), controller.state.value.groupWith(3).chosen)
        controller.toggleChoice(pair.id, path(3))
        assertTrue("a second tap un-chooses", controller.state.value.groupWith(3).chosen.isEmpty())
    }

    @Test
    fun `choices live in this session only and are bound to the group version and the photos as compared`() {
        val done = compared()
        val pair = done.groupWith(3)
        controller.toggleChoice(pair.id, path(3))
        // A new scan says photo 3 changed (another size): it leaves the group and its choice is dropped (E-G07).
        val changed = library().map {
            if (it.item.path == path(3)) it.copy(item = it.item.copy(size = it.item.size + 1)) else it
        }
        controller.sync(GalleryFakes.summary(changed))
        val synced = until { s -> s.groups.none { g -> g.members.any { it.item.item.path == path(3) } } }
        assertTrue(synced.groups.all { it.chosen.isEmpty() })

        // A new comparison starts with nothing chosen (GAL-9: nothing carried onto a new version).
        controller.sync(GalleryFakes.summary(library()))
        val again = compared()
        controller.toggleChoice(again.groupWith(3).id, path(4))
        compared()
        assertTrue(controller.state.value.groups.all { it.chosen.isEmpty() })

        // Process death: a new controller (a new ViewModel) knows no choice and shows no confirmation.
        val fresh =
            GalleryController({
                fakes.coordinator()
            }, { fakes.scope }, { { GalleryExclusions(ProtectedPaths()) } }, work)
        assertTrue(fresh.state.value.groups.isEmpty())
        assertNull(fresh.disposal(again.groupWith(3).id))
    }

    @Test
    fun `GAL-21 - choosing changes no lane total`() {
        val items = library()
        val summary = GalleryFakes.summary(items)
        val before = summary.total(Category.REVIEW)
        controller.start(summary, scanComplete = true)
        val done = until { it.phase == GalleryPhase.DONE }
        controller.toggleChoice(done.groupWith(3).id, path(3))
        assertEquals(before, summary.total(Category.REVIEW))
    }

    // ------------------------------------------------------------------ the plan (GAL-3, GAL-7, GAL-8)

    @Test
    fun `the plan moves only the chosen REVIEW photo to the trash with the group's kept photo as evidence`() {
        val done = compared()
        val memory = done.groupWith(1)
        controller.toggleChoice(memory.id, path(2))
        val disposal = controller.disposal(memory.id)!!
        val plan = GalleryReview.plan("p", disposal)!!
        val op = plan.ops.single()
        assertEquals(path(2), op.displayPath)
        assertEquals(OpKind.TRASH, op.kind)
        assertEquals("REVIEW is never SAFE, so never permanent", false, op.classifiedSafe)
        assertNull(op.duplicateGroup)
        assertTrue(op.witnesses.isEmpty())
        assertNull(op.expectedSha256)
        assertEquals("gen:5;trashed:0", op.expectedIdentity)
        val kept = op.visualGroups.single().retained.single()
        assertEquals(path(1), kept.path)
        assertEquals(KPath.Media("external_primary", 1), kept.ref)
        assertEquals("the memory is kept and re-checked by identity", "gen:5;trashed:0", kept.identity)
    }

    @Test
    fun `a memory never reaches a plan, and a photo without its compared version is never planned`() {
        val done = compared()
        val memory = done.groupWith(1)
        val all = memory.members
        val keep = GalleryKeep(memory.id, memory.version, all)
        val (memories, others) = all.partition { it.item.category == Category.MOVE }
        // The same group and evidence plan the saved copy, but never the memory (GAL-4).
        assertNotNull(GalleryReview.plan("p", GalleryDisposal(memory.id, others, listOf(keep))))
        assertNull(GalleryReview.plan("p", GalleryDisposal(memory.id, memories, listOf(keep))))
        val noVersion = others.map { it.copy(source = null) }
        assertNull(GalleryReview.plan("p", GalleryDisposal(memory.id, noVersion, listOf(keep))))
        assertNull(GalleryReview.plan("p", GalleryDisposal(memory.id, emptyList(), listOf(keep))))
    }

    /** x in g1 = {x, y} and in g2 = {x, z}; z is no longer in the scan. */
    private fun overlapping(): Pair<GalleryProgress, Map<SourceKey, ClassifiedItem>> {
        val (x, y, z) = listOf(31, 32, 33).map { GalleryFakes.photo(it) }
        val run = GalleryRun("run", "scan", 1)

        fun v(i: ClassifiedItem) = SourceVersion(
            SourceKey("external_primary", (i.item.ref as KPath.Media).id.toString()),
            i.item.size,
            i.item.mtimeMillis,
            "media:2:r1:5",
            run.id,
        )

        fun g(vararg m: ClassifiedItem) = GalleryGroup(
            GroupVersion(run, GalleryPolicy(), m.map(::v)),
            GroupEvidence.COMPLETE_LINK_VISUAL,
            KeeperAdvice.NoClearRecommendation,
        )
        val result = GalleryResult(
            run,
            listOf(g(x, y), g(x, z)),
            emptyList(),
            emptySet(),
            GalleryDiagnostics(3, 3, 0, 0, 0, 0, 0, 0, 0, 0),
        )
        val progress = GalleryProgress(GalleryRunState.COMPLETED, GalleryScope.FULL_IMAGES, run, grouping = result)
        return progress to GalleryUi.keyed(listOf(x, y))
    }

    @Test
    fun `overlapping groups - a photo that is the last available one of another group is not offered (GAL-8)`() {
        val (progress, items) = overlapping()
        val ui = GalleryUi.of(progress, items)
        val g1 = ui.groups.single()
        val x = items.values.single { it.item.path.endsWith("IMG_31.jpg") }.item.path
        val y = items.values.single { it.item.path.endsWith("IMG_32.jpg") }.item.path
        assertEquals("x is all g2 has left; only y may go", setOf(y), g1.choosable)
        // Even a plan built around the screen can't take x: g2 keeps nothing, and the choke point refuses it.
        val members = g1.members
        val disposal = GalleryDisposal(
            g1.id,
            members.filter { it.item.item.path == x },
            listOf(
                GalleryKeep(g1.id, g1.version, members),
                GalleryKeep("g2", "v2", members.filter { it.item.item.path == x }),
            ),
        )
        val plan = GalleryReview.plan("p", disposal)!!.confirm(1, "s")
        val verdict = SafetyGuard(ProtectedPaths()).check(plan, "s")
        assertTrue(verdict is SafetyGuard.Verdict.Rejected)
    }

    @Test
    fun `a choice made for another version of the group is not shown or used (GAL-9)`() {
        val (progress, items) = overlapping()
        val g1 = GalleryUi.of(progress, items).groups.single()
        val y = g1.members.single { it.item.item.path.endsWith("IMG_32.jpg") }.item.item.path
        assertEquals(
            setOf(y),
            GalleryUi.of(progress, items, chosen = mapOf(g1.id to (g1.version to setOf(y)))).groups.single().chosen,
        )
        assertTrue(
            GalleryUi.of(
                progress,
                items,
                chosen = mapOf(g1.id to ("older" to setOf(y))),
            ).groups.single().chosen.isEmpty(),
        )
    }

    // ------------------------------------------------------------------ the existing clean path (GAL-7, AC-G5)

    private fun appWithRows(testMode: Boolean): Triple<TestApp, HomeViewModel, FakeMediaProvider> {
        val test = TestApp(tmp.newFolder()).also { app = it }
        if (testMode) test.app.testMode.turnOn() else test.app.testMode.turnOff()
        val media = FakeMediaProvider.install()
        for (item in library()) {
            val ref = item.item.ref as KPath.Media
            media.rows[ref.id] =
                FakeMediaProvider.Row(item.item.path.substringAfterLast('/'), item.item.size, item.item.mtimeMillis)
        }
        return Triple(test, test.viewModel(HomeViewModel::class.java), media)
    }

    private fun chosenDisposal(): GalleryDisposal {
        val group = compared().groupWith(1)
        controller.toggleChoice(group.id, path(2))
        return controller.disposal(group.id)!!
    }

    private fun confirmation(vararg paths: String) = Confirmation.of(paths.toList(), System.currentTimeMillis())

    @Test
    fun `test mode simulates - no Android dialog, no change, nothing to undo (SAFETY-9)`() {
        val (_, vm, media) = appWithRows(testMode = true)
        val rows = media.rows.toMap()
        vm.cleanGallery(chosenDisposal(), confirmation(path(2)))
        TestApp.awaitMain("simulated result") { vm.state.value.cleanResult != null }
        val r = vm.state.value.cleanResult!!
        assertTrue(r.simulated)
        assertEquals(1, r.done.files)
        assertNull(vm.state.value.consent)
        assertTrue(r.androidTrashed.isEmpty())
        assertEquals(rows, media.rows.toMap())
    }

    @Test
    fun `the chosen photo goes to Android's trash after its dialog, and Undo puts exactly it back`() {
        val (_, vm, media) = appWithRows(testMode = false)
        vm.open(Screen.GalleryGroup("g"))
        vm.cleanGallery(chosenDisposal(), confirmation(path(2)))
        // The clean finishes (its result, the rescan) before the user answers Android's dialog.
        TestApp.awaitMain("Android's dialog") { vm.state.value.consent != null && !vm.state.value.busy }
        val consent = vm.state.value.consent!!
        assertTrue(consent.undoable)
        val handed = consent.requests.flatMap { it.ops }
        assertEquals(listOf(path(2) to OpKind.TRASH), handed.map { it.displayPath to it.kind })
        // The user agrees in Android's dialog; Android moves the row to its trash.
        vm.consentLaunched(consent.headId)
        media.rows[2] = media.rows.getValue(2).copy(trashed = true)
        vm.consentResult(approved = true)
        val r = vm.state.value.cleanResult!!
        assertTrue(r.androidTrash)
        assertEquals(1, r.trashed.files)
        assertEquals("trashed, not freed (GAL-21)", null, r.measured)
        assertEquals(listOf(path(2)), r.androidTrashed.map { it.displayPath })

        vm.undo(r, confirmation(path(2)))
        TestApp.awaitMain("Android's restore dialog") { vm.state.value.consent != null && !vm.state.value.busy }
        val restore = vm.state.value.consent!!
        assertEquals(
            listOf(path(2) to OpKind.RESTORE),
            restore.requests.flatMap { it.ops }.map {
                it.displayPath to
                    it.kind
            },
        )
        vm.consentLaunched(restore.headId)
        vm.consentResult(approved = true)
        assertEquals(1, vm.state.value.cleanResult!!.restoredFromBin.files)
    }

    @Test
    fun `a kept photo edited after the confirmation refuses the group, nothing is handed to Android (E-G09)`() {
        val (_, vm, media) = appWithRows(testMode = false)
        val disposal = chosenDisposal()
        // Another app edits the memory: same size and time, a new generation.
        media.rows[1] = media.rows.getValue(1).copy(generation = 6)
        vm.cleanGallery(disposal, confirmation(path(2)))
        TestApp.awaitMain("result") { vm.state.value.cleanResult != null }
        val r = vm.state.value.cleanResult!!
        assertEquals(1, r.groupRefused)
        assertEquals(0, r.done.files)
        assertNull(vm.state.value.consent)
    }

    @Test
    fun `the chosen photo changed since the comparison is skipped (source re-check, SAFETY-6)`() {
        val (_, vm, media) = appWithRows(testMode = false)
        val disposal = chosenDisposal()
        media.rows[2] = media.rows.getValue(2).copy(generation = 9)
        vm.cleanGallery(disposal, confirmation(path(2)))
        TestApp.awaitMain("result") { vm.state.value.cleanResult != null }
        assertEquals(1, vm.state.value.cleanResult!!.skipped)
        assertNull(vm.state.value.consent)
    }

    @Test
    fun `a confirmation that doesn't name the chosen photo changes nothing (review PR B)`() {
        val (_, vm, _) = appWithRows(testMode = false)
        vm.cleanGallery(chosenDisposal(), confirmation(path(3)))
        TestApp.awaitMain("result") { vm.state.value.cleanResult != null }
        assertEquals(0, vm.state.value.cleanResult!!.done.files)
        assertNull(vm.state.value.consent)
        assertNotNull(vm.state.value.cleanResult)
    }
}
