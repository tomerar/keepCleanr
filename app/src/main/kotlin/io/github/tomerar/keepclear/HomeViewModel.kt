// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.app.Application
import android.os.StatFs
import android.os.storage.StorageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.tomerar.keepclear.core.model.Category
import io.github.tomerar.keepclear.core.safety.ActionPlan
import io.github.tomerar.keepclear.core.safety.Confirmation
import io.github.tomerar.keepclear.core.safety.ExecutionReport
import io.github.tomerar.keepclear.core.safety.OpKind
import io.github.tomerar.keepclear.core.safety.OpResult
import io.github.tomerar.keepclear.core.safety.PlannedOp
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.core.storage.media.MediaAccess
import io.github.tomerar.keepclear.core.storage.media.MediaBatch
import io.github.tomerar.keepclear.engine.classify.ClassificationSummary
import io.github.tomerar.keepclear.engine.classify.ClassifiedItem
import io.github.tomerar.keepclear.engine.classify.Classifier
import io.github.tomerar.keepclear.engine.classify.Total
import io.github.tomerar.keepclear.engine.enrich.CleanPlanner
import io.github.tomerar.keepclear.engine.enrich.Enrichment
import io.github.tomerar.keepclear.engine.enrich.Evidence
import io.github.tomerar.keepclear.engine.enrich.FactEnricher
import io.github.tomerar.keepclear.engine.scan.ScanEvent
import io.github.tomerar.keepclear.engine.scan.ScanItem
import io.github.tomerar.keepclear.engine.scan.Scanner
import io.github.tomerar.keepclear.engine.scan.SpaceMode
import io.github.tomerar.keepclear.engine.trash.TrashEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** Scan state shown on Home (SCAN-1/3, ONB-3/4, LOW-1/2). */
sealed interface ScanState {
    data object Idle : ScanState

    data class Running(val files: Int, val bytes: Long) : ScanState

    data class Done(val files: Int, val bytes: Long, val complete: Boolean) : ScanState
}

data class HomeUiState(
    val totalBytes: Long = 0,
    val freeBytes: Long = 0,
    val mediaAccess: MediaAccess = MediaAccess.NONE,
    /** null in the `play` flavor (no all-files access there, ADR 0005). */
    val allFiles: Boolean? = null,
    /** MSG-3: cloned-app storage that can be scanned, or that exists but is hidden from KeepClear. */
    val cloned: ClonedStorage = ClonedStorage(),
    /** M12b, ONB-6: granted WhatsApp folders (their real paths), scanned while All files access is off. */
    val grantedFolders: List<String> = emptyList(),
    /** ONB-6: the picked folder wasn't WhatsApp's; the guide offers to try again. */
    val wrongFolder: Boolean = false,
    val spaceMode: SpaceMode = SpaceMode.NORMAL,
    val scan: ScanState = ScanState.Idle,
    /** Classification of the last scan (M5: read-only browsing). */
    val summary: ClassificationSummary? = null,
    val trash: TrashUi = TrashUi(),
    val cleanResult: CleanResult? = null,
    /** Owner decision 1.3: items held back because they are the last copy of something in the trash. */
    val lastCopy: List<io.github.tomerar.keepclear.core.safety.PlannedOp>? = null,
    /** Bytes Android freed in the last cache trim (measured, SAFE-5). */
    val trimResult: Long? = null,
    /** Navigation stack of the shown [tab]; last is visible. */
    val screens: List<Screen> = listOf(Screen.Home),
    /** F1b: the main tab shown ([Nav]). */
    val tab: Tab = Tab.FREE,
    /** F1b: the stacks of the other tabs, as they were left (each tab keeps its own place). */
    val tabStacks: Map<Tab, List<Screen>> = emptyMap(),
    /** An action is running; buttons that start another one are disabled (review PR C). */
    val busy: Boolean = false,
    /** Free space measured before and after the last action that frees space (review PR C). */
    val measured: Measured? = null,
    /** M12: Android's own confirmation dialogs still to show, for items going to Android's trash. */
    val consent: MediaConsent? = null,
    /** SAFE-10: launchable apps, and the space measured after the user cleared one in Android's screen. */
    val apps: List<AppEntry> = emptyList(),
    val perAppFreed: Map<String, Long> = emptyMap(),
    /** M13: apps with sizes and last use; null without usage access (ONB-7). */
    val appsInfo: List<io.github.tomerar.keepclear.engine.classify.AppInfo>? = null,
    /** F2: false until the first load of [appsInfo] has finished, so "loading" is never shown as "no access". */
    val appsLoaded: Boolean = false,
    /**
     * E4: what the user selected in each group (by [GroupKey]), kept here and not in the screen's saved state: a
     * 10,000-path set in saved state crashes Android (TransactionTooLarge). A group's selection ends when its
     * screen is left with Back.
     */
    val selections: Map<String, Set<String>> = emptyMap(),
    /** E4, E11: the one-by-one review's decisions per group, by path; null while not reviewing one by one. */
    val swipes: Map<String, List<SwipeDecision>> = emptyMap(),
)

/** E4: the key of a group's selection. */
object GroupKey {
    fun of(ruleId: String, category: Category) = "$ruleId|${category.name}"
}

/**
 * M12: what Android must confirm, one dialog per [requests] entry, launched one at a time by the activity. Only
 * the ops of requests the user approved count as done.
 */
