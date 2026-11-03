// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import io.github.tomerar.keepclear.core.common.ByteSize
import io.github.tomerar.keepclear.core.designsystem.KeepClearTheme
import io.github.tomerar.keepclear.core.designsystem.component.CONFIRM_SHEET_FADE_TAG
import io.github.tomerar.keepclear.core.designsystem.component.CONFIRM_SHEET_RISK_TAG
import io.github.tomerar.keepclear.core.designsystem.component.StripSegment
import io.github.tomerar.keepclear.core.model.Category
import io.github.tomerar.keepclear.core.storage.media.MediaAccess
import io.github.tomerar.keepclear.engine.classify.Total
import io.github.tomerar.keepclear.engine.move.JobState
import io.github.tomerar.keepclear.engine.move.MoveJob
import io.github.tomerar.keepclear.engine.move.PauseReason
import io.github.tomerar.keepclear.engine.move.Receipt
import io.github.tomerar.keepclear.engine.scan.SpaceMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

/** Owner brief H2 (PR H2a): intro, first-run Home, Trash chip, legend, clean confirm, after clean, REVIEW, empty states. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h800dp")
class H2aHomeTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) = compose.setContent {
        KeepClearTheme(reducedMotion = true) {
            androidx.compose.runtime.CompositionLocalProvider(LocalClock provides { OwnerPhone.NOW }) { content() }
        }
    }

    private fun home(state: HomeUiState, actions: HomeActions = HomeActions()) = show {
        AppScreens(state = state, onOpen = {}, onBack = {}, home = { HomeScreen(state, false, actions = actions) })
    }

    private fun description(tag: String): String =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)
            .orEmpty().joinToString()

    private fun gb(bytes: Long) = io.github.tomerar.keepclear.core.common.Bidi.strip(
        ByteSize.format(bytes, Locale.ENGLISH),
    )

    private fun plain(s: String) = io.github.tomerar.keepclear.core.common.Bidi.strip(s)

    // ------------------------------------------------------------------------------------------- 1 intro

    @Test
    fun `the intro has dots that TalkBack reads as 1 of 3, and a picture per page`() {
        show { IntroScreen({}) }
        assertEquals("1 of 3", description("introPage"))
        compose.onNodeWithTag("introArt_STRIP").assertIsDisplayed()
        compose.onNodeWithTag("introNext").performClick()
        assertEquals("2 of 3", description("introPage"))
        compose.onNodeWithTag("introArt_CHOOSE").assertIsDisplayed()
        compose.onNodeWithTag("introNext").performClick()
        assertEquals("3 of 3", description("introPage"))
        compose.onNodeWithTag("introArt_BACKUP").assertIsDisplayed()
        compose.onNodeWithText("3 / 3").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "iw-w360dp-h800dp")
    fun `in Hebrew the dots read 1 out of 3`() {
        show { IntroScreen({}) }
        assertEquals("1 מתוך 3", description("introPage"))
    }

    @Test
    fun `the trash promise is honest, most things wait 30 days, what rebuilds itself goes at once`() {
        show { IntroScreen({}) }
        compose.onNodeWithTag("introNext").performClick()
        compose.onNodeWithText(
            "Most of what you clean waits 30 days in the recycle bin. Files that rebuild themselves are deleted " +
                "right away.",
        ).assertIsDisplayed()
        compose.onNodeWithText("Everything can come back").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "iw-w360dp-h800dp")
    fun `the Hebrew trash promise is the owner's sentence`() {
        show { IntroScreen({}) }
        compose.onNodeWithTag("introNext").performClick()
        compose.onNodeWithText("רוב מה שמנקים מחכה 30 יום בסל המחזור. קבצים שנבנים מחדש לבד נמחקים מיד.")
            .assertIsDisplayed()
    }

    @Test
    fun `New phone - restore from a backup opens the restore flow, not Home`() {
        var restored = 0
        var done = 0
        show { IntroScreen(onDone = { done++ }, onRestore = { restored++ }) }
        compose.onNodeWithTag("introRestore").performScrollTo().assertIsDisplayed()
            .assert(hasText("New phone? Restore from a backup"))
        compose.onNodeWithTag("introRestore").performClick()
        assertEquals(1, restored)
        assertEquals(0, done)
    }

    // ------------------------------------------------------------------------------------------- 2 first run

    @Test
    fun `first-run Home has ONE access card and the lanes say they show after the permission`() {
        home(OwnerPhone.beforeAccess)
        assertEquals(1, compose.onAllNodesWithTag("allowAccess").fetchSemanticsNodes().size)
        compose.onAllNodesWithText("Needs access", substring = true).fetchSemanticsNodes().let {
            assertEquals("no \"Needs access\" left", 0, it.size)
        }
        // SAFE (in the hero), REVIEW and MOVE.
        assertEquals(3, compose.onAllNodesWithText("Shows after you allow access").fetchSemanticsNodes().size)
        listOf("moreFilesOffer", "moreFiles", "trimCard", "allowAllFiles", "pickWhatsApp").forEach {
            compose.onNodeWithTag(it).assertDoesNotExist()
        }
    }

    @Test
    fun `after the first scan the WhatsApp step is offered in context, on foss through All files access`() {
        var explained = 0
        val state = HomeUiState(
            totalBytes = OwnerPhone.TOTAL_BYTES,
            freeBytes = OwnerPhone.FREE_BYTES,
            mediaAccess = MediaAccess.FULL,
            allFiles = false,
            scan = ScanState.Done(10, 10, true),
            summary = OwnerPhone.summary,
        )
        home(state, HomeActions(onRequestAllFiles = { explained++ }))
        compose.onNodeWithTag("moreFilesOffer").assertIsDisplayed()
        compose.onNodeWithText("Find WhatsApp photos and documents too?").assertIsDisplayed()
        // It sits right under the scan line, above the lanes (not at the bottom).
        val offer = compose.onNodeWithTag("moreFilesOffer").getUnclippedBoundsInRoot()
        val review = compose.onNodeWithTag("total_REVIEW").getUnclippedBoundsInRoot()
        assertTrue("offer $offer, review $review", offer.bottom <= review.top)
        compose.onNodeWithTag("pickWhatsApp").assertDoesNotExist()
        compose.onNodeWithTag("allowAllFiles").performClick()
        compose.onNodeWithTag("explainerContinue").performClick()
        assertEquals(1, explained)
    }

    @Test
    fun `while scanning, or with All files access, there is no offer`() {
        home(OwnerPhone.home.copy(allFiles = false, scan = ScanState.Running(5, 5)))
        compose.onNodeWithTag("moreFilesOffer").assertDoesNotExist()
    }

    @Test
    fun `with All files access already there is no offer`() {
        home(OwnerPhone.home)
        compose.onNodeWithTag("moreFilesOffer").assertDoesNotExist()
    }

    @Test
    fun `on play the offer explains what works without All files access and picks WhatsApp's folder`() {
        home(OwnerPhone.home.copy(allFiles = null))
        compose.onNodeWithTag("moreFilesOffer").assertIsDisplayed()
        compose.onNodeWithText(
            "This version of KeepClear doesn't ask for access to all files. You can choose WhatsApp's folder, " +
                "and KeepClear will look only there.",
        ).assertIsDisplayed()
        compose.onNodeWithTag("allowAllFiles").assertDoesNotExist()
        compose.onNodeWithTag("pickWhatsApp").assertIsDisplayed()
        compose.onNodeWithTag("moreFilesNotNow").performClick()
        compose.onNodeWithTag("moreFilesOffer").assertDoesNotExist()
    }

    // ------------------------------------------------------------------------------------------- 3 trash chip

    @Test
    fun `the Trash chip has a word and the size`() {
        home(OwnerPhone.home.copy(trash = OwnerPhone.trash))
        val bytes = trashChipBytes(OwnerPhone.trash)!!
        compose.onNodeWithTag("trashChip").assert(
            SemanticsMatcher("label") {
                it.config.getOrNull(TrashChipLabel)?.let { l ->
                    l.startsWith("Trash · ") && gb(bytes) in plain(l)
                } == true
            },
        )
    }

    @Test
    @Config(qualifiers = "iw-w360dp-h800dp")
    fun `in Hebrew the chip reads the word for bin with the size`() {
        home(OwnerPhone.home.copy(trash = OwnerPhone.trash))
        compose.onNodeWithTag("trashChip").assert(
            SemanticsMatcher("label") { it.config.getOrNull(TrashChipLabel)?.startsWith("סל · ") == true },
        )
    }

    @Test
    fun `an empty trash's chip says Trash, never 0B`() {
        home(OwnerPhone.home)
        compose.onNodeWithTag("trashChip").assert(SemanticsMatcher.expectValue(TrashChipLabel, "Trash"))
    }

    // ------------------------------------------------------------------------------------------- 4 legend, one button

    @Test
    fun `the legend uses nouns`() {
        home(OwnerPhone.home)
        val texts = StripSegment.entries.mapNotNull { seg ->
            compose.onAllNodesWithTag("legend_${seg.name}", useUnmergedTree = true).fetchSemanticsNodes()
                .singleOrNull()?.let { n ->
                    compose.onNodeWithTag("legend_${seg.name}", useUnmergedTree = true).fetchSemanticsNode()
                        .config.getOrNull(SemanticsProperties.Text)?.joinToString()
                        ?: n.children.flatMap { c -> c.config.getOrNull(SemanticsProperties.Text).orEmpty() }
                            .joinToString()
                }
        }.joinToString(" | ")
        listOf("Junk files", "To choose", "To back up", "Other", "Free").forEach {
            assertTrue("$it in $texts", texts.contains(it))
        }
    }

    @Test
    fun `Home has one clean button, app caches and one app at a time are elsewhere`() {
        home(OwnerPhone.home)
        assertEquals(1, compose.onAllNodesWithTag("open_SAFE").fetchSemanticsNodes().size)
        listOf("trimCard", "homeTrim", "oneByOneLink").forEach { compose.onNodeWithTag(it).assertDoesNotExist() }
        compose.onAllNodesWithText("Clear apps' temporary files", substring = true).fetchSemanticsNodes().let {
            assertEquals(0, it.size)
        }
    }

    @Test
    fun `the Apps tab has the apps' temporary files and one app at a time`() {
        var trims = 0
        val state = OwnerPhone.home.copy(tab = Tab.APPS, screens = listOf(Screen.Apps), appsLoaded = true)
        show {
            AppScreens(
                state = state,
                onOpen = {},
                onBack = {},
                home = {},
                safeActions = SafeActions(onTrim = { trims++ }),
            )
        }
        compose.onNodeWithTag("appsOneByOne").assertIsDisplayed()
        compose.onNodeWithTag("appsTrim").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("confirmAppsTrim").fetchSemanticsNodes()
                .any { androidx.compose.ui.semantics.SemanticsProperties.Disabled !in it.config }
        }
        compose.onNodeWithTag("confirmAppsTrim").performClick()
        assertEquals(1, trims)
    }

    @Test
    fun `nearly full, the banner's next step is the trim`() {
        var trims = 0
        home(OwnerPhone.home.copy(spaceMode = SpaceMode.MINIMAL), HomeActions(onTrim = { trims++ }))
        compose.onNodeWithTag("bannerAction").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("confirmHomeTrim").fetchSemanticsNodes()
                .any { androidx.compose.ui.semantics.SemanticsProperties.Disabled !in it.config }
        }
        compose.onNodeWithTag("confirmHomeTrim").performClick()
        assertEquals(1, trims)
    }

    // ------------------------------------------------------------------------------------------- 5 clean confirm

    private fun openConfirm(state: HomeUiState = OwnerPhone.home) {
        home(state)
        compose.onNodeWithTag("open_SAFE").performClick()
    }

    @Test
    fun `the clean sheet says what happens, red only for other apps' bins, and always reassures`() {
        openConfirm()
        compose.onNodeWithText("What happens").assertExists()
        compose.onNodeWithText("Where it goes").assertDoesNotExist()
        compose.onNodeWithText("Deleted now; the phone makes them again by itself", substring = true).assertExists()
        compose.onNodeWithText("Your photos, videos and chats are not deleted.", substring = true).assertExists()
        compose.onNodeWithTag(CONFIRM_SHEET_RISK_TAG).assert(
            SemanticsMatcher("about other apps' bins") {
                it.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString().contains("other apps' bins")
            },
        )
        // The rebuild line isn't the red one.
        val risk = compose.onNodeWithTag(CONFIRM_SHEET_RISK_TAG).fetchSemanticsNode()
            .config[SemanticsProperties.Text].joinToString()
        assertFalse(risk.contains("makes them again"))
    }

    @Test
    fun `without other apps' bins there is no red line`() {
        val noBins = OwnerPhone.summary.without(
            OwnerPhone.summary.group("other_app_bin", Category.SAFE)!!.items.mapTo(HashSet()) { it.item.path },
        )
        openConfirm(OwnerPhone.home.copy(summary = noBins))
        compose.onNodeWithTag(CONFIRM_SHEET_RISK_TAG).assertDoesNotExist()
        compose.onNodeWithText("Your photos, videos and chats are not deleted.", substring = true).assertExists()
    }

    @Test
    @Config(qualifiers = "iw-w360dp-h640dp")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun `at 200 percent the risk line comes first and the sheet scrolls with a fade above sticky buttons`() {
        RuntimeEnvironment.setFontScale(2f)
        openConfirm()
        val risk = compose.onNodeWithTag(CONFIRM_SHEET_RISK_TAG).getBoundsInRoot()
        val howMany = compose.onNodeWithText("כמה", useUnmergedTree = true).getBoundsInRoot()
        assertTrue("risk at ${risk.top}, rows at ${howMany.top}", risk.top < howMany.top)
        compose.onNodeWithTag(CONFIRM_SHEET_FADE_TAG).assertExists()
        compose.onNodeWithTag("confirmClean").assertIsDisplayed()
        // Scrolled to the end, the fade goes.
        compose.onNodeWithTag(io.github.tomerar.keepclear.core.designsystem.component.CONFIRM_SHEET_BODY_TAG)
            .performScrollToNode(hasText("לא נמחקים", substring = true))
        compose.waitForIdle()
        compose.onNodeWithTag("confirmClean").assertIsDisplayed()
    }

    // ------------------------------------------------------------------------------------------- 6 after clean

    private val safePaths by lazy {
        OwnerPhone.summary.groups(Category.SAFE).flatMap { it.items }.mapTo(HashSet()) { it.item.path }
    }

    private fun result(simulated: Boolean = false) = CleanResult(
        Total(safePaths.size, 7_900_000_000),
        skipped = 0,
        failed = 0,
        trashedPaths = emptyList(),
        origin = Screen.Home,
        simulated = simulated,
    )

    @Test
    fun `after a clean the cleaned items leave the totals at once and the hero shows the measured free space`() {
        val after = OwnerPhone.home.afterClean(result(), safePaths, 24_700_000_000)
        assertEquals(Total(), after.summary!!.phoneTotal(Category.SAFE))
        assertEquals(OwnerPhone.summary.phoneTotal(Category.MOVE), after.summary.phoneTotal(Category.MOVE))
        assertEquals(24_700_000_000, after.freeBytes)
        assertEquals(result(), after.cleanResult)
        assertFalse(after.busy)
    }

    @Test
    fun `in test mode nothing leaves the totals, and an unknown free space keeps the last one`() {
        val after = OwnerPhone.home.afterClean(result(simulated = true), safePaths, 0)
        assertEquals(OwnerPhone.summary, after.summary)
        assertEquals(OwnerPhone.FREE_BYTES, after.freeBytes)
    }

    @Test
    fun `nothing left to clean shows All clean and points to the backup, never 0B`() {
        var backup = 0
        val state = OwnerPhone.home.afterClean(result(), safePaths, 24_700_000_000).copy(cleanResult = null)
        home(state, HomeActions(onOpenBackup = { backup++ }))
        compose.onNodeWithText("All clean ✓", substring = true).assertIsDisplayed()
        val move = OwnerPhone.summary.phoneTotal(Category.MOVE).bytes
        val bigWin = compose.onNodeWithTag(
            "bigWin",
        ).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString()
        assertTrue(bigWin, plain(bigWin).contains(gb(move)) && bigWin.contains("backing up your photos"))
        compose.onNodeWithTag("bigWinButton").performClick()
        assertEquals(1, backup)
        listOf("0 B", "0B").forEach { zero ->
            val hits = compose.onAllNodes(
                SemanticsMatcher("shows $zero") { n ->
                    (
                        n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
                            n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                    )
                        .any { t -> Regex("(^|[^0-9.,])${Regex.escape(zero)}").containsMatchIn(t) }
                },
                useUnmergedTree = true,
            ).fetchSemanticsNodes()
            assertEquals("no $zero on Home", 0, hits.size)
        }
    }

    @Test
    fun `with nothing to back up the biggest win is choosing what to delete`() {
        var opened: Category? = null
        val noMove = OwnerPhone.summary.without(
            OwnerPhone.summary.groups(Category.MOVE).flatMap { it.items }.mapTo(HashSet()) { it.item.path } + safePaths,
        )
        home(OwnerPhone.home.copy(summary = noMove), HomeActions(onOpenCategory = { opened = it }))
        compose.onNodeWithTag("bigWin").assert(hasText("choosing what to delete", substring = true))
        compose.onNodeWithTag("bigWinButton").performClick()
        assertEquals(Category.REVIEW, opened)
    }

    // ------------------------------------------------------------------------------------------- 7 REVIEW lane

    @Test
    fun `the REVIEW lane is about deleting, and nothing is deleted unless chosen`() {
        home(OwnerPhone.home)
        compose.onNodeWithText("Choose what to delete", substring = true).assertExists()
        compose.onNodeWithText("Choose what to keep").assertDoesNotExist()
        compose.onNodeWithText("Nothing is deleted unless you choose it.", substring = true).assertExists()
    }

    @Test
    @Config(qualifiers = "iw-w360dp-h800dp")
    fun `in Hebrew the REVIEW lane is choose what to delete`() {
        home(OwnerPhone.home)
        compose.onAllNodesWithText(
            "לבחור מה למחוק",
            substring = true,
        ).fetchSemanticsNodes().isNotEmpty().let(::assertTrue)
        compose.onNodeWithText("לבחור מה להשאיר").assertDoesNotExist()
        compose.onNodeWithText("שום דבר לא נמחק בלי שתבחרו", substring = true).assertExists()
    }

    @Test
    fun `the REVIEW list repeats that nothing happens without the user`() {
        show { CategoryListScreen(OwnerPhone.summary, Category.REVIEW, {}, {}) }
        compose.onNodeWithTag("nothingWithoutYou").assertIsDisplayed()
    }

    // ------------------------------------------------------------------------------------------- 11 empty states

    @Test
    fun `an empty REVIEW list has one next step`() {
        var backUp = 0
        val empty = OwnerPhone.summary.without(
            OwnerPhone.summary.groups(Category.REVIEW).flatMap { it.items }.mapTo(HashSet()) { it.item.path },
        )
        show { CategoryListScreen(empty, Category.REVIEW, {}, {}, onBackUp = { backUp++ }) }
        compose.onNodeWithTag("reviewEmpty").assertIsDisplayed()
        compose.onNodeWithTag("reviewEmptyNext").performClick()
        assertEquals(1, backUp)
    }

    @Test
    fun `nothing to back up says so, with one next step`() {
        var free = 0
        val empty = OwnerPhone.summary.without(
            OwnerPhone.summary.groups(Category.MOVE).flatMap { it.items }.mapTo(HashSet()) { it.item.path },
        )
        show {
            MoveScreen(empty, MoveUiState(), OwnerPhone.NOW, false, MoveActions(onOpenFreeTab = { free++ }), null)
        }
        compose.onNodeWithTag("moveNothing").assertIsDisplayed()
        compose.onNodeWithTag("moveNothingNext").performClick()
        assertEquals(1, free)
    }

    @Test
    fun `a full drive, a full phone and an unexpected error each say what to do next`() {
        assertEquals(
            mapOf(
                PauseReason.TARGET_FULL to io.github.tomerar.keepclear.core.i18n.R.string.pause_next_full,
                PauseReason.PHONE_LOW_SPACE to io.github.tomerar.keepclear.core.i18n.R.string.pause_next_phone_space,
                PauseReason.UNEXPECTED_ERROR to io.github.tomerar.keepclear.core.i18n.R.string.pause_next_unexpected,
            ),
            PauseReason.entries.mapNotNull { r -> pauseNextStep(r)?.let { r to it } }.toMap(),
        )
        assertNull(pauseNextStep(null))
    }

    private fun paused(reason: PauseReason) = JobView(
        MoveJob("j1", "t1", "SanDisk USB", "KeepClear/Phone", OwnerPhone.NOW, JobState.PAUSED, reason),
        Receipt("j1", "SanDisk USB", "KeepClear/Phone", 10, 10_000, emptyMap(), 0, 5, 0),
        canReadBack = true,
    )

    @Test
    fun `a backup paused because the phone is full offers to free space on the phone`() {
        var free = 0
        show {
            MoveScreen(
                OwnerPhone.summary,
                MoveUiState(jobs = listOf(paused(PauseReason.PHONE_LOW_SPACE))),
                OwnerPhone.NOW,
                false,
                MoveActions(onOpenFreeTab = { free++ }),
                null,
            )
        }
        compose.onNodeWithTag("pauseNext").assert(hasText("Free some space on the phone first", substring = true))
        compose.onNodeWithTag("resume").assertExists()
        compose.onNodeWithTag("pauseFreePhone").performClick()
        assertEquals(1, free)
    }

    @Test
    fun `a backup paused with an unexpected error says what to try`() {
        show {
            MoveScreen(
                OwnerPhone.summary,
                MoveUiState(jobs = listOf(paused(PauseReason.UNEXPECTED_ERROR))),
                OwnerPhone.NOW,
                false,
                MoveActions(),
                null,
            )
        }
        compose.onNodeWithTag("pauseNext").assert(hasText("unplug the USB drive", substring = true))
        compose.onNodeWithTag("pauseFreePhone").assertDoesNotExist()
    }

    @Suppress("unused")
    private fun tagged(tag: String) = hasTestTag(tag)
}
