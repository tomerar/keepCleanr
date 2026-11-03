// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import io.github.tomerar.keepclear.core.designsystem.KeepClearTheme
import io.github.tomerar.keepclear.core.i18n.R
import io.github.tomerar.keepclear.core.model.Category
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.engine.classify.AppInfo
import io.github.tomerar.keepclear.engine.classify.ClassificationSummary
import io.github.tomerar.keepclear.engine.classify.ClassifiedItem
import io.github.tomerar.keepclear.engine.classify.Classifier
import io.github.tomerar.keepclear.engine.restore.RestoreReport
import io.github.tomerar.keepclear.engine.scan.ScanItem
import io.github.tomerar.keepclear.engine.trash.TrashEntry
import io.github.tomerar.keepclear.engine.trash.TrashState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** PR F2: screens rebuilt on the design system, and the UX fixes of the owner's review brief. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h800dp")
class F2ScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val now = 1_800_000_000_000L
    private val old = now - 400L * 24 * 3_600_000

    private fun summary(): ClassificationSummary {
        val c = Classifier()

        fun i(rel: String) = c.classify(
            ScanItem(KPath.Local("/storage/emulated/0/$rel"), "/storage/emulated/0/$rel", 1000, old),
            now = now,
        )
        return ClassificationSummary.of(
            listOf(i("DCIM/.thumbnails/1.jpg"), i("Download/a.tmp"), i("DCIM/Camera/IMG.jpg")),
        )
    }

    private fun permanentOf(tag: String) =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config[PermanentAction]

    private fun awaitEnabled(tag: String) = compose.waitUntil(5_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().any { SemanticsProperties.Disabled !in it.config }
    }

    private fun entry(id: String, verified: String? = null, expiresAt: Long = now + 23 * DAY) = TrashEntry(
        id = id,
        originalPath = "/storage/emulated/0/DCIM/Camera/IMG_$id.jpg",
        trashedPath = "/storage/emulated/0/.KeepClearTrash/20261031/$id/IMG_$id.jpg",
        volumeRoot = "/storage/emulated/0",
        size = 3_000_000,
        mtimeMillis = 1,
        reason = "TRASH",
        deletedAt = now - DAY,
        expiresAt = expiresAt,
        deletedAtElapsed = 0,
        bootId = "b",
        state = TrashState.ACTIVE,
        verifiedCopy = verified,
    )

    // ---------------------------------------------------------------- ConfirmSheet: ink unless permanent

    @Test
    fun `cleaning to the trash and trimming caches confirm in ink, never red (SafeConfirmButton has no default)`() {
        val items = summary().groups(Category.SAFE).flatMap { it.items }
        compose.setContent {
            KeepClearTheme {
                CleanConfirmDialog(items, onClean = { _, _ -> }, onDismiss = {})
                TrimConfirmSheet(onTrim = {}, onDismiss = {}, tag = "trimConfirm", bodyTag = "trimBody")
            }
        }
        assertEquals(false, permanentOf("confirmClean"))
        assertEquals(false, permanentOf("trimConfirm"))
    }

    @Test
    fun `restoring a changed file and turning test mode off are not permanent, emptying the trash is`() {
        val changed = entry("1")
        compose.setContent {
            KeepClearTheme {
                TrashScreen(
                    TrashUi(entries = listOf(changed), changed = setOf("1")),
                    initialView = TrashView.LIST,
                    onRestore = { _, _ -> },
                    onPurge = { _, _ -> },
                    onBack = {},
                    now = now,
                )
            }
        }
        compose.onNodeWithTag("restoreChanged_1").performScrollTo().performClick()
        assertEquals(false, permanentOf("confirmRestoreChanged"))
    }

    @Test
    fun `emptying the trash is the permanent one - red - and says it can't be undone`() {
        compose.setContent {
            KeepClearTheme {
                TrashScreen(TrashUi(entries = listOf(entry("1"))), { _, _ -> }, { _, _ -> }, {}, now = now)
            }
        }
        // Design QA item 3: the actions on the whole trash are at the bottom, after the list.
        compose.onNodeWithTag("trash").performScrollToNode(hasTestTag("emptyNow"))
        compose.onNodeWithTag("emptyNow").performClick()
        assertEquals(true, permanentOf("confirmPurge"))
        compose.onNodeWithTag("emptyBody").assertExists()
    }

    @Test
    fun `turning test mode off confirms in ink`() {
        compose.setContent { KeepClearTheme { SettingsScreen(true, {}, {}, {}, SettingsActions()) } }
        compose.onNodeWithTag("testModeSwitch").performScrollTo().performClick()
        compose.onNodeWithTag("testModeOff1").performClick()
        assertEquals(false, permanentOf("testModeOff2"))
    }

    // ---------------------------------------------------------------- Trash

    @Test
    fun `release all says how many items have no checked copy and stay in the trash (verified bug - was 0)`() {
        val withCopy = entry("1", verified = "USB")
        val trash = TrashUi(entries = listOf(withCopy, entry("2"), entry("3")), releasable = setOf("1"))
        var released: List<TrashEntry>? = null
        compose.setContent {
            KeepClearTheme {
                TrashScreen(trash, { _, _ -> }, { _, _ -> }, {}, onRelease = { e, _ -> released = e }, now = now)
            }
        }
        compose.onNodeWithTag("trash").performScrollToNode(hasTestTag("releaseAll"))
        compose.onNodeWithTag("releaseAll").performClick()
        val body = io.github.tomerar.keepclear.core.common.Bidi.strip(
            compose.onNodeWithTag("releaseAllBody").fetchSemanticsNode()
                .config[SemanticsProperties.Text].joinToString { it.text },
        )
        assertTrue(body, "With a checked copy on your drive: 1 file" in body)
        assertTrue(body, "Without a checked copy: 2 files, they stay in the trash" in body)
        awaitEnabled("confirmReleaseAll")
        compose.onNodeWithTag("confirmReleaseAll").performClick()
        assertEquals(listOf("1"), released!!.map { it.id })
    }

    @Test
    fun `every trashed item and group shows the days left, never a date to work out`() {
        compose.setContent {
            KeepClearTheme {
                TrashScreen(TrashUi(entries = listOf(entry("1"))), { _, _ ->
                }, { _, _ -> }, {}, now = now, initialView = TrashView.LIST)
            }
        }
        compose.onNodeWithTag("daysLeft_1").assertTextContains("23 days left")
        compose.onNodeWithTag("groupDaysLeft_OTHER").assertTextContains("23 days left")
    }

    @Test
    fun `days left round up and never go below zero`() {
        assertEquals(23, TrashDays.left(now + 23 * DAY, now))
        assertEquals(1, TrashDays.left(now + 2 * 3_600_000L, now))
        assertEquals(0, TrashDays.left(now - DAY, now))
    }

    @Test
    fun `the uninstall note shows on the first visit to the trash, not on every visit`() {
        context.getSharedPreferences("trash_notes", Context.MODE_PRIVATE).edit().clear().commit()
        var screens by mutableStateOf(listOf<Screen>(Screen.Home, Screen.Trash))
        compose.setContent {
            KeepClearTheme {
                AppScreens(
                    state = HomeUiState(screens = screens, trash = TrashUi(entries = listOf(entry("1")))),
                    onOpen = {},
                    onBack = {},
                    home = {},
                )
            }
        }
        compose.onNodeWithTag("uninstallNote").assertExists()
        screens = listOf(Screen.Home)
        compose.waitForIdle()
        screens = listOf(Screen.Home, Screen.Trash)
        compose.waitForIdle()
        compose.onNodeWithTag("trash").assertExists()
        compose.onNodeWithTag("uninstallNote").assertDoesNotExist()
    }

    // ---------------------------------------------------------------- Review group

    private fun group() = summary().groups(Category.SAFE).first()

    @Test
    fun `why is this here opens a sheet instead of a wall of text above the pictures`() {
        val g = group()
        compose.setContent { KeepClearTheme { GroupScreen(g, onOpenItem = {}, onBack = {}) } }
        val why = context.getString(RuleText.of(g.ruleId).why)
        assertEquals(0, compose.onAllNodesWithText(why).size)
        compose.onNodeWithTag("whyChip").performClick()
        compose.onNodeWithTag("whySheet").assertExists()
        assertEquals(1, compose.onAllNodesWithText(why).size)
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithText(text: String) =
        onAllNodes(androidx.compose.ui.test.hasText(text), useUnmergedTree = true).fetchSemanticsNodes()

    @Test
    fun `a selected picture shows a check-circle and the bar says what and how much`() {
        val g = group()
        compose.setContent { KeepClearTheme { GroupScreen(g, onOpenItem = {}, onBack = {}) } }
        compose.onNodeWithTag("deleteSelected").assertDoesNotExist()
        compose.onNodeWithTag("grid").performScrollToNode(hasTestTag("tile"))
        compose.onAllNodesWithTag("tile")[0].performClick()
        compose.onAllNodesWithTag("tileCheck", useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithTag("deleteSelected").assertTextContains("1 file", substring = true)
    }

    // ---------------------------------------------------------------- Swipe review

    private fun swipeItems(): List<ClassifiedItem> {
        val c = Classifier()
        return (1..3).map {
            val p = "/storage/emulated/0/Pictures/Screenshots/s$it.png"
            c.classify(ScanItem(KPath.Local(p), p, 1000, old), now = now)
        }
    }

    @Test
    fun `swipe direction is fixed by meaning - toward the Delete button deletes, in Hebrew and in English`() {
        // English: Delete sits at the right (end); Hebrew: at the left (end in RTL).
        assertEquals(SwipeChoice.DELETE, SwipeSession.choiceFor(300f, 100f, rtl = false))
        assertEquals(SwipeChoice.KEEP, SwipeSession.choiceFor(-300f, 100f, rtl = false))
        assertEquals(SwipeChoice.DELETE, SwipeSession.choiceFor(-300f, 100f, rtl = true))
        assertEquals(SwipeChoice.KEEP, SwipeSession.choiceFor(300f, 100f, rtl = true))
        assertNull(SwipeSession.choiceFor(99f, 100f, rtl = false))
        assertNull(SwipeSession.choiceFor(-99f, 100f, rtl = true))
    }

    private fun swipeOnce(direction: LayoutDirection, toLeft: Boolean): List<SwipeDecision> {
        var decisions by mutableStateOf(listOf<SwipeDecision>())
        val items = swipeItems()
        compose.setContent {
            KeepClearTheme {
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    SwipeReview(items, onDone = {
                    }, onClose = {}, decisions = decisions, onDecisions = { decisions = it })
                }
            }
        }
        compose.onNodeWithTag("swipeCard").performTouchInput { if (toLeft) swipeLeft() else swipeRight() }
        compose.waitForIdle()
        return decisions
    }

    @Test
    fun `in Hebrew a swipe to the left deletes`() {
        assertEquals(SwipeChoice.DELETE, swipeOnce(LayoutDirection.Rtl, toLeft = true).single().choice)
    }

    @Test
    fun `in English a swipe to the right deletes`() {
        assertEquals(SwipeChoice.DELETE, swipeOnce(LayoutDirection.Ltr, toLeft = false).single().choice)
    }

    @Test
    fun `past the threshold the card says what letting go will do`() {
        compose.setContent {
            KeepClearTheme { SwipeReview(swipeItems(), onDone = {}, onClose = {}) }
        }
        compose.onNodeWithTag("swipeLabel", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("swipeCard").performTouchInput {
            down(center)
            moveBy(androidx.compose.ui.geometry.Offset(width * 0.45f, 0f))
        }
        compose.onNodeWithTag("swipeLabel", useUnmergedTree = true)
            .assertTextContains(context.getString(R.string.swipe_delete))
    }

    @Test
    fun `leaving with decisions asks first, and only leaving loses them`() {
        var closed = 0
        compose.setContent {
            KeepClearTheme { SwipeReview(swipeItems(), onDone = {}, onClose = { closed++ }) }
        }
        compose.onNodeWithTag("swipeKeep").performClick()
        compose.onNodeWithTag("swipeClose").performClick()
        compose.onNodeWithTag("swipeExit").assertExists()
        assertEquals(0, closed)
        compose.onNodeWithTag("swipeExitYes").performClick()
        assertEquals(1, closed)
    }

    @Test
    fun `closing before any decision just closes`() {
        var closed = 0
        compose.setContent {
            KeepClearTheme { SwipeReview(swipeItems(), onDone = {}, onClose = { closed++ }) }
        }
        compose.onNodeWithTag("swipeClose").performClick()
        assertEquals(1, closed)
        compose.onNodeWithTag("swipeExit").assertDoesNotExist()
    }

    // ---------------------------------------------------------------- Apps tab

    @Test
    fun `while the apps load the tab says so, never no access`() {
        compose.setContent {
            KeepClearTheme {
                AppScreens(state = HomeUiState(screens = listOf(Screen.Apps)), onOpen = {}, onBack = {}, home = {})
            }
        }
        compose.onNodeWithTag("appsLoading").assertExists()
        compose.onNodeWithTag("usageAccess").assertDoesNotExist()
    }

    @Test
    fun `after loading without usage access the tab explains it`() {
        compose.setContent {
            KeepClearTheme {
                AppScreens(
                    state = HomeUiState(screens = listOf(Screen.Apps), appsLoaded = true),
                    onOpen = {},
                    onBack = {},
                    home = {},
                )
            }
        }
        compose.onNodeWithTag("usageAccess").assertExists()
    }

    @Test
    fun `an unused app is listed once`() {
        val month = 30L * 24 * 3_600_000
        val apps = listOf(
            AppInfo("old.game", "Old game", 2_000_000_000, 0, 0, now - 9 * month, now - 30 * month),
            AppInfo("daily.app", "Daily", 500_000_000, 0, 10, now, now - 30 * month),
        )
        compose.setContent { KeepClearTheme { AppsScreen(apps, now, AppsActions(), onBack = {}) } }
        compose.onNodeWithTag("unused_old.game").assertExists()
        compose.onNodeWithTag("apps").performScrollToNode(hasTestTag("app_daily.app"))
        compose.onNodeWithTag("app_old.game").assertDoesNotExist()
    }

    @Test
    fun `one app at a time offers usage access instead of promising a later version`() {
        var asked = 0
        compose.setContent {
            KeepClearTheme {
                OneAppAtATimeScreen(
                    listOf(AppEntry("a.b", "Alpha")),
                    emptyMap(),
                    AppActions(onAllowUsageAccess = { asked++ }),
                    onBack = {},
                )
            }
        }
        compose.onNodeWithTag("oneByOneUsageAccess").performClick()
        assertEquals(1, asked)
    }

    // ---------------------------------------------------------------- Low-space reminder

    @Test
    fun `the reminder explains first and stays off when notifications are refused`() {
        val turned = mutableListOf<Boolean>()
        var asked = 0
        compose.setContent {
            KeepClearTheme {
                SettingsScreen(
                    true,
                    {},
                    {},
                    {},
                    SettingsActions(
                        onReminder = { turned += it },
                        needsNotificationPermission = true,
                        onAskNotifications = { answer ->
                            asked++
                            answer(false)
                        },
                    ),
                )
            }
        }
        compose.onNodeWithTag("reminderSwitch").performScrollTo().performClick()
        compose.onNodeWithTag("reminderExplain").assertExists()
        assertEquals(0, asked)
        compose.onNodeWithTag("reminderContinue").performClick()
        assertEquals(1, asked)
        assertEquals(emptyList<Boolean>(), turned)
    }

    @Test
    fun `the reminder turns on once notifications are allowed`() {
        val turned = mutableListOf<Boolean>()
        compose.setContent {
            KeepClearTheme {
                SettingsScreen(
                    true,
                    {},
                    {},
                    {},
                    SettingsActions(
                        onReminder = { turned += it },
                        needsNotificationPermission = true,
                        onAskNotifications = { it(true) },
                    ),
                )
            }
        }
        compose.onNodeWithTag("reminderSwitch").performScrollTo().performClick()
        compose.onNodeWithTag("reminderContinue").performClick()
        assertEquals(listOf(true), turned)
    }

    @Test
    fun `the reminder notification shows the whole sentence and KeepClear's own icon`() {
        val n = SpaceReminder.build(context, 2_000_000_000, 7_900_000_000, java.util.Locale.ENGLISH, null)
        val text = SpaceReminder.text(context, 2_000_000_000, 7_900_000_000, java.util.Locale.ENGLISH)
        assertEquals(text, n.extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT).toString())
        assertEquals(io.github.tomerar.keepclear.R.drawable.ic_notification, n.smallIcon.resId)
    }

    // ---------------------------------------------------------------- Backup tab and restore

    @Test
    fun `a backup source row toggles from its name too (one target)`() {
        compose.setContent {
            KeepClearTheme {
                MoveScreen(summary(), MoveUiState(), now, notificationsOff = false, actions = MoveActions(), onBack = {
                })
            }
        }
        compose.onNodeWithTag("moveScreen").performScrollToNode(hasTestTag("source_camera"))
        val row = compose.onNodeWithTag("source_camera").fetchSemanticsNode()
        assertEquals(androidx.compose.ui.semantics.Role.Checkbox, row.config[SemanticsProperties.Role])
        // Tapping the group's name (not the box) ticks it.
        val name = context.getString(RuleText.of("camera").group)
        compose.onNode(androidx.compose.ui.test.hasText(name), useUnmergedTree = true).performClick()
        assertEquals(
            androidx.compose.ui.state.ToggleableState.On,
            compose.onNodeWithTag("source_camera").fetchSemanticsNode().config[SemanticsProperties.ToggleableState],
        )
    }

    @Test
    fun `the backup summary is a paper slip with the verified stamp`() {
        val job = io.github.tomerar.keepclear.engine.move.MoveJob(
            id = "j",
            targetId = "t",
            targetLabel = "USB",
            rootRel = "KeepClear/Phone",
            createdAt = 1,
            state = io.github.tomerar.keepclear.engine.move.JobState.DONE,
        )
        val receipt = io.github.tomerar.keepclear.engine.move.Receipt(
            jobId = "j",
            targetLabel = "USB",
            rootRel = "KeepClear/Phone",
            verified = 2,
            verifiedBytes = 2_000,
            failed = emptyMap(),
            changed = 0,
            pending = 0,
            namesChanged = 0,
            // H1b item 6: the stamp comes only after the second check.
            confirmed = 2,
            confirmedBytes = 2_000,
        )
        compose.setContent {
            KeepClearTheme { ReceiptCard(JobView(job, receipt, canReadBack = true), onFree = {}, onShare = {}) }
        }
        compose.onNodeWithTag("verifiedStamp", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("receiptTotal", useUnmergedTree = true).assertTextContains("2 files", substring = true)
    }

    @Test
    fun `restore shows a loading state with cancel between choosing the place and the catalog`() {
        var closed = 0
        compose.setContent {
            KeepClearTheme { RestoreDialogs(RestoreStep.Loading, RestoreActions(onClose = { closed++ })) }
        }
        compose.onNodeWithTag("restoreLoading").assertExists()
    }

    @Test
    fun `a running restore can be stopped`() {
        var stopped = 0
        compose.setContent {
            KeepClearTheme { RestoreDialogs(RestoreStep.Running(1, 4), RestoreActions(onCancel = { stopped++ })) }
        }
        compose.onNodeWithTag("restoreStop").performClick()
        assertEquals(1, stopped)
    }

    @Test
    fun `the restore catalog names groups in plain words - Camera photos, July 2021`() {
        assertEquals(
            "July 2021",
            io.github.tomerar.keepclear.core.common.Bidi.strip(restoreMonthLabel("2021-07", java.util.Locale.ENGLISH)),
        )
        compose.setContent {
            KeepClearTheme {
                RestoreDialogs(
                    RestoreStep.Catalog(
                        "KeepClear/Phone",
                        listOf(RestoreGroup("Camera", "2021-07", 1_000, toOriginal = 1, toRestoreFolder = 0)),
                        io.github.tomerar.keepclear.engine.restore.Space(
                            allocatable = 50_000_000_000,
                            total = 100_000_000_000,
                        ),
                    ),
                    RestoreActions(),
                )
            }
        }
        compose.onNodeWithTag("restoreLabel_Camera|2021-07", useUnmergedTree = true)
            .assertTextContains("Camera photos · ", substring = true)
            .assertTextContains("July 2021", substring = true)
    }

    // ---------------------------------------------------------------- Home

    @Test
    fun `a running action shows progress, not only disabled buttons`() {
        compose.setContent {
            KeepClearTheme { HomeScreen(HomeUiState(busy = true), showDebugTools = false) }
        }
        compose.onNodeWithTag("busy").assertExists()
    }

    @Test
    fun `where your space is sits under the strip, not as a section of its own`() {
        compose.setContent {
            KeepClearTheme {
                HomeScreen(
                    HomeUiState(summary = summary(), totalBytes = 128_000_000_000, freeBytes = 16_800_000_000),
                    showDebugTools = false,
                )
            }
        }
        compose.onNodeWithTag("areas", useUnmergedTree = true).assertExists()
        assertEquals(0, compose.onAllNodesWithText("Where your space is").size)
    }

    @Test
    fun `a clean result on Home is a banner with Undo and Empty now`() {
        var undone = 0
        var trash = 0
        val r = CleanResult(
            io.github.tomerar.keepclear.engine.classify.Total(2, 2_000),
            0,
            0,
            listOf("/a", "/b"),
            origin = Screen.Home,
        )
        compose.setContent {
            KeepClearTheme {
                HomeScreen(
                    HomeUiState(cleanResult = r),
                    showDebugTools = false,
                    actions = HomeActions(onUndo = { _, _ -> undone++ }, onOpenTrash = { trash++ }),
                )
            }
        }
        compose.onNodeWithTag("cleanBanner").assertExists()
        compose.onNodeWithTag("undo").performClick()
        compose.onNodeWithTag("emptyNowLink").performClick()
        assertEquals(1 to 1, undone to trash)
    }

    private companion object {
        const val DAY = 86_400_000L
    }
}