data class MediaConsent(
    val requests: List<io.github.tomerar.keepclear.core.safety.ConsentRequest>,
    val origin: Screen,
    val approved: List<PlannedOp> = emptyList(),
    val declined: Int = 0,
    val failed: Int = 0,
    /** E12: the id of the first request; each answered request moves it on, so every dialog has its own id. */
    val headId: Int = 0,
    /** E12: the id of the dialog Android shows right now; it is not shown again (rotation, theme, language). */
    val launchedId: Int? = null,
) {
    /** E12: the first request still has to be shown. */
    val needsLaunch: Boolean get() = requests.isNotEmpty() && launchedId != headId

    /** The user answered the first request (or it could not be shown: [couldNotShow]). */
    fun answer(approved: Boolean, couldNotShow: Boolean = false): MediaConsent {
        val head = requests.firstOrNull() ?: return this
        return copy(
            headId = headId + 1,
            launchedId = null,
            requests = requests.drop(1),
            approved = if (approved) this.approved + head.ops else this.approved,
            declined = declined + if (!approved && !couldNotShow) head.ops.size else 0,
            failed = failed + if (couldNotShow) head.ops.size else 0,
        )
    }

    fun result(): CleanResult {
        // Owner decision 2026-11-02: rows already in Android's trash were deleted for good, not moved to it.
        val forGood = approved.filter { it.kind == OpKind.DELETE_FOR_GOOD }
        return CleanResult(
            done = Total(approved.size, approved.sumOf { it.expectedSize }),
            skipped = declined,
            failed = failed,
            trashedPaths = emptyList(),
            origin = origin,
            androidTrash = approved.size > forGood.size,
            declined = approved.isEmpty() && failed == 0,
            deletedForGood = Total(forGood.size, forGood.sumOf { it.expectedSize }),
        )
    }
}

/** "Freed 7.9 GB · now 24.7 GB free (was 16.8 GB)", shown on the screen the action came from. */
data class Measured(val freed: Long, val nowFree: Long, val wasFree: Long, val origin: Screen)

/**
 * The phone's free space now, as the clean result measures it (StatFs on KeepClear's own folder, review PR C). Also
 * the free result after a backup (owner brief H1 item 7). Reads storage: never on the main thread.
 */
internal fun phoneFreeBytes(
    context: android.content.Context,
): Long = StatFs(context.filesDir.absolutePath).availableBytes

/** Simple in-memory navigation (no navigation library needed yet). */
sealed interface Screen {
    /** The "Free up space" tab. */
    data object Home : Screen

    /** F1b: the "Backup" tab: move memories, restore (also without a scan), backup places, history. */
    data object Backup : Screen

    data class CategoryList(val category: Category) : Screen

    data class Group(val ruleId: String, val category: Category) : Screen

    data class Preview(val path: String, val ruleId: String, val category: Category) : Screen

    data object Trash : Screen

    data object Settings : Screen

    /** SAFE-9: other apps' own folders, per app. */
    data object AppContent : Screen

    /** SAFE-10: clear one app at a time. */
    data object OneAppAtATime : Screen

    /** M13: apps by size and last use; the "Apps" tab. */
    data object Apps : Screen

    /** Test builds only: Settings › Advanced › Device check (read-only). */
    data object DeviceCheck : Screen

