// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import io.github.tomerar.keepclear.core.designsystem.KeepClearTheme
import io.github.tomerar.keepclear.core.model.Category
import io.github.tomerar.keepclear.engine.classify.Total
import io.github.tomerar.keepclear.engine.move.JobState
import io.github.tomerar.keepclear.engine.move.MoveJob
import io.github.tomerar.keepclear.engine.move.Receipt
import org.robolectric.RuntimeEnvironment
import java.util.TimeZone

/** Design QA item 5: screenshots of a confirmation are taken once its button is ready (500 ms, plus a margin). */
const val CONFIRM_READY_MILLIS = 600L

/**
 * F3: every screen of the app as the user sees it, on [OwnerPhone]. Each screen is captured as the whole window
 * ([captureScreenRoboImage]), so sheets and dialogs (ConfirmSheet, restore) are in the picture. Files are named
 * `<screen>_<locale>_<light|dark>[_font200]_api<sdk>.png`.
 */
class Shots(
    private val compose: ComposeContentTestRule,
    private val locale: String,
    private val night: Boolean,
    private val fontScale: Float = 1f,
) {
    init {
        // Dates on screen (the trash's "space comes back on …", "last used") are worked out in the phone's zone.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        RuntimeEnvironment.setQualifiers("+$locale-${if (night) "night" else "notnight"}")
        if (fontScale != 1f) RuntimeEnvironment.setFontScale(fontScale)
    }

    fun path(screen: String): String {
        val theme = if (night) "dark" else "light"
        val font = if (fontScale != 1f) "_font${(fontScale * 100).toInt()}" else ""
        return "src/test/screenshots/${screen}_${locale}_$theme${font}_api${Build.VERSION.SDK_INT}.png"
    }

    fun show(content: @Composable () -> Unit) {
        compose.setContent {
            KeepClearTheme(darkTheme = night, reducedMotion = true) {
                CompositionLocalProvider(
                    LocalThumbnailLoader provides OwnerPhone.thumbnails,
                    LocalClock provides { OwnerPhone.NOW },
                ) { content() }
            }
        }
    }

    @OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
    fun capture(screen: String) {
        compose.waitForIdle()
        // Tolerate tiny anti-aliasing differences between machines; layout or text changes still fail. Created
        // here: RoborazziOptions reads settings that exist only in the Robolectric sandbox.
        val options = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.005f))
        captureScreenRoboImage(path(screen), roborazziOptions = options)
    }

    // ------------------------------------------------------------------ the screens

    /** The Free up space tab in the main frame (top app bar with the Trash chip, tabs). */
    @Composable
    fun Home(state: HomeUiState = OwnerPhone.home.copy(trash = OwnerPhone.trash)) {
        AppScreens(state = state, onOpen = {}, onBack = {}, home = { HomeScreen(state, showDebugTools = false) })
    }

    fun home() {
        show { Home() }
        capture("home")
    }

    fun homeBeforeAccess() {
        show { Home(OwnerPhone.beforeAccess) }
        capture("home_access")
    }

    /** The main button's confirmation, opened the way the user opens it. */
    fun confirm() {
        show { Home() }
        compose.onNodeWithTag("open_SAFE").performClick()
        // Design QA item 5: the picture shows the confirm button ready, after its 500 ms fill.
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("confirmClean").fetchSemanticsNodes()
                .any { androidx.compose.ui.semantics.SemanticsProperties.Disabled !in it.config }
        }
        capture("confirm_clean")
    }

    /**
     * Back on Home after cleaning, on top. Owner decision 2026-11-02: what the owner-shaped phone cleans (statuses,
     * thumbnails, temp files, the gallery's bin) is all deleted for good, so the banner is the measured result.
     */
    fun result() {
        val safe = OwnerPhone.summary.groups(Category.SAFE).flatMap { it.items }
        val forGood = io.github.tomerar.keepclear.engine.enrich.CleanPlanner.permanentReasons(safe, OwnerPhone.NOW)
        val (gone, trashed) = safe.partition { it.item.path in forGood }
        val freed = gone.sumOf { it.item.size }
        val done = CleanResult(
            safe.fold(Total()) { t, i -> t + i },
            skipped = 0,
            failed = 0,
            trashedPaths = trashed.map { it.item.path },
            origin = Screen.Home,
            deletedForGood = Total(gone.size, freed),
            measured = Measured(freed, OwnerPhone.FREE_BYTES + freed, OwnerPhone.FREE_BYTES, Screen.Home),
        )
        show { Home(OwnerPhone.home.copy(cleanResult = done, trash = OwnerPhone.trash)) }
        capture("result")
    }

    fun reviewList() {
        show { CategoryListScreen(OwnerPhone.summary, Category.REVIEW, {}, {}) }
        capture("review_list")
    }

    fun reviewGroup() {
        show { GroupScreen(OwnerPhone.summary.group("screenshots", Category.REVIEW)!!, {}, {}) }
        capture("review_group")
    }

    fun swipe() {
        show { SwipeReview(OwnerPhone.summary.group("screenshots", Category.REVIEW)!!.items, {}, {}) }
        capture("swipe")
    }

    fun trash() {
        show {
            TrashScreen(OwnerPhone.trash, { _, _ -> }, { _, _ -> }, {}, showUninstallNote = true, now = OwnerPhone.NOW)
        }
        capture("trash")
    }

    /** Design QA item 3: the bottom of the Trash, with its actions after the list (none of them filled red). */
    fun trashActions() {
        show {
            TrashScreen(OwnerPhone.trash, { _, _ -> }, { _, _ -> }, {}, showUninstallNote = true, now = OwnerPhone.NOW)
        }
        compose.onNodeWithTag("trash").performScrollToNode(hasTestTag("emptyNow"))
        capture("trash_actions")
    }

    @Composable
    private fun Backup(move: MoveUiState) {
        val state = OwnerPhone.home.copy(tab = Tab.BACKUP, screens = listOf(Screen.Backup), trash = OwnerPhone.trash)
        AppScreens(state = state, onOpen = {}, onBack = {}, home = {}, move = move)
    }

    fun backup() {
        show { Backup(MoveUiState()) }
        capture("backup")
    }

    private val job = MoveJob(
        id = "j1",
        targetId = "t1",
        targetLabel = "SanDisk USB",
        rootRel = "KeepClear/Phone",
        createdAt = OwnerPhone.NOW - 3_600_000,
    )

    fun backupProgress() {
        val receipt = Receipt("j1", "SanDisk USB", "KeepClear/Phone", 2_870, 12_400_000_000, emptyMap(), 0, 3_960, 0)
        val view = JobView(
            job.copy(state = JobState.RUNNING),
            receipt,
            canReadBack = true,
            totalBytes = 31_000_000_000,
            doneBytes = 12_900_000_000,
            etaMillis = 41 * 60_000L,
        )
        show { Backup(MoveUiState(jobs = listOf(view), running = "j1")) }
        capture("backup_progress")
    }

    fun backupSummary() {
        val receipt = Receipt(
            "j1",
            "SanDisk USB",
            "KeepClear/Phone",
            verified = 6_830,
            verifiedBytes = 31_000_000_000,
            failed = emptyMap(),
            changed = 0,
            pending = 0,
            namesChanged = 0,
            confirmed = 6_830,
            confirmedBytes = 31_000_000_000,
        )
        val view = JobView(job.copy(state = JobState.DONE), receipt, canReadBack = true)
        show { Backup(MoveUiState(jobs = listOf(view))) }
        capture("backup_summary")
    }

    fun restore() {
        val groups = listOf("2021-07" to 2_400_000_000L, "2021-08" to 1_900_000_000L, "2022-12" to 3_100_000_000L)
            .map { (month, bytes) -> RestoreGroup("Camera", month, bytes, toOriginal = 410, toRestoreFolder = 0) }
        val catalog = RestoreStep.Catalog(
            "KeepClear/Phone",
            groups,
            io.github.tomerar.keepclear.engine.restore.Space(allocatable = 16_800_000_000, total = 128_000_000_000),
        )
        show { Backup(MoveUiState(restore = catalog)) }
        capture("restore")
    }

    fun apps() {
        val state = OwnerPhone.home.copy(
            tab = Tab.APPS,
            screens = listOf(Screen.Apps),
            appsInfo = OwnerPhone.apps,
            appsLoaded = true,
            trash = OwnerPhone.trash,
        )
        show { AppScreens(state = state, onOpen = {}, onBack = {}, home = {}) }
        capture("apps")
    }

    private val settingsActions = SettingsActions(version = "1.0.0", trashBytes = 255_000_000)

    fun settings() {
        show { SettingsScreen(true, {}, {}, {}, settingsActions) }
        capture("settings")
    }

    /** Settings scrolled down to Advanced (trial mode, backup places). */
    fun settingsAdvanced() {
        show { SettingsScreen(true, {}, {}, {}, settingsActions) }
        compose.onNodeWithTag("settings").performScrollToNode(hasTestTag("openNextcloud"))
        capture("settings_advanced")
    }

    fun intro() {
        show { IntroScreen {} }
        capture("intro")
    }
}
