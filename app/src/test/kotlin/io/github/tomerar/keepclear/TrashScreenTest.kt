// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import io.github.tomerar.keepclear.core.designsystem.KeepClearTheme
import io.github.tomerar.keepclear.engine.trash.TrashEntry
import io.github.tomerar.keepclear.engine.trash.TrashState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** TRASH-1…5 UI, SEC-1 destination display, SEC-7 delayed confirm. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TrashScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private fun entry(id: String, verified: String? = null) = TrashEntry(
        id = id,
        originalPath = "/storage/emulated/0/DCIM/Camera/IMG_$id.jpg",
        trashedPath = "/storage/emulated/0/.KeepClearTrash/20261031/$id/IMG_$id.jpg",
        volumeRoot = "/storage/emulated/0",
        size = 3_000_000,
        mtimeMillis = 1,
        reason = "TRASH",
        deletedAt = 1_800_000_000_000,
        expiresAt = 1_802_592_000_000,
        deletedAtElapsed = 0,
        bootId = "b",
        state = TrashState.ACTIVE,
        verifiedCopy = verified,
    )

    private var restored: List<TrashEntry>? = null
    private var purged: List<TrashEntry>? = null

    private fun show(trash: TrashUi) = compose.setContent {
        KeepClearTheme { TrashScreen(trash, onRestore = { restored = it }, onPurge = { purged = it }, onBack = {}) }
    }

    @Test
    fun `empty trash says so`() {
        show(TrashUi())
        compose.onNodeWithTag("trashEmpty").assertIsDisplayed()
    }

    @Test
    fun `restore one item`() {
        val a = entry("a")
        show(TrashUi(listOf(a, entry("b"))))
        compose.onNodeWithTag("restore_a").performClick()
        assertEquals(listOf(a), restored)
    }

    @Test
    fun `empty now needs a second, delayed confirmation (TRASH-4, SEC-7)`() {
        val entries = listOf(entry("a"), entry("b"))
        show(TrashUi(entries))
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("emptyNow").performClick()
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("confirmPurge").assertIsNotEnabled()
        assertEquals(null, purged)
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("confirmPurge").assertIsEnabled().performClick()
        assertEquals(entries, purged)
    }

    @Test
    fun `release now only for items whose copy passed the second check (TRASH-5, H1)`() {
        show(
            TrashUi(
                listOf(entry("a", verified = "SanDisk"), entry("b"), entry("c", verified = "SanDisk")),
                releasable = setOf("a"),
            ),
        )
        compose.onNodeWithTag("release_a").assertIsDisplayed()
        compose.onNodeWithTag("release_b").assertDoesNotExist()
        // Trashed before the second check existed: the copy was only read once, possibly from the phone's memory.
        compose.onNodeWithTag("release_c").assertDoesNotExist()
    }

    @Test
    fun `non-original restore destinations are shown before restoring (SEC-1)`() {
        show(
            TrashUi(
                listOf(entry("a")),
                restoreTo = mapOf("a" to "/storage/emulated/0/Download/KeepClear Restored/IMG_a.jpg"),
            ),
        )
        compose.onNodeWithTag("restoreTo_a").assertIsDisplayed()
    }

    @Test
    fun `a changed item says so and restores only to a separate folder after a confirm (owner 1-2)`() {
        var restoredChanged: TrashEntry? = null
        val a = entry("a")
        compose.setContent {
            KeepClearTheme {
                TrashScreen(
                    TrashUi(listOf(a), changed = setOf("a")),
                    onRestore = { restored = it },
                    onPurge = {},
                    onBack = {},
                    onRestoreChanged = { restoredChanged = it },
                )
            }
        }
        compose.onNodeWithTag("changed_a").assertIsDisplayed()
        compose.onNodeWithTag("restore_a").assertDoesNotExist()
        compose.onNodeWithTag("restoreChanged_a").performClick()
        assertEquals(null, restoredChanged)
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("confirmRestoreChanged").fetchSemanticsNodes().any {
                androidx.compose.ui.semantics.SemanticsProperties.Disabled !in it.config
            }
        }
        compose.onNodeWithTag("confirmRestoreChanged").performClick()
        assertEquals(a, restoredChanged)
        assertEquals(null, restored)
    }

    @Test
    fun `last-copy dialog needs an explicit confirm (owner 1-3)`() {
        var confirmed = 0
        var kept = 0
        val op = io.github.tomerar.keepclear.core.safety.PlannedOp(
            io.github.tomerar.keepclear.core.storage.KPath.Local("/storage/emulated/0/DCIM/Camera/IMG_7.jpg"),
            "/storage/emulated/0/DCIM/Camera/IMG_7.jpg",
            io.github.tomerar.keepclear.core.safety.OpKind.TRASH,
            false,
            1,
            1,
        )
        compose.setContent { KeepClearTheme { LastCopyDialog(listOf(op), { confirmed++ }, { kept++ }) } }
        compose.onNodeWithTag("keepLastCopy").performClick()
        assertEquals(1, kept)
        assertEquals(0, confirmed)
    }
}