    /** M17b: Settings › Advanced › Back up to Nextcloud. */
    data object Nextcloud : Screen
}

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as KeepClearApplication
    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state
    private var scanJob: Job? = null

    /** E13: the user cancelled the scan; it stays cancelled (also on resume) until they start one again. */
    private var scanCancelled = false

    init {
        // E3: a free, check or restore ended in the foreground service: free space, trash and scan are new.
        viewModelScope.launch {
            app.moveController.finished.drop(1).collect {
                refreshNow()
                loadTrash()
                scan()
            }
        }
    }

    /** CLS-7: the user's kept and always-junk choices apply to every classification. */
    private suspend fun classifier(): Classifier = homeClassifier(app)

    /** The last scan's items, so a choice can be applied without scanning again. */
    private var lastItems: List<ScanItem> = emptyList()

    /** CLS-7 "This is important to me". */
    fun keep(path: String) {
        if (app.userChoices.keep(path)) reclassify()
    }

    /** CLS-7 "Always treat this folder as junk". */
    fun alwaysJunk(folder: String) {
        if (app.userChoices.alwaysJunk(folder)) reclassify()
    }

    fun removeKept(path: String) {
        app.userChoices.removeKept(path)
        reclassify()
    }

    fun removeJunk(folder: String) {
        app.userChoices.removeJunk(folder)
        reclassify()
    }

    private fun reclassify() {
        val items = lastItems
        if (items.isNotEmpty()) viewModelScope.launch { classify(items) }
    }

    /** SAFE-10: the app whose info screen the user opened, and the free space just before. */
    private var appInfoOpened: Pair<String, Long>? = null

    /** SAFE-10: the user is about to open Android's app-info screen for [pkg]. */
    fun openingAppInfo(pkg: String) {
        viewModelScope.launch { appInfoOpened = pkg to freeNow() }
    }

    fun setApps(apps: List<AppEntry>) = _state.update { it.copy(apps = apps) }

    fun setAppsInfo(apps: List<io.github.tomerar.keepclear.engine.classify.AppInfo>?) =
        _state.update { it.copy(appsInfo = apps, appsLoaded = true) }

    /** Called on every resume: permissions and free space can change outside the app. */
    fun refresh() {
        viewModelScope.launch { refreshNow() }
    }

    /** What [refresh] measures; read off the main thread (E1: it reads storage). */
    private class Probe(
        val total: Long,
        val free: Long,
        val media: MediaAccess,
        val allFiles: Boolean?,
        val granted: List<String>,
        val cloned: ClonedStorage,
    )

    private suspend fun probe(): Probe = withContext(Dispatchers.IO) {
        val stat = StatFs(app.filesDir.absolutePath)
        app.refreshGrantedFolders()
        val allFiles = if (BuildConfig.FLAVOR == "foss") MediaAccess.hasAllFiles() else null
        Probe(
            total = stat.totalBytes,
            free = stat.availableBytes,
            media = MediaAccess.current(app),
            allFiles = allFiles,
            granted = app.grantedFolders.keys.sorted(),
            cloned = ClonedApps.probe(
                allFiles = allFiles == true,
                exists = { java.io.File(it).exists() },
                canList = { java.io.File(it).list() != null },
            ),
        )
    }

    private suspend fun refreshNow() {
        val p = probe()
        appInfoOpened?.let { (pkg, before) ->
            // Measured, never estimated (SAFE-10).
            _state.update { it.copy(perAppFreed = it.perAppFreed + (pkg to (p.free - before).coerceAtLeast(0))) }
            appInfoOpened = null
        }
        val before = _state.value
        _state.update {
            it.copy(
                grantedFolders = p.granted,
                totalBytes = p.total,
                freeBytes = p.free,
                mediaAccess = p.media,
                allFiles = p.allFiles,
                spaceMode = SpaceMode.of(p.free),
                cloned = p.cloned,
            )
        }
        val after = _state.value
        val gainedAccess = before.mediaAccess != after.mediaAccess || before.allFiles != after.allFiles ||
            before.cloned.readable != after.cloned.readable ||
            before.grantedFolders != after.grantedFolders
        val canScan = after.mediaAccess != MediaAccess.NONE || after.grantedFolders.isNotEmpty()
        // E13: a scan the user cancelled is not started again by coming back to KeepClear.
        if (canScan && !scanCancelled && (after.scan == ScanState.Idle || gainedAccess)) scan()
        if (after.allFiles == true || after.grantedFolders.isNotEmpty()) loadTrash()
    }

    fun scan() {
        scanCancelled = false
        scanJob?.cancel()
        val s = _state.value
        // M12b: without All files access, granted folders (WhatsApp's) are walked at their real paths.
        val persist = s.spaceMode.persistScanResults
        scanJob = viewModelScope.launch {
            // D5: never the backup drives or KeepClear's backup folders.
            val excluded = backupScanExclusions(app)
            val roots =
                ScanRoots.of(s.allFiles == true, app.volumeRoots(), s.cloned.readable, s.grantedFolders, excluded)
            val scanner = backupAwareScanner(app, excluded)
            _state.update { it.copy(scan = ScanState.Running(0, 0)) }
            // LOW-3/LOW-4: a database write failure (e.g. disk full) never crashes the scan; it continues in
            // memory and the result is shown as incomplete.
            var runId = if (persist) runCatching { app.scanIndex.startRun() }.getOrNull() else null
            var storageFailed = persist && runId == null
            // Every item of this run, persisted or not: classification uses the whole run even when the index
            // fell back to memory halfway through (the index is for diffs and history, not the source of truth here).
            val runItems = mutableListOf<ScanItem>()
            // E1: the walk itself runs off the main thread; only its events arrive here.
            scanner.scan(roots).flowOn(Dispatchers.IO).collect { event ->
                when (event) {
                    is ScanEvent.Batch -> {
                        val id = runId
                        val written = id != null && runCatching { app.scanIndex.write(id, event.items) }.isSuccess
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        if (id != null && !written) {
                            runId = null
                            storageFailed = true
                        }
                        runItems += event.items
                    }

                    is ScanEvent.Progress -> {
                        _state.update { it.copy(scan = ScanState.Running(event.files, event.bytes)) }
                    }

                    is ScanEvent.Done -> {
                        val id = runId
                        val finished = id == null || runCatching {
                            app.scanIndex.finish(
                                id,
                                event.complete && !storageFailed,
                                event.files,
                                event.bytes,
                                event.maxGeneration,
                            )
                        }.isSuccess
                        // E13: runCatching above also catches a cancellation (the user cancelled the scan while the
                        // index was being written); a cancelled scan never reports itself as done.
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        // If finalising the index failed, the stale-entry cleanup didn't happen: say so.
                        val complete = event.complete && !storageFailed && finished
                        _state.update { it.copy(scan = ScanState.Done(event.files, event.bytes, complete)) }
                        classify(runItems.toList())
                    }

                    is ScanEvent.Unreadable, is ScanEvent.VolumeLost -> {
                        // Listed per directory from M5 on; the summary only needs counts.
                    }
                }
            }
        }
    }

    /** Why each redundant item is redundant (H2); goes into the plan so the executor can re-check it. */
    private var evidence: Map<String, Evidence> = emptyMap()

    /** PR #27 review: a classification started earlier must never overwrite a newer one's result. */
    private val classifyRuns = LatestRun()

    private suspend fun classify(items: List<ScanItem>) {
        lastItems = items
        val run = classifyRuns.start()
        val classifier = classifier()
        val (summary, ev) = withContext(Dispatchers.Default) {
            classifyRun(app.localGateway, classifier, items, System.currentTimeMillis(), app.hashCache)
        }
        if (!classifyRuns.isLatest(run)) return
        evidence = ev
        // SET-3: the reminder can say how much is ready to clean without scanning in the background.
        withContext(Dispatchers.IO) {
            runCatching { app.reminderPrefs.lastJunkBytes = summary.phoneTotal(Category.SAFE).bytes }
        }
        // M12: what is in Android's own trash right now (MediaStore IS_TRASHED), for the Trash screen.
        val androidTrash = items.filter { it.isTrashed && it.ref is KPath.Media }
        _state.update { it.copy(summary = summary, trash = it.trash.copy(android = androidTrash)) }
    }

    @Volatile private var trashRecovered = false

    /** E5: the newest trash load; a newer one replaces it (latest wins). */
    private var trashLoad: Job? = null

    /**
     * E5: what each trash entry needs a file check for (where it would go back, whether it changed in the trash),
     * worked out once per entry and kept until the next trash action, so coming back to KeepClear doesn't check
     * thousands of files again. Restoring re-checks every item anyway (content check, executor).
     */
    private val trashDetails = java.util.concurrent.ConcurrentHashMap<String, TrashDetail>()

    private class TrashDetail(val restoreTo: String?, val changed: Boolean)

    /**
     * Loads the trash; on the first call per process also recovers it (E-DEL-8/9). E5: off the main thread, one load
     * at a time (a newer call replaces an older one), no file check of entries already known.
     */
    fun loadTrash() {
        trashLoad?.cancel()
        trashLoad = viewModelScope.launch {
            // D6: a full disk makes Room throw (SQLiteFullException). The Trash then keeps what it showed; never a crash.
            try {
                val loaded = withContext(Dispatchers.IO) { loadTrashNow() }
                _state.update {
                    it.copy(
                        trash = loaded.copy(android = it.trash.android, releaseHeldBack = it.trash.releaseHeldBack),
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("KeepClear-Trash", "trash not loaded: ${e.javaClass.simpleName}")
            }
        }
    }

    private suspend fun loadTrashNow(): TrashUi {
        val manager = app.trashManager
        if (!trashRecovered) {
            manager.recover()
            if (!app.testMode.isOn) manager.purgeExpired()
            trashRecovered = true
        }
        val entries = manager.entries().sortedByDescending { it.deletedAt }
        val details = HashMap<String, TrashDetail>(entries.size * 2)
        for (e in entries) {
            details[e.id] = trashDetails.getOrPut(e.id) {
                val dest = manager.restoreDestination(e)
                TrashDetail(dest.takeIf { it != e.originalPath }, manager.isChanged(e))
            }
        }
        trashDetails.keys.retainAll(details.keys)
        val destinations = details.mapNotNull { (id, d) -> d.restoreTo?.let { id to it } }.toMap()
        // H1: "Release now" only where the MOVE record says the copy passed the second, uncached check.
        val confirmed = app.moveController.store.let { store ->
            store.jobs().flatMap { store.files(it.id) }
                .filter { it.state == io.github.tomerar.keepclear.engine.move.MoveFileState.VERIFIED }
                .filter { it.confirmedAt != null }
                .map { it.sourcePath }
                .toSet()
        }
        val releasable = entries.filter { it.verifiedCopy != null && it.originalPath in confirmed }.map { it.id }
        // Owner decision 1.2: a size check here (cheap); the content check runs when restoring.
        val changed = details.filterValues { it.changed }.keys + changedFound
        return TrashUi(entries, destinations, releasable.toSet(), changed)
    }

    /**
     * Restore or permanently delete trash entries through the executor. [confirmation] is the user's tap in the
     * Trash UI, made from the entries' original paths (review PR B).
     */
    fun restore(entries: List<TrashEntry>, confirmation: Confirmation) {
        if (_state.value.busy) return
        val origin = currentScreen()
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            // Owner decision 1.2: a changed item is never put back in place; it is marked and offered separately.
            // E1: the content check reads whole files, never on the main thread.
            val changed = withContext(Dispatchers.IO) {
                entries.filter { app.trashManager.isChanged(it, fullCheck = true) }
            }
            changedFound += changed.map { it.id }
            val changedIds = changed.map { it.id }.toSet()
            runTrashPlanNow(entries.filter { it.id !in changedIds }, OpKind.RESTORE, confirmation, origin)
        }
    }

    /** Ids found changed while restoring (content check), kept so the Trash keeps showing them. */
    private val changedFound = mutableSetOf<String>()

    /** M12: put items back from Android's trash, or delete them now; Android confirms each request. */
    fun androidTrash(items: List<ScanItem>, restore: Boolean, confirmation: Confirmation) {
        if (items.isEmpty() || _state.value.busy) return
        val origin = currentScreen()
        val kind = if (restore) OpKind.RESTORE else OpKind.PURGE
        viewModelScope.launch {
            val ops = items.map {
                PlannedOp(it.ref, it.path, kind, false, it.size, it.mtimeMillis, null, shownAs = it.path)
            }
            val plan = ActionPlan(UUID.randomUUID().toString(), ops, kind.name).confirm(confirmation, app.sessionId)
            val report = withContext(Dispatchers.IO) { app.executor().run(plan) } as? ExecutionReport.Ran
                ?: return@launch
            if (report.consent.isNotEmpty()) {
                _state.update { it.copy(consent = MediaConsent(report.consent, origin)) }
            }
        }
    }

    /** Owner decision 1.2: restore a changed item into `KeepClear/Restored-changed/` (user confirmed). */
    fun restoreChanged(entry: TrashEntry, confirmation: Confirmation) =
        runTrashPlan(listOf(entry), OpKind.RESTORE_CHANGED, confirmation)

    /** Duplicate-group sizes of the plan the held-back items came from (SAFETY-8 still applies to them). */
    private var lastCopyGroupSizes: Map<String, Int> = emptyMap()

    /** Owner decision 1.3: the user confirmed these are the last copies; trash them. */
    fun confirmLastCopies(confirmation: Confirmation) {
        val ops = _state.value.lastCopy ?: return
        _state.update { it.copy(lastCopy = null) }
        viewModelScope.launch {
            val plan = lastCopyPlan(UUID.randomUUID().toString(), ops, lastCopyGroupSizes)
                .confirm(confirmation, app.sessionId)
            val report = withContext(Dispatchers.IO) { app.executor().run(plan) }
            if (report is ExecutionReport.Rejected) {
                _state.update {
                    it.copy(
                        cleanResult = CleanResult(Total(), skipped = 0, failed = ops.size, trashedPaths = emptyList()),
                    )
                }
            }
            trashDetails.clear()
            loadTrash()
            scan()
        }
    }

    fun dismissLastCopies() = _state.update { it.copy(lastCopy = null) }

    fun purge(entries: List<TrashEntry>, confirmation: Confirmation) = runTrashPlan(entries, OpKind.PURGE, confirmation)

    /**
     * D6: "Release now/all". Right before the permanent delete, every backup copy is re-checked on its backup place
     * (size and SHA-256 read back now, C52). Only originals whose copy is intact are purged; the others stay in the
     * trash and the Trash says how many.
     */
    fun release(entries: List<TrashEntry>, confirmation: Confirmation) {
        if (entries.isEmpty() || _state.value.busy) return
        _state.update { it.copy(busy = true, trash = it.trash.copy(releaseHeldBack = 0)) }
        viewModelScope.launch {
            val intact = try {
                withContext(Dispatchers.IO) { intactCopies(entries.filter { it.verifiedCopy != null }) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("KeepClear-Trash", "release check failed: ${e.javaClass.simpleName}")
                emptySet()
            }
            // PR #43 review: the check is keyed by the trashed content itself; no recorded SHA-256 → not released.
            val (go, keep) = entries.partition { e -> releaseKey(e)?.let { it in intact } == true }
            _state.update { it.copy(busy = false, trash = it.trash.copy(releaseHeldBack = keep.size)) }
            if (go.isNotEmpty()) runTrashPlan(go, OpKind.PURGE, confirmation)
        }
    }

    private fun releaseKey(e: TrashEntry) = e.sha256?.takeIf { e.verifiedCopy != null }?.let {
        io.github.tomerar.keepclear.engine.move.ReleaseKey(e.originalPath, e.size, it)
    }

    private suspend fun intactCopies(
        entries: List<TrashEntry>,
    ): Set<io.github.tomerar.keepclear.engine.move.ReleaseKey> {
        if (entries.isEmpty()) return emptySet()
        val store = app.moveController.store
        val check = io.github.tomerar.keepclear.engine.move.ReleaseCheck(store, store.jobs()) { job ->
            val ref = job.targetRef ?: return@ReleaseCheck null
            (app.openTargetRef(ref, job.targetLabel) as? OpenedTarget.Ok)?.target
        }
        return check.intact(entries.mapNotNull { releaseKey(it) })
    }

    private fun runTrashPlan(entries: List<TrashEntry>, kind: OpKind, confirmation: Confirmation) {
        if (entries.isEmpty()) {
            loadTrash()
            return
        }
        if (_state.value.busy) return
        val origin = currentScreen()
        _state.update { it.copy(busy = true) }
        viewModelScope.launch { runTrashPlanNow(entries, kind, confirmation, origin) }
    }

    /** Runs a trash plan; the caller has set [HomeUiState.busy]. E1: every file access off the main thread. */
    private suspend fun runTrashPlanNow(
        entries: List<TrashEntry>,
        kind: OpKind,
        confirmation: Confirmation,
        origin: Screen,
    ) {
        val before = freeNow()
        withContext(Dispatchers.IO) {
            // Entries whose file vanished are removed from the list rather than silently skipped (TRASH-6). A database
            // error here (D6, disk full) only means they stay listed; the executor re-checks each one anyway.
            try {
                app.trashManager.forgetMissing(entries)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("KeepClear-Trash", "missing entries not dropped: ${e.javaClass.simpleName}")
            }
            val ops = entries.mapNotNull { e ->
                val stat = app.localGateway.stat(KPath.Local(e.trashedPath)) ?: return@mapNotNull null
                PlannedOp(
                    stat.path,
                    e.trashedPath,
                    kind,
                    stat.isDirectory,
                    stat.size,
                    stat.mtimeMillis,
                    stat.identity,
                    // The Trash shows (and the user confirms) items by their original path.
                    shownAs = e.originalPath,
                )
            }
            if (ops.isNotEmpty()) {
                val plan = ActionPlan(UUID.randomUUID().toString(), ops, kind.name).confirm(confirmation, app.sessionId)
                app.executor().run(plan)
            }
        }
        val after = freeNow()
        trashDetails.clear()
        _state.update {
            it.copy(
                busy = false,
                // Review PR C: a measured result after emptying, on the screen it came from.
                measured = if (kind == OpKind.PURGE) {
                    Measured((after - before).coerceAtLeast(0), after, before, origin)
                } else {
                    it.measured
                },
            )
        }
        refreshNow()
        loadTrash()
        if (kind == OpKind.RESTORE || kind == OpKind.RESTORE_CHANGED) scan()
    }

    /** SAFE-1 / REV-3: move the confirmed items to the trash through the executor. */
    fun clean(items: List<ClassifiedItem>, confirmation: Confirmation) {
        if (_state.value.busy) return
        val origin = currentScreen()
        _state.update { it.copy(busy = true, cleanResult = null) }
        viewModelScope.launch {
            // Files KeepClear can reach by path go to its folder trash (ADR 0010); MediaStore-only items (`play`, or
            // no All files access) go to Android's own trash after Android's confirmation (M12). SAF items: later.
            // Owner decision 2026-11-02: a row already in Android's trash is deleted for good, with Android's consent.
            val (local, unsupported) = items.partition { it.item.ref is KPath.Local || it.item.ref is KPath.Media }
            // SAFETY-6/8, H2: built from the scan snapshot with duplicate groups and witnesses (CleanPlanner).
            val unconfirmed = CleanPlanner.plan(UUID.randomUUID().toString(), local, evidence, "clean")
            val ops = unconfirmed.ops
            var done = emptyList<PlannedOp>()
            var forGood = emptyList<PlannedOp>()
            var simulated = false
            // Owner decision 2026-11-02: what is deleted for good is measured (StatFs before and after, off the main
            // thread); what goes to the trash frees nothing until the trash is emptied.
            val before = freeNow()
            var skipped = unsupported.size
            var protectedSkipped = 0
            var failed = 0
            var trashedIds = emptySet<String>()
            if (ops.isNotEmpty()) {
                val plan = unconfirmed.confirm(confirmation, app.sessionId)
                // E13: the trash entries this clean adds are told apart from older ones, so Undo restores only them.
                val earlier = trashIds()
                // E1: the executor re-checks and may hash (witnesses); never on the main thread.
                when (val report = withContext(Dispatchers.IO) { app.executor().run(plan) }) {
                    is ExecutionReport.Ran -> {
                        val wouldHave = report.results.filterIsInstance<OpResult.Simulated>().map { it.op }
                        simulated = wouldHave.isNotEmpty()
                        done = report.done.map { it.op } + wouldHave
                        forGood = done.filter { op -> op.kind == OpKind.DELETE_FOR_GOOD }
                        // M12: Android asks the user itself; the result comes back in consentResult().
                        if (report.consent.isNotEmpty()) {
                            _state.update { it.copy(consent = MediaConsent(report.consent, origin)) }
                        }
                        val lastCopies = report.skipped.filter {
                            it.reason == io.github.tomerar.keepclear.core.safety.SkipReason.LAST_COPY
                        }.map { it.op }
                        if (lastCopies.isNotEmpty()) {
                            lastCopyGroupSizes = plan.duplicateGroupSizes
                            _state.update { it.copy(lastCopy = lastCopies) }
                        }
                        // D5: a PROTECTED item is left alone with its own reason; the rest of the plan ran.
                        protectedSkipped = report.skipped.count {
                            it.reason == io.github.tomerar.keepclear.core.safety.SkipReason.PROTECTED
                        }
                        skipped += report.skipped.size - lastCopies.size - protectedSkipped
                        failed = report.failed.size
                        if (!simulated && earlier != null) {
                            val paths = report.done.filter { it.op.kind != OpKind.DELETE_FOR_GOOD }
                                .map { it.op.displayPath }.toSet()
                            trashedIds = withContext(Dispatchers.IO) {
                                runCatching {
                                    app.trashManager.entries()
                                        .filter { it.id !in earlier && it.originalPath in paths }
                                        .map { it.id }
                                        .toSet()
                                }.getOrDefault(emptySet())
                            }
                        }
                    }

                    is ExecutionReport.Rejected -> {
                        failed = ops.size
                    }
                }
            }
            val after = freeNow()
            trashDetails.clear()
            _state.update {
                it.copy(
                    cleanResult = CleanResult(
                        done = Total(done.size, done.sumOf { op -> op.expectedSize }),
                        skipped = skipped,
                        failed = failed,
                        // Nothing to undo in test mode (nothing was moved), nor for what was deleted for good.
                        trashedPaths = if (simulated) {
                            emptyList()
                        } else {
                            done.filter { op -> op.kind != OpKind.DELETE_FOR_GOOD }.map { op -> op.displayPath }
                        },
                        deletedForGood = Total(forGood.size, forGood.sumOf { op -> op.expectedSize }),
                        measured = Measured((after - before).coerceAtLeast(0), after, before, origin)
                            .takeIf { forGood.isNotEmpty() && !simulated },
                        protectedSkipped = protectedSkipped,
                        simulated = simulated,
                        origin = origin,
                        trashedIds = trashedIds,
                    ),
                    busy = false,
                )
            }
            refreshNow()
            loadTrash()
            scan()
        }
    }

    /** Ids in KeepClear's trash right now; null when the index can't be read (then Undo offers nothing). */
    private suspend fun trashIds(): Set<String>? = withContext(Dispatchers.IO) {
        runCatching { app.trashManager.entries().map { it.id }.toSet() }.getOrNull()
    }

    /**
     * M12: builds Android's request for the next dialog right before it shows (building a second request of the same
     * kind earlier would cancel the first). Null when it can't be built; that batch is then reported as failed.
     */
    suspend fun nextConsentRequest(): Any? {
        val head = _state.value.consent?.requests?.firstOrNull() as? MediaBatch ?: return null
        return try {
            app.mediaRequests.build(head)
        } catch (e: io.github.tomerar.keepclear.core.storage.StorageException) {
            android.util.Log.w("KeepClear", "consent request not built: ${e.javaClass.simpleName}")
            consentResult(approved = false, couldNotShow = true)
            null
        } catch (e: IllegalArgumentException) {
            // Android rejects rows it can't put in its trash; never let that crash the app.
            android.util.Log.w("KeepClear", "consent request rejected by Android: ${e.javaClass.simpleName}")
            consentResult(approved = false, couldNotShow = true)
            null
        }
    }

    /** E12: Android shows the dialog of request [id] now; it is not launched again after a configuration change. */
    fun consentLaunched(id: Int) = _state.update { s ->
        val c = s.consent
        if (c != null && c.headId == id) s.copy(consent = c.copy(launchedId = id)) else s
    }

    /**
     * M12: the user answered Android's dialog for the first pending request. When the last one is answered, the
     * result counts only the approved dialogs' items; a rescan then shows what really changed. E12: an answer counts
     * only while that request's dialog is showing; a late or repeated result is ignored.
     */
    fun consentResult(approved: Boolean, couldNotShow: Boolean = false) {
        val c = _state.value.consent ?: return
        if (!couldNotShow && c.launchedId != c.headId) return
        val next = c.answer(approved, couldNotShow)
        if (next.requests.isNotEmpty()) {
            _state.update { it.copy(consent = next) }
            return
        }
        _state.update { it.copy(consent = null, cleanResult = next.result()) }
        refresh()
        scan()
    }

    /**
     * ONB-6: the user picked a folder in the system picker. Only WhatsApp's own folder (with `Media/` inside) is
     * kept; anything else is let go again and the guide offers to retry (never a silent failure).
     */
    fun whatsAppFolderPicked(uri: android.net.Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { isWhatsAppFolder(uri) }
            val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            if (ok) {
                runCatching { app.contentResolver.takePersistableUriPermission(uri, flags) }
            }
            _state.update { it.copy(wrongFolder = !ok) }
            refreshNow()
        }
    }

    fun dismissWrongFolder() = _state.update { it.copy(wrongFolder = false) }

    private suspend fun isWhatsAppFolder(uri: android.net.Uri): Boolean {
        if (!android.provider.DocumentsContract.isTreeUri(uri)) return false
        val id = android.provider.DocumentsContract.getTreeDocumentId(uri)
        if (!io.github.tomerar.keepclear.core.storage.saf.GrantedFolders.isWhatsAppFolder(id)) return false
        if (io.github.tomerar.keepclear.core.storage.saf.GrantedFolders.mountPoint(uri.authority.orEmpty(), id) ==
            null
        ) {
            return false
        }
        val root = KPath.Saf(uri.toString(), id)
        return runCatching {
            app.safGateway.list(root).toList().any { it.isDirectory && it.name == "Media" }
        }.getOrDefault(false)
    }

    /**
     * E-DEL-15: Undo restores what the last clean moved to the trash. E13: only that: the entries it added
     * ([CleanResult.trashedIds]), never an older entry from the same place.
     */
    fun undo(result: CleanResult, confirmation: Confirmation) {
        if (_state.value.busy) return
        viewModelScope.launch {
            val ids = result.trashedIds
            val entries = withContext(Dispatchers.IO) { app.trashManager.entries() }.filter { it.id in ids }
            _state.update { it.copy(cleanResult = null) }
            restore(entries, confirmation)
        }
    }

    /** SAFE-5: ask Android to trim other apps' caches; report the measured difference only. */
    fun trimCaches() {
        if (app.testMode.isOn) {
            android.util.Log.i(TestMode.TAG, "would ask Android to clear app caches")
            _state.update { it.copy(trimResult = TestMode.TRIM_SKIPPED) }
            return
        }
        if (_state.value.busy) return
        val origin = currentScreen()
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val before = freeNow()
            withContext(Dispatchers.IO) {
                val sm = app.getSystemService(StorageManager::class.java)
                runCatching {
                    sm.allocateBytes(StorageManager.UUID_DEFAULT, sm.getAllocatableBytes(StorageManager.UUID_DEFAULT))
                }
            }
            val after = freeNow()
            val freed = (after - before).coerceAtLeast(0)
            _state.update {
                it.copy(
                    busy = false,
                    trimResult = freed,
                    measured = if (freed > 0) Measured(freed, after, before, origin) else null,
                )
            }
            refreshNow()
        }
    }

    /**
     * Results belong to the screen they came from; leaving it clears them, so no stale Undo shows elsewhere. E13: a
     * second tap on the same thing (a double tap) doesn't open it twice. F1b: a tab's root switches to that tab.
     */
    fun open(screen: Screen) = _state.update { Nav.open(it, screen) }

    /** E4: leaving a group with Back ends its selection; at a tab's root Back goes to the first tab ([Nav.back]). */
    fun back() = _state.update { Nav.back(it) }

    /** F1b: show [tab] where it was left (the shown tab again: back to its root). */
    fun selectTab(tab: Tab) = _state.update { Nav.selectTab(it, tab) }

    /** E4: the selection of the group [key] ([GroupKey]). */
    fun select(key: String, paths: Set<String>) = _state.update {
        it.copy(selections = if (paths.isEmpty()) it.selections - key else it.selections + (key to paths))
    }

    /** E4, E11: the one-by-one review of group [key]; null when it closes. */
    fun swipe(key: String, decisions: List<SwipeDecision>?) = _state.update {
        it.copy(swipes = if (decisions == null) it.swipes - key else it.swipes + (key to decisions))
    }

    private suspend fun freeNow(): Long = withContext(Dispatchers.IO) { phoneFreeBytes(app) }

    private fun currentScreen(): Screen = _state.value.screens.last()

    fun cancelScan() {
        scanCancelled = true
        scanJob?.cancel()
        _state.update { it.copy(scan = ScanState.Idle) }
    }
}

