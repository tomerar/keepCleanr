// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import io.github.tomerar.keepclear.core.common.Bidi
import io.github.tomerar.keepclear.core.common.ByteSize
import io.github.tomerar.keepclear.core.designsystem.KeepClearTheme
import io.github.tomerar.keepclear.core.model.Category
import io.github.tomerar.keepclear.core.safety.Confirmation
import io.github.tomerar.keepclear.engine.classify.Total
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import io.github.tomerar.keepclear.core.i18n.R as S

/**
 * G4b (GAL-4/6/7/9, AC-G3/AC-G5): the comparison screen's REVIEW choices and the existing editable confirmation. A
 * small holder plays the controller: a REVIEW photo is offered while the group keeps another unchosen photo.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class G4bScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)

    /** The controller's rule in small: REVIEW only, and never the last unchosen photo of the group. */
    private fun offered(g: GalleryGroupUi, chosen: Set<String>): Set<String> {
        val left = g.members.count { it.item.item.path !in chosen }
        return if (left <= 1) {
            emptySet()
        } else {
            g.members.filter { it.item.category == Category.REVIEW && it.item.item.path !in chosen }
                .map { it.item.item.path }.toSet()
        }
    }

    private inner class Holder(start: GalleryGroupUi) {
        var group by mutableStateOf(start.copy(version = "v1", choosable = offered(start, emptySet())))
        val disposed = mutableListOf<Pair<GalleryDisposal, Confirmation>>()
        var opened = 0

        fun toggle(path: String) {
            val now = group.chosen
            val next = when (path) {
                in now -> now - path
                in group.choosable -> now + path
                else -> return
            }
            group = group.copy(chosen = next, choosable = offered(group, next))
        }

        @Composable
        fun Screen() = GalleryReviewGroupScreen(
            group,
            GalleryActions(onToggleChoice = { _, p -> toggle(p) }, onOpenPhoto = { opened++ }),
            disposal = { id ->
                GalleryDisposal(
                    id,
                    group.members.filter { it.item.item.path in group.chosen },
                    listOf(GalleryKeep(id, group.version, group.members)),
                )
            },
            onUnchoose = { _, p -> toggle(p) },
            onChoicesDone = { group = group.copy(chosen = emptySet(), choosable = offered(group, emptySet())) },
            review = GalleryReviewActions(onDispose = { d, c -> disposed += d to c }),
            result = null,
            onBack = {},
        )
    }

    private fun show(holder: Holder) {
        compose.setContent { KeepClearTheme(reducedMotion = true) { holder.Screen() } }
        compose.waitForIdle()
    }

    private fun node(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode()

    private fun scrollTo(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag(GALLERY_GROUP_TAG).performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }

    private fun androidx.compose.ui.semantics.SemanticsNode.textOf(): String =
        (
            config.getOrNull(SemanticsProperties.Text)
                ?: children.flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }
        )
            .joinToString { it.text }

    private fun total(items: List<GalleryMember>) = items.fold(Total()) { t, m -> t + m.item }

    private fun confirmReady() = compose.waitUntil(5_000) {
        compose.onAllNodesWithTag("confirmDelete").fetchSemanticsNodes()
            .any { SemanticsProperties.Disabled !in it.config }
    }

    @Test
    fun `nothing is chosen at first, memories are never a choice, other photos are unchosen checkboxes`() {
        val holder = Holder(GallerySamples.withKeeper)
        show(holder)
        // The two memories: buttons that open the preview; a tap never chooses them (GAL-4, AC-G3).
        for (i in 0..1) {
            assertEquals(Role.Button, node("galleryMember_$i").config[SemanticsProperties.Role])
            assertNull(node("galleryMember_$i").config.getOrNull(SemanticsProperties.Selected))
        }
        compose.onNodeWithTag("galleryMember_0").performClick()
        assertEquals(1, holder.opened)
        assertTrue(holder.group.chosen.isEmpty())
        // The saved copy: a checkbox, not selected (GAL-6).
        val saved = scrollTo("galleryMember_2").fetchSemanticsNode()
        assertEquals(Role.Checkbox, saved.config[SemanticsProperties.Role])
        assertEquals(false, saved.config[SemanticsProperties.Selected])
        assertEquals(0, compose.onAllNodesWithTag("galleryDeleteChosen").fetchSemanticsNodes().size)
        // A group of memories only offers no choice and no delete button at all.
        holder.group = GallerySamples.withKeeper.copy(members = GallerySamples.withKeeper.members.take(2))
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodesWithTag("galleryDeleteChosen").fetchSemanticsNodes().size)
        assertEquals(Role.Button, node("galleryMember_1").config[SemanticsProperties.Role])
    }

    @Test
    fun `explicit choices reach the existing confirmation, which can be edited and cancelled`() {
        val holder = Holder(GallerySamples.tied)
        show(holder)
        val members = holder.group.members
        compose.onNodeWithTag("galleryMember_0").performClick()
        compose.onNodeWithTag("galleryMember_1").performClick()
        // The third is the last unchosen photo: it is not offered, a tap opens it instead (GAL-8).
        assertTrue(members[2].item.item.path !in holder.group.choosable)
        scrollTo("galleryMember_2").performClick()
        assertEquals(setOf(members[0], members[1]).map { it.item.item.path }.toSet(), holder.group.chosen)
        assertEquals(1, holder.opened)

        val two = total(members.take(2))
        val count = context.resources.getQuantityString(S.plurals.file_count, 2, "2")
        val size = ByteSize.format(two.bytes, Locale.US)
        val label = str(S.string.review_delete, str(S.string.total_value, Bidi.isolate(count), Bidi.isolate(size)))
        assertEquals(label, scrollTo("galleryDeleteChosen").fetchSemanticsNode().textOf())
        compose.onNodeWithTag("galleryDeleteChosen").performClick()
        compose.onNodeWithTag("reviewConfirm").assertExists()
        compose.onNodeWithText(str(S.string.clean_confirm_yes)).assertExists()
        val bytes = { m: List<GalleryMember> -> Bidi.isolate(ByteSize.format(total(m).bytes, Locale.US)) }
        compose.onNodeWithText(str(S.string.review_confirm_title, bytes(members.take(2)))).assertExists()

        // ✕ on the first row: it leaves the list and the choices; count and size follow.
        // The row may sit below the small test screen's fold; TalkBack's click reaches it all the same.
        compose.onNodeWithTag("remove_${members[0].item.item.path.substringAfterLast('/')}")
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(setOf(members[1].item.item.path), holder.group.chosen)
        compose.onNodeWithText(str(S.string.review_confirm_title, bytes(members.drop(1).take(1)))).assertExists()

        // Cancel changes nothing.
        compose.onNodeWithText(str(S.string.trash_cancel)).performClick()
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodesWithTag("reviewConfirm").fetchSemanticsNodes().size)
        assertTrue(holder.disposed.isEmpty())
        assertEquals(setOf(members[1].item.item.path), holder.group.chosen)

        // Confirm: exactly the photo shown goes on, named by the user's tap.
        scrollTo("galleryDeleteChosen").performClick()
        confirmReady()
        compose.onNodeWithTag("confirmDelete").performClick()
        val (disposal, confirmation) = holder.disposed.single()
        assertEquals(listOf(members[1].item.item.path), disposal.chosen.map { it.item.item.path })
        assertEquals(setOf(members[1].item.item.path), confirmation.paths)
        assertTrue("the choices are handed over once", holder.group.chosen.isEmpty())
    }

    @Test
    fun `a confirmation is never restored after the activity or process is recreated (GAL-9, E-G08)`() {
        val holder = Holder(GallerySamples.tied)
        val restoration = StateRestorationTester(compose)
        restoration.setContent { KeepClearTheme(reducedMotion = true) { holder.Screen() } }
        compose.onNodeWithTag("galleryMember_0").performClick()
        scrollTo("galleryDeleteChosen").performClick()
        compose.onNodeWithTag("reviewConfirm").assertExists()
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodesWithTag("reviewConfirm").fetchSemanticsNodes().size)
        assertTrue(holder.disposed.isEmpty())
    }

    @Test
    fun `the result of a gallery clean shows on the group with Undo, and test mode says it was simulated`() {
        var undone: Confirmation? = null
        val trashed = GallerySamples.tied.members[0].item.item
        val op = io.github.tomerar.keepclear.core.safety.PlannedOp(
            trashed.ref,
            trashed.path,
            io.github.tomerar.keepclear.core.safety.OpKind.TRASH,
            false,
            trashed.size,
            trashed.mtimeMillis,
        )
        var result by mutableStateOf<CleanResult?>(
            CleanResult(Total(1, trashed.size), 0, 0, emptyList(), androidTrash = true, androidTrashed = listOf(op)),
        )
        compose.setContent {
            KeepClearTheme(reducedMotion = true) {
                GalleryReviewGroupScreen(
                    GallerySamples.tied,
                    GalleryActions(),
                    disposal = { null },
                    onUnchoose = { _, _ -> },
                    onChoicesDone = {},
                    review = GalleryReviewActions(onUndo = { _, c -> undone = c }),
                    result = result,
                    onBack = {},
                )
            }
        }
        compose.onNodeWithTag("androidTrashResult").assertExists()
        compose.onNodeWithTag("undo").performClick()
        assertEquals(setOf(trashed.path), undone!!.paths)
        assertEquals(0, compose.onAllNodesWithTag("emptyNowLink").fetchSemanticsNodes().size)

        result = CleanResult(Total(1, trashed.size), 0, 0, emptyList(), simulated = true)
        compose.waitForIdle()
        compose.onNodeWithTag("simulatedResult").assertExists()
        assertEquals(0, compose.onAllNodesWithTag("undo").fetchSemanticsNodes().size)
    }
}
