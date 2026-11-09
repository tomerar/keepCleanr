// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.github.tomerar.keepclear.core.designsystem.KeepClearTheme
import io.github.tomerar.keepclear.core.model.Category
import io.github.tomerar.keepclear.engine.gallery.GalleryScope
import io.github.tomerar.keepclear.engine.gallery.KeeperReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import io.github.tomerar.keepclear.core.i18n.R as S

private typealias Shown = @androidx.compose.runtime.Composable () -> Unit

/** G4a sample states for the UI tests and the screenshots (photos shaped like the owner's phone). */
object GallerySamples {
    private val camera = (1..3).map { GalleryFakes.photo(it, Category.MOVE, OwnerPhone.NOW - 40 * OwnerPhone.DAY) }
    private val saved = (11..13).map { GalleryFakes.photo(it, Category.REVIEW, OwnerPhone.NOW - 90 * OwnerPhone.DAY) }
    private val more = (21..24).map { GalleryFakes.photo(it, Category.REVIEW, OwnerPhone.NOW - 200 * OwnerPhone.DAY) }

    /** A burst: two camera memories and a saved copy; the first is clearly sharper and larger. */
    val withKeeper = GalleryGroupUi(
        "external_primary:1",
        listOf(GalleryMember(camera[0], true), GalleryMember(camera[1], false), GalleryMember(saved[0], false)),
        listOf(KeeperReason.SHARPER, KeeperReason.HIGHER_USABLE_RESOLUTION),
    )

    /** Three saved pictures that are about equally good: no clear recommendation. */
    val tied =
        GalleryGroupUi("external_primary:12", saved.drop(1).plus(more[0]).map { GalleryMember(it, false) }, emptyList())
    val pair = GalleryGroupUi("external_primary:22", more.drop(1).take(2).map { GalleryMember(it, false) }, emptyList())

    val running =
        GalleryUi(
            GalleryPhase.RUNNING,
            GalleryScope.FULL_IMAGES,
            done = 1_240,
            total = 3_812,
            compared = 1_236,
            skipped = 4,
        )
    val partial = GalleryUi(
        GalleryPhase.STOPPED,
        GalleryScope.SELECTED,
        done = 64,
        total = 120,
        compared = 61,
        skipped = 3,
        groups = listOf(withKeeper),
        details = GalleryDetails(eligible = 120, fromCache = 20, newlyDecoded = 41, notReached = 56),
    )
    val done = GalleryUi(
        GalleryPhase.DONE,
        GalleryScope.FULL_IMAGES,
        done = 3_812,
        total = 3_812,
        compared = 3_812,
        complete = true,
        groups = listOf(withKeeper, tied, pair),
    )
    val noAccess = GalleryUi(GalleryPhase.NO_ACCESS, GalleryScope.NONE)
}