/** What Home scans; the device check (test builds) scans the same roots. */
object ScanRoots {
    /**
     * With All files access: every mounted volume plus readable cloned-app storage (MSG-3, same rules). Without it
     * (M12b): granted folders (WhatsApp's) at their real paths; MediaStore adds the rest.
     */
    fun of(
        allFiles: Boolean,
        volumes: List<String>,
        clonedReadable: List<String>,
        granted: List<String>,
        /** D5: backup drives and folders ([BackupRoots.scanExclusions]); a volume among them is not walked. */
        excluded: List<String> = emptyList(),
    ): List<KPath.Local> {
        val roots = if (allFiles) (volumes + clonedReadable).distinct() else granted
        val out = excluded.map { it.lowercase(java.util.Locale.ROOT) }
        return roots.filterNot { root ->
            val r = root.lowercase(java.util.Locale.ROOT)
            out.any { r == it || r.startsWith("$it/") }
        }.map { KPath.Local(it) }
    }
}

/** D5: every known backup folder and the drive it is on; read from the MOVE jobs (off the main thread). */
suspend fun backupScanExclusions(app: KeepClearApplication): List<String> =
    BackupRoots.scanExclusions(BackupRoots.of(withContext(Dispatchers.IO) { app.moveController.store.jobs() }))

