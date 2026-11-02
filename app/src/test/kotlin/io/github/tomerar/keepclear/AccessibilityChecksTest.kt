// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.tomerar.keepclear.core.designsystem.KeepClearTheme
import io.github.tomerar.keepclear.core.model.Category
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.core.storage.media.MediaAccess
import io.github.tomerar.keepclear.engine.classify.ClassificationSummary
import io.github.tomerar.keepclear.engine.classify.Classifier
import io.github.tomerar.keepclear.engine.scan.ScanItem
import io.github.tomerar.keepclear.engine.trash.TrashEntry
import io.github.tomerar.keepclear.engine.trash.TrashState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * M18 / A11Y-1: an accessibility pass over the main screens, on the semantics tree that TalkBack reads.
 *
 * The Accessibility Test Framework (what Android's Accessibility Scanner runs) does not look inside Compose under
 * Robolectric ("Composable content is not evaluated in this environment"), so its main rules are checked here
 * directly: every control has something to say (SpeakableTextPresentCheck), every enabled control is at least
 * 48 × 48 dp to touch (TouchTargetSizeCheck), no label repeats the role (RedundantDescriptionCheck), and at 200%
 * font size no text with a number is cut off. Colour contrast needs a real screen and stays a device check (D18-1).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE) // real text measuring, so cut-off text is real
class AccessibilityChecksTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = 1_800_000_000_000L
    private val old = now - 400L * 24 * 3_600_000
    private val classifier = Classifier()

    private fun item(rel: String, size: Long = 1000) = classifier.classify(
        ScanItem(KPath.Local("/storage/emulated/0/$rel"), "/storage/emulated/0/$rel", size, old),
        now = now,
    )

    private fun summary() = ClassificationSummary.of(
        listOf(
            item("DCIM/.thumbnails/1.jpg"),
            item("Download/a.tmp"),
            item("DCIM/Camera/IMG_1.jpg", 3_000_000),
            item("Pictures/Screenshots/s1.png"),
            item("Pictures/Screenshots/s2.png"),
        ),
    )

    private fun trashEntry(id: String) = TrashEntry(
        id = id,
        originalPath = "/storage/emulated/0/DCIM/Camera/IMG_$id.jpg",
        trashedPath = "/storage/emulated/0/.KeepClearTrash/20261031/$id/IMG_$id.jpg",
        volumeRoot = "/storage/emulated/0",
        size = 3_000_000,
        mtimeMillis = 1,
        reason = "TRASH",
        deletedAt = now,
        expiresAt = now + 30L * 86_400_000,
        deletedAtElapsed = 0,
        bootId = "b",
        state = TrashState.ACTIVE,
    )

    private val screens: Map<String, @Composable () -> Unit> = mapOf(
        "home before a scan" to { HomeScreen(HomeUiState(mediaAccess = MediaAccess.NONE), showDebugTools = false) },
        "home after a scan" to {
            HomeScreen(
                HomeUiState(mediaAccess = MediaAccess.FULL, scan = ScanState.Done(5, 5, true), summary = summary()),
                showDebugTools = false,
            )
        },
        "review group" to {
            GroupScreen(summary().group("screenshots", Category.REVIEW)!!, onOpenItem = {}, onBack = {})
        },
        "item preview" to { PreviewScreen(item("Download/Memes/a.zip"), onBack = {}) },
        "swipe review" to {
            SwipeReview(summary().group("screenshots", Category.REVIEW)!!.items, onDone = {}, onClose = {})
        },
        "trash" to {
            TrashScreen(
                TrashUi(listOf(trashEntry("a"), trashEntry("b"))),
                onRestore = { _, _ -> },
                onPurge = { _, _ -> },
                onBack = {},
            )
        },
        "settings" to {
            SettingsScreen(
                testModeOn = false,
                onTestModeOn = {},
                onTestModeOff = {},
                onBack = {},
                actions = SettingsActions(trashBytes = 3_100_000_000, showDeviceCheck = true),
            )
        },
        "device check (test builds)" to {
            io.github.tomerar.keepclear.devcheck.DeviceCheckScreen(
                io.github.tomerar.keepclear.devcheck.DeviceCheckUi(
                    enabled = true,
                    state = io.github.tomerar.keepclear.devcheck.DeviceCheckState(
                        report = "KeepClear device check\n== Scan (D3-1) ==\n[PASS] Scan time: 21.0 s for 2 storage " +
                            "areas\n[INFO] Found: 10,472 files, 75,200,000,000 bytes (75.2 GB)\n\nNo file paths are included.\n",
                    ),
                ),
                onBack = {},
            )
        },
        "device check running" to {
            io.github.tomerar.keepclear.devcheck.DeviceCheckScreen(
                io.github.tomerar.keepclear.devcheck.DeviceCheckUi(
                    enabled = true,
                    state = io.github.tomerar.keepclear.devcheck.DeviceCheckState(running = true),
                ),
                onBack = {},
            )
        },
    )

    /** Problems on the screen currently shown; [fontCheck] adds the cut-off-number check. */
    private fun audit(fontCheck: Boolean = false): List<String> {
        val nodes = compose.onAllNodes(SemanticsMatcher("any node") { true }).fetchSemanticsNodes()
        val density = RuntimeEnvironment.getApplication().resources.displayMetrics.density
        // The window's root; an empty extra root (a popup that is not open) has no size.
        val screen = compose.onAllNodes(isRoot()).fetchSemanticsNodes().maxBy { it.size.width * it.size.height }
            .boundsInRoot
        return nodes.flatMap { node ->
            val config = node.config
            val problems = mutableListOf<String>()
            val label = label(node)
            val actionable = SemanticsActions.OnClick in config || SemanticsProperties.ToggleableState in config
            val enabled = SemanticsProperties.Disabled !in config
            val bounds = node.boundsInRoot
            val visible = bounds.width > 0 && bounds.height > 0 && screen.overlaps(bounds)
            // A control cut by the screen edge (scrolled half out) is measured by the scanner only once fully shown.
            val fullyShown = bounds.top >= screen.top && bounds.bottom <= screen.bottom &&
                bounds.left >= screen.left && bounds.right <= screen.right
            if (actionable && label.isBlank()) {
                problems += "control without a label at $bounds (tag ${config.getOrNull(SemanticsProperties.TestTag)})"
            }
            if (actionable && enabled && visible && fullyShown) {
                // What the scanner measures: Compose reports the touch area, which it already widens to 48 dp
                // for a small control when there is room, so this fails only where that is impossible.
                val touch = node.touchBoundsInRoot
                val w = touch.width / density
                val h = touch.height / density
                if (w < MIN_TOUCH_DP - 0.5f || h < MIN_TOUCH_DP - 0.5f) {
                    problems += "\"$label\": touch target ${w.toInt()}×${h.toInt()} dp (< $MIN_TOUCH_DP)"
                }
            }
            config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().forEach { d ->
                if (redundantRole.containsMatchIn(d)) problems += "\"$d\": the label repeats the role"
            }
            if (fontCheck && visible) {
                val text = config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ")
                if (text.any { it.isDigit() } && cutOff(node)) problems += "\"$text\" is cut off at 200% font size"
            }
            problems
        }.distinct()
    }

    private fun label(node: SemanticsNode): String {
        val c = node.config
        return listOfNotNull(
            c.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" "),
            c.getOrNull(SemanticsProperties.Text)?.joinToString(" "),
            c.getOrNull(SemanticsProperties.EditableText)?.text,
            c.getOrNull(SemanticsProperties.StateDescription),
        ).joinToString(" ").trim()
    }

    private fun cutOff(node: SemanticsNode): Boolean {
        val results = mutableListOf<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        // Ellipsis or lines that do not fit the height. (didOverflowWidth is not reliable under Robolectric.)
        return results.any { r -> r.didOverflowHeight || (0 until r.lineCount).any { r.isLineEllipsized(it) } }
    }

    // createComposeRule allows one setContent per test, so each screen is swapped in through a state.
    private var current: MutableState<@Composable () -> Unit>? = null

    private fun show(content: @Composable () -> Unit) {
        val state = current
        if (state == null) {
            val s = mutableStateOf(content)
            current = s
            compose.setContent { KeepClearTheme { s.value() } }
        } else {
            state.value = content
        }
        compose.waitForIdle()
    }

    private fun auditScreens(fontCheck: Boolean) = screens.flatMap { (name, content) ->
        show(content)
        audit(fontCheck).map { "$name: $it" }
    }

    @Test
    fun `the checks catch an unlabeled control, a role in the label and a cut-off number`() {
        show {
            Column {
                Box(Modifier.size(20.dp).clickable {})
                Box(Modifier.size(48.dp).semantics { contentDescription = "Delete button" }.clickable {})
                Box(Modifier.size(width = 30.dp, height = 20.dp)) {
                    Text("1,234,567 files", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        val problems = audit(fontCheck = true)
        assertTrue(problems.toString(), problems.any { it.startsWith("control without a label") })
        assertTrue(problems.toString(), problems.any { "repeats the role" in it })
        assertTrue(problems.toString(), problems.any { "cut off" in it })
    }

    @Test
    fun `main screens have labels, 48 dp targets and no role words`() {
        assertEquals(emptyList<String>(), auditScreens(fontCheck = false))
    }

    @Test
    @Config(sdk = [36], fontScale = 2.0f)
    fun `at 200 percent font size no number is cut off on the main screens`() {
        assertEquals(emptyList<String>(), auditScreens(fontCheck = true))
    }

    private companion object {
        const val MIN_TOUCH_DP = 48
        val redundantRole = Regex("""\b(button|checkbox|switch)\b|כפתור|לחצן""", RegexOption.IGNORE_CASE)
    }
}
