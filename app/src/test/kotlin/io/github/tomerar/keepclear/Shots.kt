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
import androidx.compose.ui.test.performScrollTo
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
    /** Owner brief H6: "tablet_" for the tablet set, so its files sit next to the phone's under their own name. */
    private val prefix: String = "",
) {
    /** On a tablet a screen shown alone keeps the readable width, as in [AppScreens] ([ReadableWidth], UI-2). */
    private val tablet = prefix.isNotEmpty()

    init {
        // Dates on screen (the trash's "space comes back on …", "last used") are worked out in the phone's zone.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        RuntimeEnvironment.setQualifiers("+$locale-${if (night) "night" else "notnight"}")
        if (fontScale != 1f) RuntimeEnvironment.setFontScale(fontScale)
    }

    fun path(screen: String): String {
        val theme = if (night) "dark" else "light"
        val font = if (fontScale != 1f) "_font${(fontScale * 100).toInt()}" else ""
        return "src/test/screenshots/$prefix${screen}_${locale}_$theme${font}_api${Build.VERSION.SDK_INT}.png"
    }

    /** [alone]: a screen shown without [AppScreens] (which already keeps the readable width on a tablet). */
    fun show(alone: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent {
            KeepClearTheme(darkTheme = night, reducedMotion = true) {
                CompositionLocalProvider(
                    LocalThumbnailLoader provides OwnerPhone.thumbnails,
                    LocalClock provides { OwnerPhone.NOW },
                ) { if (tablet && alone) ReadableWidth { content() } else content() }
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

    /** Owner brief H2 item 6: what cleaning the owner-shaped phone's SAFE items leaves on Home. */
    private fun cleaned(): Pair<CleanResult, Set<String>> {
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
        return done to safe.mapTo(HashSet()) { it.item.path }
    }

    /**
     * Back on Home right after cleaning (owner brief H2 item 6: the real post-clean Home, through the ViewModel's own
     * [afterClean]): the measured result on top, the cleaned items gone from the totals, the hero at the measured
     * free space and „הכול נקי ✓” pointing to the backup. Owner decision 2026-11-02: what the owner-shaped phone
     * cleans (statuses, thumbnails, temp files, the gallery's bin) is all deleted for good.
     */
    fun result() {
        val (done, paths) = cleaned()
        val state = OwnerPhone.home.copy(trash = OwnerPhone.trash).afterClean(done, paths, done.measured!!.nowFree)
        show { Home(state) }
        capture("result")
    }

    /** Owner brief H2 item 6: the next day, nothing left to clean: „הכול נקי ✓” and the biggest win (the backup). */
    fun allClean() {
        val (done, paths) = cleaned()
        val state = OwnerPhone.home.copy(trash = OwnerPhone.trash).afterClean(done, paths, done.measured!!.nowFree)
            .copy(cleanResult = null)
        show { Home(state) }
        capture("home_all_clean")
    }

    /** Owner brief H2 item 1: each intro page with its picture. */
    fun introPage(page: Int) {
        show { IntroScreen({}) }
        repeat(page) { compose.onNodeWithTag("introNext").performClick() }
        capture("intro_${page + 1}")
    }

    fun reviewList() {
        show { CategoryListScreen(OwnerPhone.summary, Category.REVIEW, {}, {}) }
        capture("review_list")
    }

    fun reviewGroup() {
        show { GroupScreen(OwnerPhone.summary.group("screenshots", Category.REVIEW)!!, {}, {}) }
        capture("review_group")
    }

    private val swipeItems get() = OwnerPhone.summary.group("screenshots", Category.REVIEW)!!.items

    /** PR H2b: 40 of the screenshots decided, every third toward Delete (14 marked). */
    private fun midSession(): List<SwipeDecision> = swipeItems.take(40).mapIndexed { i, item ->
        SwipeDecision.of(item, if (i % 3 == 0) SwipeChoice.DELETE else SwipeChoice.KEEP)
    }

    /** PR H2b: in the middle of a session: „לסיים ולבדוק · 14” always on screen, „חזרה” apart from „לסגור”. */
    fun swipe() {
        show(alone = true) { SwipeReview(swipeItems, {}, {}, decisions = midSession()) }
        capture("swipe")
    }

    /** PR H2b: the first session ever: the one-time hint says which way deletes. */
    fun swipeHint() {
        show {
            CompositionLocalProvider(LocalSwipePrefs provides SwipePrefs(hintSeen = false)) {
                SwipeReview(swipeItems, {}, {})
            }
        }
        capture("swipe_hint")
    }

    /** PR H2b: the card dragged to the left: its edge says „למחוק”. */
    fun swipeDrag() {
        show { SwipeReview(swipeItems, {}, {}, decisions = midSession(), previewDrag = -0.3f) }
        capture("swipe_drag")
    }

    /** PR H2b: „להמשיך מאיפה שעצרתם”, with two files that changed since the session was left. */
    fun swipeResume() {
        val group = OwnerPhone.summary.group("screenshots", Category.REVIEW)!!
        val saved = midSession().mapIndexed { i, d -> if (i == 5 || i == 6) d.copy(size = d.size + 1) else d }
        show {
            GroupScreen(
                group,
                {},
                {},
                selection = GroupSelection(emptySet(), {}, swipe = null, onSwipe = {}, saved = saved),
            )
        }
        compose.onNodeWithTag("grid").performScrollToNode(hasTestTag("startSwipe"))
        compose.onNodeWithTag("startSwipe").performClick()
        capture("swipe_resume")
    }

    /** H1c: the Trash as a grid of pictures by month (its default). */
    fun trash() {
        show(alone = true) {
            TrashScreen(OwnerPhone.trash, { _, _ -> }, { _, _ -> }, {}, showUninstallNote = true, now = OwnerPhone.NOW)
        }
        capture("trash")
    }

    /** H1c: the grouped list stays one tap away. */
    fun trashList() {
        show {
            TrashScreen(
                OwnerPhone.trash,
                { _, _ -> },
                { _, _ -> },
                {},
                showUninstallNote = true,
                now = OwnerPhone.NOW,
                initialView = TrashView.LIST,
            )
        }
        capture("trash_list")
    }

    /** H1c: one trashed photo's details, opened from its tile. */
    fun trashDetails() {
        show {
            TrashScreen(OwnerPhone.trash, { _, _ -> }, { _, _ -> }, {}, showUninstallNote = false, now = OwnerPhone.NOW)
        }
        compose.onNodeWithTag("trash").performScrollToNode(hasTestTag("tile_c1"))
        compose.onNodeWithTag("tile_c1").performClick()
        capture("trash_details")
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

    /** The owner-shaped phone's backup, copied and checked once (H1: before the second check, [confirmed] = 0). */
    private fun receipt(confirmed: Int) = Receipt(
        "j1",
        "SanDisk USB",
        "KeepClear/Phone",
        verified = 6_830,
        verifiedBytes = 31_000_000_000,
        failed = emptyMap(),
        changed = 0,
        pending = 0,
        namesChanged = 0,
        confirmed = confirmed,
        confirmedBytes = confirmed * (31_000_000_000 / 6_830),
    )

    /**
     * Owner decisions P1, P2, P4: the 6,830 originals the receipt offers to free: 120 favourites and 310 from the last
     * 30 days (both kept on the phone by the pre-ticked options), and 400 WhatsApp photos (the extra sentence).
     */
    private val freeCandidates: List<io.github.tomerar.keepclear.engine.move.FreeCandidate> by lazy {
        val day = 86_400_000L
        val size = 31_000_000_000 / 6_830
        (0 until 6_830).map { i ->
            val whatsApp = i % 17 == 0
            val path = if (whatsApp) {
                "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/IMG-$i-WA0001.jpg"
            } else {
                "/storage/emulated/0/DCIM/Camera/IMG_$i.jpg"
            }
            io.github.tomerar.keepclear.engine.move.FreeCandidate(
                path,
                size,
                mtime = OwnerPhone.NOW - (if (i % 22 == 1) 3L else 400L + i % 300) * day,
                favourite = i % 57 == 2,
            )
        }
    }

    /** Owner brief H1 item 6: copied and checked once: „הועתק ✓ · שלב אחרון: בדיקה”, no stamp yet. */
    fun backupCopied() {
        val view = JobView(job.copy(state = JobState.DONE), receipt(confirmed = 0), canReadBack = true)
        show { Backup(MoveUiState(jobs = listOf(view))) }
        capture("backup_copied")
    }

    /**
     * Owner brief H1 item 6: the re-plug check, one picture per live state (unplug → unplugged ✓, plug it back in →
     * checking → the stamp).
     */
    fun backupCheck(step: String) {
        val check = when (step) {
            "unplug" -> BackupCheck.Unplug("j1")
            "plugback" -> BackupCheck.PlugBack("j1")
            "checking" -> BackupCheck.Checking("j1")
            else -> BackupCheck.Done("j1", io.github.tomerar.keepclear.engine.move.CheckResult(6_830, 0, 0))
        }
        val confirmed = if (check is BackupCheck.Done) 6_830 else 0
        val view = JobView(job.copy(state = JobState.DONE), receipt(confirmed), canReadBack = true)
        show { Backup(MoveUiState(jobs = listOf(view), check = check)) }
        capture("backup_check_$step")
    }

    /** Owner decisions P1, P2, P4: the free step's confirmation, opened from the receipt, its button ready. */
    fun freeConfirm() {
        val view = JobView(
            job.copy(state = JobState.DONE),
            receipt(confirmed = 6_830),
            canReadBack = true,
            freeCandidates = freeCandidates,
        )
        show { Backup(MoveUiState(jobs = listOf(view))) }
        compose.onNodeWithTag("moveScreen").performScrollToNode(hasTestTag("freeOriginals"))
        compose.onNodeWithTag("freeOriginals").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("confirmFree").fetchSemanticsNodes()
                .any { androidx.compose.ui.semantics.SemanticsProperties.Disabled !in it.config }
        }
        capture("free_confirm")
    }

    /** Owner brief H1 item 7: after freeing, the measured result („פונו 31.0GB · עכשיו פנויים 47.8GB”). */
    fun freeResult() {
        val view = JobView(job.copy(state = JobState.DONE), receipt(confirmed = 6_830), canReadBack = true)
        val result = FreeResult(
            "j1",
            done = 6_000,
            bytes = 27_200_000_000,
            skipped = 0,
            failed = 0,
            forGood = true,
            targetLabel = "SanDisk USB",
            measuredFreed = 27_200_000_000,
            nowFree = 44_000_000_000,
        )
        show { Backup(MoveUiState(jobs = listOf(view), freeResult = result)) }
        capture("free_result")
    }

    /** H1c: "Restore from a backup" as a sheet: the backup's files by month, with pictures from the USB drive. */
    fun restore() {
        show { Backup(MoveUiState(restore = OwnerPhone.restoreCatalog)) }
        capture("restore")
    }

    /** H1c: the phone hasn't room for every month chosen: the newest files that fit can come back now. */
    fun restorePartial() {
        val tight = OwnerPhone.restoreCatalog.copy(
            space = io.github.tomerar.keepclear.engine.restore.Space(
                allocatable = 1_400_000_000,
                total = 16_000_000_000,
            ),
        )
        show { Backup(MoveUiState(restore = tight)) }
        compose.onNodeWithTag("restoreViewToggle").performClick()
        tight.groups.forEach { compose.onNodeWithTag("restore_${it.key}").performClick() }
        compose.onNodeWithTag("restoreFit").performScrollTo()
        capture("restore_partial")
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
}
