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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    /** Navigation stack; last is visible. */
    val screens: List<Screen> = listOf(Screen.Home),
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
)

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
) {
    /** The user answered the first request (or it could not be shown: [couldNotShow]). */
    fun answer(approved: Boolean, couldNotShow: Boolean = false): MediaConsent {
        val head = requests.firstOrNull() ?: return this
        return copy(
            requests = requests.drop(1),
            approved = if (approved) this.approved + head.ops else this.approved,
            declined = declined + if (!approved && !couldNotShow) head.ops.size else 0,
            failed = failed + if (couldNotShow) head.ops.size else 0,
        )
    }

    fun result() = CleanResult(
        done = Total(approved.size, approved.sumOf { it.expectedSize }),
        skipped = declined,
        failed = failed,
        trashedPaths = emptyList(),
        origin = origin,
        androidTrash = approved.isNotEmpty(),
        declined = approved.isEmpty() && failed == 0,
    )
}

/** "Freed 7.9 GB · now 24.7 GB free (was 16.8 GB)", shown on the screen the action came from. */
data class Measured(val freed: Long, val nowFree: Long, val wasFree: Long, val origin: Screen)

/** Simple in-memory navigation (no navigation library needed yet). */
sealed interface Screen {
    data object Home : Screen

    data class CategoryList(val category: Category) : Screen

    data class Group(val ruleId: String, val category: Category) : Screen

    data class Preview(val path: String, val ruleId: String, val category: Category) : Screen

    data object Trash : Screen

    data object Settings : Screen

    /** SAFE-9: other apps' own folders, per app. */
    data object AppContent : Screen

    /** SAFE-10: clear one app at a time. */
    data object OneAppAtATime : Screen