/** D5: a scanner that skips [excluded] and any folder holding a backup place's marker file. */
fun backupAwareScanner(app: KeepClearApplication, excluded: List<String>) = Scanner(
    app.localGateway,
    app.mediaSource,
    excluded = excluded,
    skipFoldersWith = io.github.tomerar.keepclear.backup.saf.SafTargets.MARKER,
)

/**
 * CLS-7: Home's classifier, with the user's kept and always-junk choices (shared with the device check). D5: it
 * protects the same backup folders as the executor ([KeepClearApplication.executor]), so a backup copy is never
 * offered for anything even if a scan reached it.
 */
suspend fun homeClassifier(app: KeepClearApplication): Classifier {
    val jobs = withContext(Dispatchers.IO) { app.moveController.store.jobs() }
    val choices = app.userChoices.state.value
    return Classifier(
        protectedPaths = BackupRoots.protectedPaths(jobs, app.packageName),
        keptByUser = choices.kept,
        junkFolders = choices.junk,
    )
}

/** Home's classification of one scan run: second-pass facts (twins, extracted archives; M7), then the rules. */
suspend fun classifyRun(
    gateway: io.github.tomerar.keepclear.core.storage.StorageGateway,
    classifier: Classifier,
    items: List<ScanItem>,
    now: Long,
    /** E13: full hashes of earlier runs, by path, size and date; null hashes every candidate again. */
    hashCache: io.github.tomerar.keepclear.engine.enrich.HashCache? = null,
): Pair<ClassificationSummary, Map<String, Evidence>> {
    // Only for files KeepClear can read by path.
    val enrichment = runCatching {
        FactEnricher(gateway, hashCache).enrich(items.filter { it.ref is KPath.Local })
    }
        .getOrDefault(Enrichment(emptyMap(), emptyMap()))
    return ClassificationSummary.of(
        items.map { classifier.classify(it, enrichment.facts[it.path].orEmpty(), now) },
    ) to enrichment.evidence
}

/** Owner decision 1.3: the plan for items the user confirmed as last copies, keeping their group sizes (SAFETY-8). */
fun lastCopyPlan(id: String, ops: List<PlannedOp>, groupSizes: Map<String, Int>): ActionPlan {
    val groups = ops.mapNotNull { it.duplicateGroup }.toSet()
    return ActionPlan(
        id,
        ops.map { it.copy(lastCopyConfirmed = true) },
        "clean",
        duplicateGroupSizes = groupSizes.filterKeys { it in groups },
    )
}

/** Numbers each run so only the newest one may publish its result (main thread only). */
class LatestRun {
    private var latest = 0L

    fun start(): Long = ++latest

    fun isLatest(run: Long) = run == latest
}
