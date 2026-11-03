// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import io.github.tomerar.keepclear.core.common.ByteSize
import io.github.tomerar.keepclear.core.designsystem.KeepClearTheme
import io.github.tomerar.keepclear.core.i18n.R
import io.github.tomerar.keepclear.core.model.Category
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.engine.classify.ClassificationSummary
import io.github.tomerar.keepclear.engine.classify.ClassifiedItem
import io.github.tomerar.keepclear.engine.classify.Classifier
import io.github.tomerar.keepclear.engine.classify.GroupSummary
import io.github.tomerar.keepclear.engine.scan.ScanItem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneId
import java.util.Locale

/**
 * PR H2b (owner brief H2 item 8): the swipe screen's finish button, resumable sessions, edge labels and the
 * one-time hint (both layout directions, swapped and not), equal Keep/Delete, „חזרה”, date and size, the setting,
 * haptics through a fake hook, and TalkBack's actions and polite counter.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h800dp-xhdpi")
class SwipeH2bTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val now = 1_800_000_000_000L
    private val classifier = Classifier()

    private fun item(i: Int, size: Long = 1_000L * i): ClassifiedItem {
        val p = "/storage/emulated/0/Pictures/Screenshots/s$i.png"
        return classifier.classify(ScanItem(KPath.Local(p), p, size, 1_678_000_000_000L + i), now = now)
    }

    private fun items(n: Int = 4) = (1..n).map { item(it) }

    private fun group(list: List<ClassifiedItem>): GroupSummary =
        ClassificationSummary.of(list).group("screenshots", Category.REVIEW)!!

    private class Recorder : SwipeHaptics {
        val felt = mutableListOf<SwipeHaptic>()

        override fun perform(kind: SwipeHaptic) {
            felt += kind
        }
    }

    private var lt: TestApp? = null

    @After
    fun closeApp() {
        lt?.close()
    }

    /** The swipe screen with decisions held here, a fake haptics hook and the given prefs and direction. */
    private fun showSwipe(
        list: List<ClassifiedItem> = items(),
        prefs: SwipePrefs = SwipePrefs(),
        direction: LayoutDirection = LayoutDirection.Ltr,
        haptics: SwipeHaptics = Recorder(),
        onDone: (List<ClassifiedItem>) -> Unit = {},
        start: List<SwipeDecision> = emptyList(),
    ): () -> List<SwipeDecision> {
        var decisions by mutableStateOf(start)
        compose.setContent {
            KeepClearTheme {
                CompositionLocalProvider(
                    LocalLayoutDirection provides direction,
                    LocalSwipePrefs provides prefs,
                    LocalSwipeHaptics provides haptics,
                ) {
                    SwipeReview(list, onDone = onDone, onClose = {}, decisions = decisions, onDecisions = {
                        decisions = it
                    })
                }
            }
        }
        return { decisions }
    }

    private fun text(tag: String, unmerged: Boolean = false) = compose.onNodeWithTag(tag, useUnmergedTree = unmerged)
        .fetchSemanticsNode().config.getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString("") { it.text }
        .filter { it.code !in 0x2066..0x2069 }

    // ------------------------------------------------------------------ "לסיים ולבדוק (N)"

    @Test
    fun `the finish button is on screen from the start, counts the items marked Delete and hands only them on`() {
        val list = items()
        var done: List<ClassifiedItem>? = null
        showSwipe(list, onDone = { done = it })
        compose.onNodeWithTag("swipeFinish").assertExists().assertIsNotEnabled()
        assertEquals("Finish and review", text("swipeFinish"))
        compose.onNodeWithTag("swipeDelete").performClick() // s1
        compose.onNodeWithTag("swipeKeep").performClick() // s2
        compose.onNodeWithTag("swipeDelete").performClick() // s3; s4 never seen
        compose.onNodeWithTag("swipeFinish").assertIsEnabled()
        assertEquals("Finish and review · 2", text("swipeFinish"))
        compose.onNodeWithTag("swipeFinish").performClick()
        assertEquals(listOf(list[0], list[2]), done)
    }

    @Test
    fun `finishing mid-way opens the usual confirmation for just the marked items, and only it deletes`() {
        val g = group(items())
        var deleted: List<ClassifiedItem>? = null
        compose.setContent {
            KeepClearTheme {
                GroupScreen(g, onOpenItem = {}, onBack = {}, onDelete = { list, _ -> deleted = list })
            }
        }
        compose.onNodeWithTag("grid").performScrollToNode(hasTestTag("startSwipe"))
        compose.onNodeWithTag("startSwipe").performClick()
        compose.onNodeWithTag("swipeDelete").performClick()
        compose.onNodeWithTag("swipeKeep").performClick()
        compose.onNodeWithTag("swipeFinish").performClick()
        // Nothing deleted from the swipe screen; the confirmation lists one item (SAFETY-10: the two never swiped
        // are not selected).
        assertNull(deleted)
        compose.onNodeWithTag("swipe").assertDoesNotExist()
        compose.onNodeWithTag("reviewConfirm").assertExists()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("confirmDelete").fetchSemanticsNodes()
                .any { SemanticsProperties.Disabled !in it.config }
        }
        compose.onNodeWithTag("confirmDelete").performClick()
        assertEquals(listOf(g.items[0].item.path), deleted!!.map { it.item.path })
    }

    @Test
    fun `a session finished in test mode deletes nothing (SAFETY-9)`() {
        val t = TestApp(tmp.newFolder("phone")).also { lt = it }
        assertTrue(t.app.testMode.isOn) // test builds start in test mode
        val f = t.file("Pictures/Screenshots/a.png")
        val scanned = classifier.classify(
            ScanItem(KPath.Local(f.absolutePath), f.absolutePath, f.length(), f.lastModified()),
            now = System.currentTimeMillis(),
        )
        val decisions = SwipeSession.decide(emptyList(), scanned, SwipeChoice.DELETE)
        val marked = SwipeSession.toDelete(listOf(scanned), decisions)
        val vm = t.viewModel(HomeViewModel::class.java)
        vm.clean(marked, userConfirmed(marked.map { it.item.path }))
        TestApp.awaitMain("clean in test mode") { vm.state.value.cleanResult != null && !vm.state.value.busy }
        assertTrue(f.exists())
        assertTrue(vm.state.value.cleanResult!!.simulated)
    }

    // ------------------------------------------------------------------ resumable sessions

    @Test
    fun `closing and starting again offers to continue where you left off, at the same place`() {
        val g = group(items())
        compose.setContent { KeepClearTheme { GroupScreen(g, onOpenItem = {}, onBack = {}) } }
        compose.onNodeWithTag("grid").performScrollToNode(hasTestTag("startSwipe"))
        compose.onNodeWithTag("startSwipe").performClick()
        compose.onNodeWithTag("swipeDelete").performClick()
        compose.onNodeWithTag("swipeKeep").performClick()
        compose.onNodeWithTag("swipeClose").performClick()
        compose.onNodeWithTag("startSwipe").performClick()
        compose.onNodeWithTag("swipeResume").assertExists()
        compose.onNodeWithTag("swipeResumeContinue").performClick()
        assertEquals("3 of 4", text("swipeProgress"))
        assertEquals("Finish and review · 1", text("swipeFinish"))
    }

    @Test
    fun `start over forgets the saved session`() {
        val g = group(items())
        compose.setContent { KeepClearTheme { GroupScreen(g, onOpenItem = {}, onBack = {}) } }
        compose.onNodeWithTag("grid").performScrollToNode(hasTestTag("startSwipe"))
        compose.onNodeWithTag("startSwipe").performClick()
        compose.onNodeWithTag("swipeDelete").performClick()
        compose.onNodeWithTag("swipeClose").performClick()
        compose.onNodeWithTag("startSwipe").performClick()
        compose.onNodeWithTag("swipeResumeRestart").performClick()
        assertEquals("1 of 4", text("swipeProgress"))
        compose.onNodeWithTag("swipeFinish").assertIsNotEnabled()
        compose.onNodeWithTag("swipeClose").performClick()
        // Nothing saved any more: starting goes straight in.
        compose.onNodeWithTag("startSwipe").performClick()
        compose.onNodeWithTag("swipeResume").assertDoesNotExist()
    }

    @Test
    fun `finishing ends the session - no resume offer afterwards`() {
        val g = group(items())
        compose.setContent { KeepClearTheme { GroupScreen(g, onOpenItem = {}, onBack = {}) } }
        compose.onNodeWithTag("grid").performScrollToNode(hasTestTag("startSwipe"))
        compose.onNodeWithTag("startSwipe").performClick()
        compose.onNodeWithTag("swipeDelete").performClick()
        compose.onNodeWithTag("swipeFinish").performClick()
        compose.onNodeWithTag("reviewConfirm").assertExists()
        compose.onNodeWithTag("grid").performScrollToNode(hasTestTag("startSwipe"))
        compose.onNodeWithTag("startSwipe").performClick()
        compose.onNodeWithTag("swipeResume").assertDoesNotExist()
        compose.onNodeWithTag("swipe").assertExists()
    }

    @Test
    fun `on resume a decision for a file that changed or vanished is dropped, said, and the file comes up again`() {
        val list = items()
        val saved = listOf(
            SwipeDecision.of(list[0], SwipeChoice.DELETE).copy(size = 1), // s1 changed since
            SwipeDecision.of(list[1], SwipeChoice.DELETE),
            SwipeDecision("/storage/emulated/0/Pictures/Screenshots/gone.png", SwipeChoice.DELETE, 5, 5), // vanished
        )
        var swipe by mutableStateOf<List<SwipeDecision>?>(null)
        var ended = 0
        var deleted: List<ClassifiedItem>? = null
        compose.setContent {
            KeepClearTheme {
                GroupScreen(
                    group(list),
                    onOpenItem = {},
                    onBack = {},
                    onDelete = { l, _ -> deleted = l },
                    selection = GroupSelection(
                        emptySet(),
                        {},
                        swipe = swipe,
                        onSwipe = { swipe = it },
                        saved = saved,
                        onSwipeEnd = { ended++ },
                    ),
                )
            }
        }
        compose.onNodeWithTag("grid").performScrollToNode(hasTestTag("startSwipe"))
        compose.onNodeWithTag("startSwipe").performClick()
        assertEquals(
            "2 files changed or are gone since, so their choices were dropped.",
            text("swipeResumeDropped"),
        )
        compose.onNodeWithTag("swipeResumeContinue").performClick()
        assertEquals(listOf(SwipeDecision.of(list[1], SwipeChoice.DELETE)), swipe)
        // s1 comes up again (it changed), and only s2 is marked.
        assertEquals("2 of 4", text("swipeProgress"))
        assertEquals("Finish and review · 1", text("swipeFinish"))
        compose.onNodeWithTag("swipeFinish").performClick()
        assertEquals(1, ended)
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("confirmDelete").fetchSemanticsNodes()
                .any { SemanticsProperties.Disabled !in it.config }
        }
        compose.onNodeWithTag("confirmDelete").performClick()
        assertEquals(listOf(list[1].item.path), deleted!!.map { it.item.path })
    }

    @Test
    fun `the ViewModel saves the session and a new run of the app finds it`() {
        val t = TestApp(tmp.newFolder("phone")).also { lt = it }
        val list = items()
        val key = GroupKey.of("screenshots", Category.REVIEW)
        val vm = t.viewModel(HomeViewModel::class.java)
        val d = listOf(SwipeDecision.of(list[0], SwipeChoice.DELETE), SwipeDecision.of(list[1], SwipeChoice.KEEP))
        vm.swipe(key, d)
        vm.swipe(key, null) // closed: kept
        assertEquals(d, vm.state.value.savedSwipes[key])
        TestApp.awaitMain("written") { t.app.swipeStore.loadAll()[key] == d }
        // The next run of the app.
        val next = t.viewModel(HomeViewModel::class.java, t.newStore())
        TestApp.awaitMain("loaded") { next.state.value.savedSwipes[key] == d }
        next.endSwipe(key)
        assertNull(next.state.value.savedSwipes[key])
        TestApp.awaitMain("forgotten") { t.app.swipeStore.loadAll().isEmpty() }
    }

    // ------------------------------------------------------------------ edge labels and the hint (RTL and LTR)

    private fun dragLabel(
        direction: LayoutDirection,
        toLeft: Boolean,
        swapped: Boolean = false,
    ): Pair<String, Boolean> {
        showSwipe(prefs = SwipePrefs(swapped = swapped), direction = direction)
        compose.onNodeWithTag("swipeLabel", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("swipeCard", useUnmergedTree = true).performTouchInput {
            down(center)
            // Under the threshold: the edge label shows already, before letting go means anything.
            moveBy(androidx.compose.ui.geometry.Offset(if (toLeft) -width * 0.15f else width * 0.15f, 0f))
        }
        val card = compose.onNodeWithTag("swipeCard", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val label = compose.onNodeWithTag("swipeLabel", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val onRight = label.center.x > card.center.x
        val kept = compose.onAllNodesWithTag("swipeLabel_KEEP", useUnmergedTree = true).fetchSemanticsNodes()
        return (if (kept.isEmpty()) "DELETE" else "KEEP") to onRight
    }

    @Test
    fun `edge label - LTR, dragged left it says Delete on the right edge`() {
        assertEquals("DELETE" to true, dragLabel(LayoutDirection.Ltr, toLeft = true))
    }

    @Test
    fun `edge label - RTL, dragged left it says Delete on the right edge`() {
        assertEquals("DELETE" to true, dragLabel(LayoutDirection.Rtl, toLeft = true))
    }

    @Test
    fun `edge label - LTR, dragged right it says Keep on the left edge`() {
        assertEquals("KEEP" to false, dragLabel(LayoutDirection.Ltr, toLeft = false))
    }

    @Test
    fun `edge label - RTL, dragged right it says Keep on the left edge`() {
        assertEquals("KEEP" to false, dragLabel(LayoutDirection.Rtl, toLeft = false))
    }

    @Test
    fun `edge label - swapped, RTL, dragged left it says Keep`() {
        assertEquals("KEEP" to true, dragLabel(LayoutDirection.Rtl, toLeft = true, swapped = true))
    }

    @Test
    fun `edge label - swapped, LTR, dragged right it says Delete`() {
        assertEquals("DELETE" to false, dragLabel(LayoutDirection.Ltr, toLeft = false, swapped = true))
    }

    @Test
    fun `the one-time hint says which way deletes, and goes after the first decision`() {
        var seen = 0
        var prefs by mutableStateOf(SwipePrefs(hintSeen = false, onHintSeen = { seen++ }))
        compose.setContent {
            KeepClearTheme {
                CompositionLocalProvider(LocalSwipePrefs provides prefs) {
                    SwipeReview(items(), onDone = {}, onClose = {})
                }
            }
        }
        compose.onNodeWithTag("swipeHint").assertExists()
        assertTrue(text("swipeHintText", unmerged = true).startsWith("Swipe left to delete, right to keep."))
        compose.onNodeWithTag("swipeKeep").performClick()
        assertEquals(1, seen)
        compose.onNodeWithTag("swipeHint").assertDoesNotExist()
        prefs = SwipePrefs(hintSeen = true)
        compose.onNodeWithTag("swipeUndo").performClick()
        compose.onNodeWithTag("swipeHint").assertDoesNotExist() // once seen, never again
    }

    @Test
    fun `the hint follows the swapped setting`() {
        showSwipe(prefs = SwipePrefs(hintSeen = false, swapped = true))
        assertTrue(text("swipeHintText", unmerged = true).startsWith("Swipe right to delete, left to keep."))
    }

    @Test
    fun `the setting and the hint are kept on the phone`() {
        val s = SwipeSettings(context)
        assertFalse(s.swapped.value)
        assertFalse(s.hintSeen.value)
        s.setSwapped(true)
        s.markHintSeen()
        val again = SwipeSettings(context)
        assertTrue(again.swapped.value)
        assertTrue(again.hintSeen.value)
    }

    @Test
    fun `Settings has the swap switch and says what it does now`() {
        var swapped by mutableStateOf(false)
        compose.setContent {
            KeepClearTheme {
                SettingsScreen(
                    testModeOn = true,
                    onTestModeOn = {},
                    onTestModeOff = {},
                    onBack = {},
                    actions = SettingsActions(swipeSwapped = swapped, onSwipeSwapped = { swapped = it }),
                )
            }
        }
        compose.onNodeWithTag("settings").performScrollToNode(hasTestTag("swipeSwap"))
        assertEquals("Swipe left to delete, right to keep.", text("swipeSwapNow"))
        compose.onNodeWithTag("swipeSwapSwitch").performClick()
        assertTrue(swapped)
        assertEquals("Swipe right to delete, left to keep.", text("swipeSwapNow"))
    }

    // ------------------------------------------------------------------ buttons, undo, date

    private fun buttons(direction: LayoutDirection, swapped: Boolean): Boolean {
        showSwipe(prefs = SwipePrefs(swapped = swapped), direction = direction)
        val keep = compose.onNodeWithTag("swipeKeep").fetchSemanticsNode().boundsInRoot
        val delete = compose.onNodeWithTag("swipeDelete").fetchSemanticsNode().boundsInRoot
        assertEquals(keep.width, delete.width, 0.5f) // equal weight
        return delete.center.x < keep.center.x
    }

    @Test
    fun `Delete button on the left in LTR`() = assertTrue(buttons(LayoutDirection.Ltr, swapped = false))

    @Test
    fun `Delete button on the left in RTL`() = assertTrue(buttons(LayoutDirection.Rtl, swapped = false))

    @Test
    fun `swapped, Delete button on the right in LTR`() = assertFalse(buttons(LayoutDirection.Ltr, swapped = true))

    @Test
    fun `swapped, Delete button on the right in RTL`() = assertFalse(buttons(LayoutDirection.Rtl, swapped = true))

    @Test
    fun `Keep and Delete look the same - Delete is not the filled ink button`() {
        showSwipe()
        val keep = compose.onNodeWithTag("swipeKeep").captureToImage().toPixelMap()
        val delete = compose.onNodeWithTag("swipeDelete").captureToImage().toPixelMap()
        compose.onNodeWithTag("swipeDelete").performClick() // the finish button is enabled (filled ink) now
        val ink = compose.onNodeWithTag("swipeFinish").captureToImage().toPixelMap()
        assertEquals(keep[keep.width / 2, 5], delete[delete.width / 2, 5])
        assertNotEquals(ink[ink.width / 2, 5], delete[delete.width / 2, 5])
    }

    @Test
    fun `undo is its own button next to close, and goes back one decision`() {
        val decisions = showSwipe()
        compose.onNodeWithTag("swipeUndo").assertIsNotEnabled()
        compose.onNodeWithTag("swipeDelete").performClick()
        compose.onNodeWithTag("swipeUndo").performClick()
        assertEquals(emptyList<SwipeDecision>(), decisions())
        compose.onNodeWithTag("swipe").assertExists() // still open: undo never closes
        assertNotEquals(text("swipeUndo"), text("swipeClose"))
        assertEquals("Undo", text("swipeUndo"))
    }

    @Test
    fun `the card shows the photo's date and size, not only its name`() {
        val list = items()
        showSwipe(list)
        val day = MediaGrid.dayText(list[0].item.mtimeMillis, ZoneId.systemDefault(), Locale.US)
        val size = ByteSize.format(list[0].item.size, Locale.US).filter { it.code !in 0x2066..0x2069 }
        assertEquals("$day · $size", text("swipeDate", unmerged = true))
        assertTrue(day.contains("2023"))
    }

    // ------------------------------------------------------------------ haptics

    @Test
    fun `haptics - confirm on keep, reject on delete, nothing on skip, a tick at the threshold`() {
        val r = Recorder()
        showSwipe(haptics = r)
        compose.onNodeWithTag("swipeKeep").performClick()
        compose.onNodeWithTag("swipeDelete").performClick()
        compose.onNodeWithTag("swipeSkip").performClick()
        assertEquals(listOf(SwipeHaptic.KEEP, SwipeHaptic.DELETE), r.felt)
        r.felt.clear()
        compose.onNodeWithTag("swipeCard", useUnmergedTree = true).performTouchInput { swipeLeft() }
        assertEquals(listOf(SwipeHaptic.THRESHOLD, SwipeHaptic.DELETE), r.felt)
    }

    @Test
    fun `haptic constants - CONFIRM and REJECT, the threshold tick guarded by API level`() {
        assertEquals(HapticFeedbackConstants.CONFIRM, SwipeHaptics.constantFor(SwipeHaptic.KEEP, 30))
        assertEquals(HapticFeedbackConstants.REJECT, SwipeHaptics.constantFor(SwipeHaptic.DELETE, 30))
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, SwipeHaptics.constantFor(SwipeHaptic.THRESHOLD, 33))
        assertEquals(
            HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE,
            SwipeHaptics.GESTURE_THRESHOLD_ACTIVATE,
        )
        assertEquals(
            HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE,
            SwipeHaptics.constantFor(SwipeHaptic.THRESHOLD, 34),
        )
    }

    private class RecordingView(context: Context) : View(context) {
        val calls = mutableListOf<Pair<Int, Int>>()

        override fun performHapticFeedback(feedbackConstant: Int): Boolean = performHapticFeedback(feedbackConstant, 0)

        override fun performHapticFeedback(feedbackConstant: Int, flags: Int): Boolean {
            calls += feedbackConstant to flags
            return true
        }
    }

    @Test
    @Suppress("DEPRECATION") // the flag is the one to never pass; Android ignores it for apps since API 33
    fun `haptics go through the view and respect the system setting`() {
        val v = RecordingView(context)
        SwipeHaptics.of(v).perform(SwipeHaptic.DELETE)
        assertEquals(1, v.calls.size)
        assertEquals(HapticFeedbackConstants.REJECT, v.calls.single().first)
        // Never FLAG_IGNORE_GLOBAL_SETTING: "Touch feedback" off in Android means nothing is felt.
        assertEquals(0, v.calls.single().second and HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
        v.isHapticFeedbackEnabled = false
        SwipeHaptics.of(v).perform(SwipeHaptic.KEEP)
        assertEquals(1, v.calls.size)
    }

    // ------------------------------------------------------------------ TalkBack

    private fun actionLabels() = compose.onNodeWithTag("swipeItem").fetchSemanticsNode().config
        .getOrElse(SemanticsActions.CustomActions) { emptyList() }.map { it.label }

    @Test
    fun `TalkBack - custom actions Keep, Delete and Undo, and they decide`() {
        val r = Recorder()
        val decisions = showSwipe(haptics = r)
        assertEquals(listOf("Keep", "Delete"), actionLabels())
        compose.onNodeWithTag("swipeItem").fetchSemanticsNode().config[SemanticsActions.CustomActions]
            .single { it.label == "Delete" }.action()
        compose.waitForIdle()
        assertEquals(SwipeChoice.DELETE, decisions().single().choice)
        assertEquals(listOf(SwipeHaptic.DELETE), r.felt)
        assertEquals(listOf("Keep", "Delete", "Undo"), actionLabels())
        compose.onNodeWithTag("swipeItem").fetchSemanticsNode().config[SemanticsActions.CustomActions]
            .single { it.label == "Undo" }.action()
        compose.waitForIdle()
        assertEquals(emptyList<SwipeDecision>(), decisions())
    }

    @Test
    fun `TalkBack - the counter is a polite live region`() {
        showSwipe()
        val node = compose.onNodeWithTag("swipeProgress").fetchSemanticsNode()
        assertEquals(LiveRegionMode.Polite, node.config[SemanticsProperties.LiveRegion])
        compose.onNodeWithTag("swipeKeep").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals("2 of 4", text("swipeProgress"))
    }

    // ------------------------------------------------------------------ Hebrew

    @Test
    @Config(qualifiers = "iw-w360dp-h800dp-xhdpi")
    fun `in Hebrew - undo reads chazara, the finish button counts, the hint names left`() {
        showSwipe(prefs = SwipePrefs(hintSeen = false), direction = LayoutDirection.Rtl)
        assertEquals("חזרה", text("swipeUndo"))
        assertEquals("לסגור", text("swipeClose"))
        assertTrue(
            text("swipeHintText", unmerged = true).startsWith(context.getString(R.string.swipe_dir_left_deletes)),
        )
        compose.onNodeWithTag("swipeDelete").performClick()
        assertEquals("לסיים ולבדוק · 1", text("swipeFinish"))
    }

    @Test
    fun `sessions are kept in KeepClear's own settings file`() {
        val t = TestApp(tmp.newFolder("phone")).also { lt = it }
        t.app.swipeStore.save("k|REVIEW", listOf(SwipeDecision("/a", SwipeChoice.KEEP, 1, 1)))
        val prefs = context.getSharedPreferences(SwipeSessionStore.PREFS, Context.MODE_PRIVATE)
        assertEquals(setOf("session:k|REVIEW"), prefs.all.keys)
        t.app.swipeStore.clear("k|REVIEW")
        assertTrue(prefs.all.isEmpty())
    }
}