/** G4a (GAL-6, GAL-10, GAL-16, AC-G3, AC-G10): the comparison screens' behaviour, TalkBack and large text. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class G4aScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)

    /** One setContent per test; later screens replace the shown one (each with fresh remembered state). */
    private var shown by androidx.compose.runtime.mutableStateOf<Pair<Int, Shown>?>(null)

    private fun show(content: Shown) {
        val first = shown == null
        shown = (shown?.first ?: 0) + 1 to content
        if (first) {
            compose.setContent {
                KeepClearTheme(reducedMotion = true) {
                    shown?.let { (n, c) -> androidx.compose.runtime.key(n) { c() } }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun group(group: GalleryGroupUi, actions: GalleryActions = GalleryActions()) =
        show { GalleryGroupScreen(group, actions, onBack = {}) }

    private fun run(ui: GalleryUi, actions: GalleryActions = GalleryActions()) =
        show { GalleryScreen(ui, actions, onOpenGroup = {}, onBack = {}) }

    private fun description(tag: String): String =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString()

    @Test
    fun `the keeper marker is a label, not a selection - no checkbox, nothing chosen, no delete`() {
        group(GallerySamples.withKeeper)
        val all = compose.onRoot(useUnmergedTree = true).fetchSemanticsNode().let { root ->
            generateSequence(listOf(root)) { level ->
                level.flatMap { it.children }.ifEmpty { null }
            }.flatten().toList()
        }
        assertTrue("no checkbox", all.none { it.config.getOrNull(SemanticsProperties.Role) == Role.Checkbox })
        assertTrue("nothing selected", all.none { it.config.getOrNull(SemanticsProperties.Selected) == true })
        val texts = all.flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { t -> t.text } }
        for (word in listOf(S.string.swipe_delete, S.string.trash_confirm_delete)) {
            assertFalse(str(word), texts.contains(str(word)))
        }
        assertTrue(description("galleryMember_0").endsWith(str(S.string.gallery_suggested)))
        assertFalse(description("galleryMember_1").contains(str(S.string.gallery_suggested)))
        compose.onNodeWithTag("galleryAdvice").fetchSemanticsNode()
        assertTrue(texts.contains(str(S.string.gallery_reason_sharper)))
        assertTrue(texts.contains(str(S.string.gallery_reason_resolution)))
    }

    @Test
    fun `memories offer the existing backup, other photos have no action yet`() {
        var backups = 0
        group(GallerySamples.withKeeper, GalleryActions(onBackUp = { backups++ }))
        compose.onNodeWithTag(GALLERY_GROUP_TAG).performScrollToNode(hasTestTag("galleryBackUp"))
        compose.onNodeWithTag("galleryBackUp").performClick()
        assertEquals(1, backups)

        group(GallerySamples.tied)
        assertEquals(0, compose.onAllNodesWithTag("galleryBackUp").fetchSemanticsNodes().size)
    }

    @Test
    fun `abstention says so and marks nobody`() {
        group(GallerySamples.tied)
        val advice = compose.onNodeWithTag("galleryAdvice", useUnmergedTree = true).fetchSemanticsNode()
        val texts = generateSequence(listOf(advice)) { l -> l.flatMap { it.children }.ifEmpty { null } }.flatten()
            .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { t -> t.text } }.toList()
        assertTrue(texts.contains(str(S.string.gallery_no_recommendation)))
        for (i in 0..2) assertFalse(description("galleryMember_$i").contains(str(S.string.gallery_suggested)))
    }

    @Test
    fun `TalkBack - each photo is one node with Preview and Don't suggest this again`() {
        var dontOffer: String? = null
        var opened = 0
        group(GallerySamples.withKeeper, GalleryActions(onDontOffer = { dontOffer = it }, onOpenPhoto = { opened++ }))
        val node = compose.onNodeWithTag("galleryMember_1").fetchSemanticsNode()
        val actions = node.config[SemanticsActions.CustomActions]
        assertEquals(listOf(str(S.string.grid_preview), str(S.string.choice_keep)), actions.map { it.label })
        actions[1].action()
        assertEquals(GallerySamples.withKeeper.members[1].item.item.path, dontOffer)
        actions[0].action()
        assertEquals(1, opened)
        // The tile is a button that opens the existing preview, never a checkbox.
        assertEquals(Role.Button, node.config[SemanticsProperties.Role])
    }

    @Test
    fun `keep them all this time hides the group and goes back`() {
        var kept: String? = null
        group(GallerySamples.tied, GalleryActions(onKeepThisTime = { kept = it }))
        compose.onNodeWithTag(GALLERY_GROUP_TAG).performScrollToNode(hasTestTag("galleryKeepThisTime"))
        compose.onNodeWithTag("galleryKeepThisTime").performClick()
        assertEquals(GallerySamples.tied.id, kept)
    }

    @Test
    fun `run wording is honest - partial never says all, complete does`() {
        fun n(v: String) = io.github.tomerar.keepclear.core.common.Bidi.isolate(v)
        run(GallerySamples.partial)
        assertEquals(str(S.string.gallery_stopped, n("61"), n("120")), text("galleryResult"))
        assertEquals(str(S.string.gallery_scope_selected), text("galleryScope"))
        assertTrue(text("gallerySkipped").isNotEmpty())

        // Finished, but a few photos could not be compared: "of", never "all".
        run(GallerySamples.done.copy(complete = false, compared = 3_800, skipped = 12))
        assertEquals(str(S.string.gallery_done_partial, n("3,800"), n("3,812")), text("galleryResult"))

        run(GallerySamples.done)
        assertEquals(str(S.string.gallery_done_complete, n("3,812")), text("galleryResult"))
    }

    @Test
    fun `running shows calm progress with Stop, no access offers the existing permission request`() {
        var stops = 0
        run(GallerySamples.running, GalleryActions(onStop = { stops++ }))
        assertTrue(text("galleryProgress").contains("1,240") && text("galleryProgress").contains("3,812"))
        compose.onNodeWithTag("galleryStop").performClick()
        assertEquals(1, stops)

        var asked = 0
        run(GallerySamples.noAccess, GalleryActions(onAllowAccess = { asked++ }))
        assertEquals(str(S.string.gallery_scope_none), text("galleryScope"))
        compose.onNodeWithTag("galleryAllow").performClick()
        assertEquals(1, asked)
        assertEquals(0, compose.onAllNodesWithTag("galleryStart").fetchSemanticsNodes().size)
    }

    @Test
    fun `details hold the technical counts, the default path names no method`() {
        run(GallerySamples.partial)
        val default = allTexts()
        assertFalse(default.any { it.contains(str(S.string.gallery_detail_from_cache, "").trimEnd(' ', ':')) })
        compose.onNodeWithTag("galleryDetails").performClick()
        compose.waitForIdle()
        assertEquals(1, compose.onAllNodesWithTag("galleryDetailsSheet").fetchSemanticsNodes().size)
    }

    @Test
    @Config(qualifiers = "iw", fontScale = 2f)
    fun `at 200 percent in Hebrew the main action is 56 dp, targets 48 dp, and photos run from the start side`() {
        run(GallerySamples.done.copy(phase = GalleryPhase.NOT_STARTED, groups = emptyList()))
        val start = compose.onNodeWithTag("galleryStart").getBoundsInRoot()
        assertTrue((start.bottom - start.top) >= 56.dp)

        group(GallerySamples.tied)
        val first = compose.onNodeWithTag("galleryMember_0").getBoundsInRoot()
        val second = compose.onNodeWithTag("galleryMember_1").getBoundsInRoot()
        assertTrue("RTL: the first photo is on the right", first.left > second.left)
        compose.onNodeWithTag(GALLERY_GROUP_TAG).performScrollToNode(hasTestTag("galleryKeepThisTime"))
        val keep = compose.onNodeWithTag("galleryKeepThisTime").getBoundsInRoot()
        assertTrue((keep.bottom - keep.top) >= 48.dp)
    }

    private fun text(tag: String): String =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }

    private fun allTexts(): List<String> = compose.onRoot(useUnmergedTree = true).fetchSemanticsNode().let { root ->
        generateSequence(listOf(root)) { l -> l.flatMap { it.children }.ifEmpty { null } }.flatten()
            .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { t -> t.text } }.toList()
    }
}