    /** M13: apps by size and last use. */
    data object Apps : Screen
}

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as KeepClearApplication
    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state
    private var scanJob: Job? = null

    /** CLS-7: the user's kept and always-junk choices apply to every classification. */
    private fun classifier(): Classifier = app.userChoices.state.value.let {
        Classifier(protectedPaths = app.protectedPaths, keptByUser = it.kept, junkFolders = it.junk)
    }

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
        appInfoOpened = pkg to freeNow()
    }

    fun setApps(apps: List<AppEntry>) = _state.update { it.copy(apps = apps) }

    fun setAppsInfo(apps: List<io.github.tomerar.keepclear.engine.classify.AppInfo>?) =
        _state.update { it.copy(appsInfo = apps) }

    /** Called on every resume: permissions and free space can change outside the app. */
    fun refresh() {
        val stat = StatFs(app.filesDir.absolutePath)
        val free = stat.availableBytes
        appInfoOpened?.let { (pkg, before) ->
            // Measured, never estimated (SAFE-10).
            _state.update { it.copy(perAppFreed = it.perAppFreed + (pkg to (free - before).coerceAtLeast(0))) }
            appInfoOpened = null
        }
        val before = _state.value
        app.refreshGrantedFolders()
        _state.update {
            it.copy(
                grantedFolders = app.grantedFolders.keys.sorted(),
                totalBytes = stat.totalBytes,
                freeBytes = free,
                mediaAccess = MediaAccess.current(app),
                allFiles = if (BuildConfig.FLAVOR == "foss") MediaAccess.hasAllFiles() else null,
                spaceMode = SpaceMode.of(free),
            )
        }
        val after = _state.value
        val gainedAccess = before.mediaAccess != after.mediaAccess || before.allFiles != after.allFiles ||
            before.grantedFolders != after.grantedFolders
        val canScan = after.mediaAccess != MediaAccess.NONE || after.grantedFolders.isNotEmpty()
        if (canScan && (after.scan == ScanState.Idle || gainedAccess)) scan()
        if (after.allFiles == true || after.grantedFolders.isNotEmpty()) loadTrash()
    }

    fun scan() {
        scanJob?.cancel()
        val s = _state.value
        // M12b: without All files access, granted folders (WhatsApp's) are walked at their real paths.
        val roots = if (s.allFiles == true) storageRoots() else s.grantedFolders.map { KPath.Local(it) }
        val scanner = Scanner(app.localGateway, app.mediaSource)
        val persist = s.spaceMode.persistScanResults
        scanJob = viewModelScope.launch {
            _state.update { it.copy(scan = ScanState.Running(0, 0)) }
            // LOW-3/LOW-4: a database write failure (e.g. disk full) never crashes the scan; it continues in
            // memory and the result is shown as incomplete.
            var runId = if (persist) runCatching { app.scanIndex.startRun() }.getOrNull() else null
            var storageFailed = persist && runId == null
            // Every item of this run, persisted or not: classification uses the whole run even when the index
            // fell back to memory halfway through (the index is for diffs and history, not the source of truth here).
            val runItems = mutableListOf<ScanItem>()
            scanner.scan(roots).collect { event ->
                when (event) {
                    is ScanEvent.Batch -> {
                        val id = runId
                        val written = id != null && runCatching { app.scanIndex.write(id, event.items) }.isSuccess
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

    private suspend fun classify(items: List<ScanItem>) {
        lastItems = items
        val classifier = classifier()
        val (summary, ev) = withContext(Dispatchers.Default) {
            val now = System.currentTimeMillis()
            // Second-pass facts (twins, extracted archives) for files KeepClear can read by path (M7).
            val enrichment = runCatching {
                FactEnricher(app.localGateway).enrich(items.filter { it.ref is KPath.Local })
            }
                .getOrDefault(Enrichment(emptyMap(), emptyMap()))
            ClassificationSummary.of(
                items.map { classifier.classify(it, enrichment.facts[it.path].orEmpty(), now) },
            ) to enrichment.evidence
        }
        evidence = ev
        // M12: what is in Android's own trash right now (MediaStore IS_TRASHED), for the Trash screen.
        val androidTrash = items.filter { it.isTrashed && it.ref is KPath.Media }
        _state.update { it.copy(summary = summary, trash = it.trash.copy(android = androidTrash)) }
    }

    private var trashRecovered = false

    /** Loads the trash; on the first call per process also recovers it (E-DEL-8/9). */
    fun loadTrash() {
        viewModelScope.launch {
            val manager = app.trashManager
            if (!trashRecovered) {
                manager.recover()
                if (!app.testMode.isOn) manager.purgeExpired()
                trashRecovered = true
            }
            val entries = manager.entries().sortedByDescending { it.deletedAt }
            val destinations = entries.associate { it.id to manager.restoreDestination(it) }.filter { (id, dest) ->
                dest != entries.first { it.id == id }.originalPath
            }
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
            val changed = entries.filter { manager.isChanged(it) }.map { it.id }.toSet() + changedFound
            _state.update {
                it.copy(trash = TrashUi(entries, destinations, releasable.toSet(), changed, android = it.trash.android))
            }
        }
    }

    /**
     * Restore or permanently delete trash entries through the executor. [confirmation] is the user's tap in the
     * Trash UI, made from the entries' original paths (review PR B).
     */
    fun restore(entries: List<TrashEntry>, confirmation: Confirmation) {
        viewModelScope.launch {
            // Owner decision 1.2: a changed item is never put back in place; it is marked and offered separately.
            val changed = entries.filter { app.trashManager.isChanged(it, fullCheck = true) }
            changedFound += changed.map { it.id }
            runTrashPlan(entries - changed.toSet(), OpKind.RESTORE, confirmation)
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
            val report = app.executor().run(plan) as? ExecutionReport.Ran ?: return@launch
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
            val report = app.executor().run(plan)
            if (report is ExecutionReport.Rejected) {
                _state.update {
                    it.copy(
                        cleanResult = CleanResult(Total(), skipped = 0, failed = ops.size, trashedPaths = emptyList()),
                    )
                }
            }
            loadTrash()
            scan()
        }
    }

    fun dismissLastCopies() = _state.update { it.copy(lastCopy = null) }

    fun purge(entries: List<TrashEntry>, confirmation: Confirmation) = runTrashPlan(entries, OpKind.PURGE, confirmation)

    private fun runTrashPlan(entries: List<TrashEntry>, kind: OpKind, confirmation: Confirmation) {
        if (entries.isEmpty()) {
            loadTrash()
            return
        }
        if (_state.value.busy) return
        val origin = currentScreen()
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val before = freeNow()
            // Entries whose file vanished are removed from the list rather than silently skipped (TRASH-6).
            app.trashManager.forgetMissing(entries)
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
            val after = freeNow()
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
            refresh()
            loadTrash()
            if (kind == OpKind.RESTORE || kind == OpKind.RESTORE_CHANGED) scan()
        }
    }

    /** SAFE-1 / REV-3: move the confirmed items to the trash through the executor. */
    fun clean(items: List<ClassifiedItem>, confirmation: Confirmation) {
        if (_state.value.busy) return
        val origin = currentScreen()
        _state.update { it.copy(busy = true, cleanResult = null) }
        viewModelScope.launch {
            // Files KeepClear can reach by path go to its folder trash (ADR 0010); MediaStore-only items (`play`, or
            // no All files access) go to Android's own trash after Android's confirmation (M12). SAF items: later.
            val (local, unsupported) = items.partition {
                it.item.ref is KPath.Local || (it.item.ref is KPath.Media && !it.item.isTrashed)
            }
            // SAFETY-6/8, H2: built from the scan snapshot with duplicate groups and witnesses (CleanPlanner).
            val unconfirmed = CleanPlanner.plan(UUID.randomUUID().toString(), local, evidence, "clean")
            val ops = unconfirmed.ops
            var done = emptyList<PlannedOp>()
            var simulated = false
            var skipped = unsupported.size
            var failed = 0
            if (ops.isNotEmpty()) {
                val plan = unconfirmed.confirm(confirmation, app.sessionId)
                when (val report = app.executor().run(plan)) {
                    is ExecutionReport.Ran -> {
                        val wouldHave = report.results.filterIsInstance<OpResult.Simulated>().map { it.op }
                        simulated = wouldHave.isNotEmpty()
                        done = report.done.map { it.op } + wouldHave
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
                        skipped += report.skipped.size - lastCopies.size
                        failed = report.failed.size
                    }

                    is ExecutionReport.Rejected -> {
                        failed = ops.size
                    }
                }
            }
            _state.update {
                it.copy(
                    cleanResult = CleanResult(
                        done = Total(done.size, done.sumOf { op -> op.expectedSize }),
                        skipped = skipped,
                        failed = failed,
                        // Nothing to undo in test mode: nothing was moved.
                        trashedPaths = if (simulated) emptyList() else done.map { op -> op.displayPath },
                        simulated = simulated,
                        origin = origin,
                    ),
                    busy = false,
                )
            }
            refresh()
            loadTrash()
            scan()
        }
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

    /**
     * M12: the user answered Android's dialog for the first pending request. When the last one is answered, the
     * result counts only the approved dialogs' items; a rescan then shows what really changed.
     */
    fun consentResult(approved: Boolean, couldNotShow: Boolean = false) {
        val c = _state.value.consent ?: return
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
            refresh()
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

    /** E-DEL-15: Undo restores everything the last clean moved to the trash. */
    fun undo(result: CleanResult, confirmation: Confirmation) {
        viewModelScope.launch {
            val paths = result.trashedPaths.toSet()
            val entries = app.trashManager.entries().filter { it.originalPath in paths }
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
            refresh()
        }
    }

    /** Results belong to the screen they came from; leaving it clears them, so no stale Undo shows elsewhere. */
    fun open(screen: Screen) = _state.update {
        it.copy(screens = it.screens + screen, cleanResult = null, measured = null, trimResult = null)
    }

    fun back() = _state.update {
        if (it.screens.size > 1) {
            it.copy(screens = it.screens.dropLast(1), cleanResult = null, measured = null, trimResult = null)
        } else {
            it
        }
    }

    private fun freeNow(): Long = StatFs(app.filesDir.absolutePath).availableBytes

    private fun currentScreen(): Screen = _state.value.screens.last()

    fun cancelScan() {
        scanJob?.cancel()
        _state.update { it.copy(scan = ScanState.Idle) }
    }

    private fun storageRoots(): List<KPath.Local> {
        val sm = app.getSystemService(StorageManager::class.java)
        return sm.storageVolumes.mapNotNull { v -> v.directory?.takeIf { v.state == "mounted" }?.absolutePath }
            .map { KPath.Local(it) }
    }
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
