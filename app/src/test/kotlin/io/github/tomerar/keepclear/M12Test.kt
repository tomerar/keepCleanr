// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import io.github.tomerar.keepclear.core.designsystem.KeepClearTheme
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.engine.classify.Total
import io.github.tomerar.keepclear.engine.scan.ScanItem
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** M12: Android's trash in KeepClear's Trash screen, and results after Android's own dialog. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class M12Test {
    @get:Rule
    val compose = createComposeRule()

    private val item = ScanItem(
        KPath.Media("external_primary", 7),
        "/storage/emulated/0/DCIM/Camera/IMG_7.jpg",
        1_000_000,
        1,
        isTrashed = true,
    )

    @Test
    fun `Android's trash can be restored, and deleting needs a second confirmation`() {
        val calls = mutableListOf<Pair<Int, Boolean>>()
        compose.setContent {
            KeepClearTheme {
                TrashScreen(
                    TrashUi(android = listOf(item)),
                    onRestore = { _, _ -> },
                    onPurge = { _, _ -> },
                    onBack = {},
                    onAndroidTrash = { items, restore, confirmation ->
                        assertEquals(setOf(item.path), confirmation.paths)
                        calls += items.size to restore
                    },
                )
            }
        }
        compose.onNodeWithTag("trash").performScrollToNode(hasTestTag("androidTrash"))
        compose.onNodeWithTag("androidRestore").performClick()
        assertEquals(listOf(1 to true), calls)
        compose.onNodeWithTag("androidEmpty").performClick()
        assertEquals(1, calls.size)
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("confirmAndroidEmpty").fetchSemanticsNodes()
                .any { SemanticsProperties.Disabled !in it.config }
        }
        compose.onNodeWithTag("confirmAndroidEmpty").performClick()
        assertEquals(1 to false, calls.last())
    }

    @Test
    fun `a declined Android dialog says nothing changed, and offers no Undo`() {
        val r = CleanResult(Total(), 0, 0, emptyList(), declined = true)
        compose.setContent {
            KeepClearTheme {
                SafeScreen(
                    summary = io.github.tomerar.keepclear.engine.classify.ClassificationSummary.of(emptyList()),
                    result = r,
                    trimResult = null,
                    showExternalCacheClear = false,
                    onClean = { _, _ -> },
                    onUndo = { _, _ -> },
                    onTrim = {},
                    onClearExternalCaches = {},
                    onOpenGroup = {},
                    onOpenTrash = {},
                    onBack = {},
                )
            }
        }
        compose.onNodeWithTag("androidTrashResult").assertExists()
        compose.onNodeWithTag("undo").assertDoesNotExist()
    }
}
