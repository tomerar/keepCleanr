// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import io.github.tomerar.keepclear.core.designsystem.KeepClearTheme
import io.github.tomerar.keepclear.core.model.Category
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.engine.classify.AppContent
import io.github.tomerar.keepclear.engine.classify.ClassificationSummary
import io.github.tomerar.keepclear.engine.classify.Classifier
import io.github.tomerar.keepclear.engine.classify.Total
import io.github.tomerar.keepclear.engine.scan.ScanItem
import io.github.tomerar.keepclear.engine.trash.TrashEntry
import io.github.tomerar.keepclear.engine.trash.TrashState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** M11: SAFE-9 app folders, SAFE-10 one app at a time, TRASH-7 uninstall note. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class M11Test {
    @get:Rule
    val compose = createComposeRule()

    private fun summary(): ClassificationSummary {
        val c = Classifier()

        fun i(rel: String, size: Long = 1000) = c.classify(
            ScanItem(KPath.Local("/storage/emulated/0/$rel"), "/storage/emulated/0/$rel", size, 1),
            now = 2,
        )
        return ClassificationSummary.of(
            listOf(i("Android/media/com.spotify.music/d/a.ogg", 5000), i("Android/media/org.example/x.bin")),
        )
    }

    @Test
    fun `app folders open a per-app screen with no delete action (SAFE-9)`() {
        var info: String? = null
        val state =
            HomeUiState(summary = summary(), screens = listOf(Screen.Home, Screen.CategoryList(Category.REVIEW)))
        compose.setContent {
            KeepClearTheme {
                val s = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(state) }
                AppScreens(
                    state = s.value,
                    onOpen = { screen -> s.value = s.value.copy(screens = s.value.screens + screen) },
                    onBack = {},
                    home = {},
                    appActions = AppActions(labelOf = {
                        if (it ==
                            "com.spotify.music"
                        ) {
                            "Spotify"
                        } else {
                            it
                        }
                    }, onOpenAppInfo = { info = it }),
                )
            }
        }
        compose.onNodeWithTag("group_${AppContent.RULE_ID}").performClick()
        compose.onNodeWithTag("appContent").assertExists()
        compose.onNodeWithTag("app_com.spotify.music").assertExists()
        assertEquals(true, compose.onAllNodesWithText("Spotify", substring = true).fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithTag("appInfo_com.spotify.music").performClick()
        assertEquals("com.spotify.music", info)
        assertEquals(0, compose.onAllNodesWithTag("deleteSelected").fetchSemanticsNodes().size)
    }

    @Test
    fun `the app-folder group is never selectable in the grid (SAFE-9)`() {
        val group = summary().group(AppContent.RULE_ID, Category.REVIEW)!!
        compose.setContent { KeepClearTheme { GroupScreen(group, onOpenItem = {}, onBack = {}) } }
        compose.onNodeWithTag("selectAll").assertDoesNotExist()
        compose.onAllNodesWithTag("tile")[0].performClick()
        compose.onNodeWithTag("deleteSelected").assertDoesNotExist()
    }

    @Test
    fun `one app at a time shows the measured result per app (SAFE-10)`() {
        var opened: String? = null
        compose.setContent {
            KeepClearTheme {
                OneAppAtATimeScreen(
                    apps = listOf(AppEntry("a.b", "Alpha"), AppEntry("c.d", "Delta")),
                    freed = mapOf("a.b" to 120_000_000L, "c.d" to 0L),
                    actions = AppActions(onOpenAppInfo = { opened = it }),
                    onBack = {},
                )
            }
        }
        compose.onNodeWithTag("freed_a.b").assertExists()
        compose.onNodeWithTag("freed_c.d").assertExists()
        compose.onNodeWithTag("openInfo_c.d").performClick()
        assertEquals("c.d", opened)
    }

    @Test
    fun `the trash shows the uninstall note with its size (TRASH-7)`() {
        val e = TrashEntry(
            "a",
            "/storage/emulated/0/DCIM/a.jpg",
            "/storage/emulated/0/.KeepClearTrash/1/a/a.jpg",
            "/storage/emulated/0",
            3_100_000_000,
            1,
            reason = "TRASH",
            deletedAt = 1,
            expiresAt = 2,
            deletedAtElapsed = 0,
            bootId = "b",
            state = TrashState.ACTIVE,
        )
        compose.setContent {
            KeepClearTheme {
                TrashScreen(TrashUi(listOf(e)), onRestore = {
                    _,
                    _,
                    ->
                }, onPurge = { _, _ -> }, onBack = {})
            }
        }
        compose.onNodeWithTag("uninstallNote").assertExists()
    }

    @Test
    fun `settings warns before uninstall while the trash holds files (TRASH-7)`() {
        var opened = 0
        compose.setContent {
            KeepClearTheme {
                SettingsScreen(true, {
                }, {}, {}, SettingsActions(trashBytes = 3_100_000_000, onOpenTrash = { opened++ }))
            }
        }
        compose.onNodeWithTag("settingsOpenTrash").performScrollTo().performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `Home's temporary-files card leads to the per-app list (SAFE-5 to SAFE-10)`() {
        var oneByOne = 0
        compose.setContent {
            KeepClearTheme {
                HomeScreen(HomeUiState(), showDebugTools = false, actions = HomeActions(onOneByOne = { oneByOne++ }))
            }
        }
        compose.onNodeWithTag("oneByOneLink").performScrollTo().performClick()
        assertEquals(1, oneByOne)
    }
}
