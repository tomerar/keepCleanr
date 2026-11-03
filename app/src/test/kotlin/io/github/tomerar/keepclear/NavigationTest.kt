// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** F1b: tabs, top app bars, Back, the Trash chip, restore without a scan, the rail on wide screens, headings. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h800dp")
class NavigationTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val now = 1_800_000_000_000L
    private val old = now - 400L * 86_400_000L

    private fun summary(): ClassificationSummary {
        val c = Classifier()

        fun i(rel: String) = c.classify(
            ScanItem(KPath.Local("/storage/emulated/0/$rel"), "/storage/emulated/0/$rel", 1000, old),
            now = now,
        )
        return ClassificationSummary.of(
            listOf(i("DCIM/.thumbnails/1.jpg"), i("Pictures/Screenshots/s.png"), i("DCIM/Camera/IMG.jpg")),
        )
    }

    private fun entry(size: Long) = TrashEntry(
        "a",
        "/storage/emulated/0/DCIM/a.jpg",
        "/storage/emulated/0/.KeepClearTrash/1/a/a.jpg",
        "/storage/emulated/0",
        size,
        1,
        reason = "TRASH",
        deletedAt = 1,
        expiresAt = 2,
        deletedAtElapsed = 0,
        bootId = "b",
        state = TrashState.ACTIVE,
    )

    private val heading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

    /** The app's screens with the real navigation rules ([Nav]) and a stand-in for Home. */
    private fun showApp(
        initial: HomeUiState,
        restore: RestoreActions = RestoreActions(),
        onOpenTrash: () -> Unit = {},
        onState: (HomeUiState) -> Unit = {},
    ) {
        compose.setContent {
            var state by remember { mutableStateOf(initial) }
            onState(state)
            KeepClearTheme {
                AppScreens(
                    state = state,
                    onOpen = { state = Nav.open(state, it) },
                    onBack = { state = Nav.back(state) },
                    home = {
                        HomeScreen(
                            state,
                            showDebugTools = false,
                            actions = HomeActions(
                                onOpenBackup = { state = Nav.selectTab(state, Tab.BACKUP) },
                                onOpenCategory = { state = Nav.open(state, Screen.CategoryList(it)) },
                            ),
                        )
                    },
                    moveActions = MoveActions(restore = restore),
                    tabs = TabsUi(
                        onSelectTab = { state = Nav.selectTab(state, it) },
                        onOpenTrash = {
                            onOpenTrash()
                            state = Nav.open(state, Screen.Trash)
                        },
                        onOpenSettings = { state = Nav.open(state, Screen.Settings) },
                    ),
                )
            }
        }
    }

    // --- Rules ---

    @Test
    fun `each tab keeps its own stack and Back at a tab's root goes to the first tab`() {
        var s = HomeUiState(summary = summary())
        s = Nav.open(s, Screen.CategoryList(Category.REVIEW))
        s = Nav.selectTab(s, Tab.APPS)
        assertEquals(listOf<Screen>(Screen.Apps), s.screens)
        s = Nav.selectTab(s, Tab.FREE)
        assertEquals(listOf(Screen.Home, Screen.CategoryList(Category.REVIEW)), s.screens)
        s = Nav.back(s)
        assertEquals(listOf<Screen>(Screen.Home), s.screens)
        // At the first tab's root Back is Android's (the app closes).
        assertFalse(Nav.handlesBack(s))
        s = Nav.selectTab(s, Tab.BACKUP)
        assertTrue(Nav.handlesBack(s))
        s = Nav.back(s)
        assertEquals(Tab.FREE, s.tab)
        assertEquals(listOf<Screen>(Screen.Home), s.screens)
    }

    @Test
    fun `the MOVE lane and the Apps screen open their tabs`() {
        val s = HomeUiState(summary = summary())
        assertEquals(Tab.BACKUP, Nav.open(s, Screen.CategoryList(Category.MOVE)).tab)
        assertEquals(Tab.APPS, Nav.open(s, Screen.Apps).tab)
        assertEquals(listOf<Screen>(Screen.Apps), Nav.open(s, Screen.Apps).screens)
    }

    @Test
    fun `selecting the shown tab again goes to its root and ends a group's selection (E4)`() {
        val key = GroupKey.of("screenshots", Category.REVIEW)
        var s = HomeUiState(summary = summary())
        s = Nav.open(s, Screen.CategoryList(Category.REVIEW))
        s = Nav.open(s, Screen.Group("screenshots", Category.REVIEW)).copy(selections = mapOf(key to setOf("/x")))
        // Another tab keeps the group open, with its selection.
        s = Nav.selectTab(s, Tab.APPS)
        assertEquals(setOf("/x"), s.selections[key])
        s = Nav.selectTab(s, Tab.FREE)
        assertEquals(3, s.screens.size)
        s = Nav.selectTab(s, Tab.FREE)
        assertEquals(listOf<Screen>(Screen.Home), s.screens)
        assertEquals(null, s.selections[key])
    }

    // --- Tabs on screen ---

    @Test
    fun `three tabs switch between Free up space, Backup and Apps`() {
        showApp(HomeUiState(summary = summary(), mediaAccess = MediaAccess.FULL))
        compose.onNodeWithTag(BOTTOM_NAV_TAG).assertIsDisplayed()
        compose.onNodeWithTag("homeScreen").assertIsDisplayed()
        compose.onNodeWithTag("tab_FREE").assertIsSelected()
        compose.onNodeWithTag("tab_BACKUP").performClick()
        compose.onNodeWithTag("moveScreen").assertIsDisplayed()
        compose.onNodeWithTag("tab_BACKUP").assertIsSelected()
        compose.onNodeWithTag("tab_APPS").performClick()
        compose.onNodeWithTag("apps").assertIsDisplayed()
        compose.onNodeWithTag("tab_FREE").performClick()
        compose.onNodeWithTag("homeScreen").assertIsDisplayed()
    }

    @Test
    fun `the MOVE card on Free up space jumps to the Backup tab`() {
        showApp(HomeUiState(summary = summary(), mediaAccess = MediaAccess.FULL))
        compose.onNodeWithTag("open_MOVE").performScrollTo().performClick()
        compose.onNodeWithTag("moveScreen").assertIsDisplayed()
        compose.onNodeWithTag("tab_BACKUP").assertIsSelected()
    }

    @Test
    fun `each tab keeps its state while another tab is shown`() {
        showApp(HomeUiState(summary = summary(), mediaAccess = MediaAccess.FULL))
        compose.onNodeWithTag("tab_BACKUP").performClick()
        compose.onNodeWithTag("age_ALL").performClick()
        compose.onNodeWithTag("source_camera").performClick()
        compose.onNodeWithTag("tab_APPS").performClick()
        compose.onNodeWithTag("apps").assertIsDisplayed()
        compose.onNodeWithTag("tab_BACKUP").performClick()
        compose.onNodeWithTag("age_ALL").assertIsSelected()
        compose.onNodeWithTag("source_camera").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ToggleableState,
                androidx.compose.ui.state.ToggleableState.On,
            ),
        )
        // A REVIEW list opened on the first tab is still open after visiting another tab.
        compose.onNodeWithTag("tab_FREE").performClick()
        compose.onNodeWithTag("open_REVIEW").performScrollTo().performClick()
        compose.onNodeWithTag("categoryList").assertIsDisplayed()
        compose.onNodeWithTag(BOTTOM_NAV_TAG).assertDoesNotExist()
    }

    // --- Trash chip ---

    @Test
    fun `an empty trash still has its chip, muted, and it opens the Trash (owner brief H2 item 3)`() {
        var opened = 0
        showApp(HomeUiState(), onOpenTrash = { opened++ })
        compose.onNodeWithTag("wordmark").assertIsDisplayed()
        compose.onNodeWithTag("openSettings").assertIsDisplayed()
        compose.onNodeWithTag("trashChip").assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(TrashChipEmpty, true))
            .assert(
                SemanticsMatcher("says the bin is empty, never 0B") {
                    val d = it.config.getOrElseNullable(SemanticsProperties.ContentDescription) { null }.orEmpty()
                    d == listOf("Recycle bin, empty")
                },
            )
        compose.onNodeWithTag("trashChip").performClick()
        assertEquals(1, opened)
        compose.onNodeWithTag("trash").assertIsDisplayed()
    }

    @Test
    fun `a non-empty trash chip is not muted`() {
        showApp(HomeUiState(trash = TrashUi(listOf(entry(258_000_000)))))
        compose.onNodeWithTag("trashChip").assert(SemanticsMatcher.expectValue(TrashChipEmpty, false))
    }

    @Test
    fun `the Trash chip shows the trash's size on every tab and opens the trash`() {
        var opened = 0
        showApp(HomeUiState(trash = TrashUi(listOf(entry(3_100_000_000)))), onOpenTrash = { opened++ })
        compose.onNodeWithTag("trashChip").assert(
            SemanticsMatcher("trash label with size") {
                it.config.getOrElseNullable(SemanticsProperties.ContentDescription) { null }.orEmpty().any { d ->
                    "Trash" in d &&
                        "3.1" in d
                }
            },
        )
        compose.onNodeWithTag("tab_BACKUP").performClick()
        compose.onNodeWithTag("trashChip").assertIsDisplayed()
        compose.onNodeWithTag("tab_APPS").performClick()
        compose.onNodeWithTag("trashChip").performClick()
        assertEquals(1, opened)
        compose.onNodeWithTag("trash").assertIsDisplayed()
    }

    @Test
    fun `items only in Android's trash also show the chip`() {
        val item =
            ScanItem(KPath.Local("/storage/emulated/0/DCIM/b.jpg"), "/storage/emulated/0/DCIM/b.jpg", 2_000_000_000, 1)
        assertEquals(2_000_000_000L, trashChipBytes(TrashUi(android = listOf(item))))
        assertEquals(null, trashChipBytes(TrashUi()))
    }

    // --- Sub-screens and Back ---

    @Test
    fun `a sub-screen has a back arrow and a heading title, and Back returns`() {
        showApp(HomeUiState())
        compose.onNodeWithTag("openSettings").performClick()
        compose.onNodeWithTag("settings").assertIsDisplayed()
        compose.onNodeWithTag(BOTTOM_NAV_TAG).assertDoesNotExist()
        compose.onNodeWithTag("screenTitle").assert(heading)
        compose.onNodeWithTag("back").performClick()
        compose.onNodeWithTag("homeScreen").assertIsDisplayed()
        compose.onNodeWithTag(BOTTOM_NAV_TAG).assertIsDisplayed()
    }

    @Test
    fun `system Back closes a sub-screen, then goes from another tab to the first`() {
        var last: HomeUiState? = null
        showApp(HomeUiState(), onState = { last = it })
        compose.onNodeWithTag("tab_APPS").performClick()
        compose.onNodeWithTag("openSettings").performClick()
        compose.onNodeWithTag("settings").assertIsDisplayed()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("apps").assertIsDisplayed()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("homeScreen").assertIsDisplayed()
        assertEquals(Tab.FREE, last?.tab)
        // At the first tab's root KeepClear lets Android handle Back.
        compose.runOnUiThread { assertFalse(compose.activity.onBackPressedDispatcher.hasEnabledCallbacks()) }
    }

    @Test
    fun `the main tab's wordmark is a heading`() {
        showApp(HomeUiState())
        compose.onNodeWithTag("wordmark").assert(heading)
    }

    // --- Restore without a scan ---

    @Test
    fun `restore from a backup works before any scan`() {
        var started = 0
        showApp(
            HomeUiState(summary = null, mediaAccess = MediaAccess.NONE),
            restore = RestoreActions(onStart = {
                started++
            }),
        )
        compose.onNodeWithTag("tab_BACKUP").performClick()
        compose.onNodeWithTag("movePending").assertIsDisplayed()
        compose.onNodeWithTag("restoreStart").performScrollTo().performClick()
        assertEquals(1, started)
    }

    // --- Wide screens ---

    @Test
    @Config(qualifiers = "w840dp-h600dp")
    fun `a wide screen shows a navigation rail instead of the bottom bar`() {
        showApp(HomeUiState(summary = summary()))
        compose.onNodeWithTag(NAV_RAIL_TAG).assertIsDisplayed()
        compose.onNodeWithTag(BOTTOM_NAV_TAG).assertDoesNotExist()
        compose.onNodeWithTag("tab_BACKUP").performClick()
        compose.onNodeWithTag("moveScreen").assertIsDisplayed()
    }
}
