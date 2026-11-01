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
)

/** Simple in-memory navigation (no navigation library needed yet). */
sealed interface Screen {
    data object Home : Screen

    data class CategoryList(val category: Category) : Screen

    data class Group(val ruleId: String, val category: Category) : Screen

    data class Preview(val path: String, val ruleId: String, val category: Category) : Screen

    data object Trash : Screen

    data object Settings : Screen
}

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as KeepClearApplication
    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state
    private var scanJob: Job? = null
    private val classifier = Classifier()

    /** Called on every resume: permissions and free space can change outside the app. */
    fun refresh() {
        val stat = StatFs(app.filesDir.absolutePath)
        val free = stat.availableBytes
        val before = _state.value
        _state.update {
            it.copy(
                totalBytes = stat.totalBytes,
                freeBytes = free,
                mediaAccess = MediaAccess.current(app),
                allFiles = if (BuildConfig.FLAVOR == "foss") MediaAccess.hasAllFiles() else null,
                spaceMode = SpaceMode.of(free),
            )
        }
        val after = _state.value
        val gainedAccess = (before.mediaAccess != after.mediaAccess || before.allFiles != after.allFiles)
        if (after.mediaAccess != MediaAccess.NONE && (after.scan == ScanState.Idle || gainedAccess)) scan()
        if (after.allFiles == true) loadTrash()
    }

    fun scan() {
        scanJob?.cancel()
        val s = _state.value
        val roots = if (s.allFiles == true) storageRoots() else emptyList()
        val scanner = Scanner(app.fileGateway, app.mediaSource)
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
        val (summary, ev) = withContext(Dispatchers.Default) {
            val now = System.currentTimeMillis()
            // Second-pass facts (twins, extracted archives) for files KeepClear can read by path (M7).
            val enrichment = runCatching {
                FactEnricher(app.fileGateway).enrich(items.filter { it.ref is KPath.Local })
            }
                .getOrDefault(Enrichment(emptyMap(), emptyMap()))
            ClassificationSummary.of(
                items.map { classifier.classify(it, enrichment.facts[it.path].orEmpty(), now) },
            ) to enrichment.evidence
        }
        evidence = ev
        _state.update { it.copy(summary = summary) }
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
            _state.update { it.copy(trash = TrashUi(entries, destinations, releasable.toSet(), changed)) }
        }
    }

<<<<<<< HEAD
    /**
     * Restore or permanently delete trash entries through the executor. [confirmation] is the user's tap in the
     * Trash UI, made from the entries' original paths (review PR B).
     */
    fun restore(entries: List<TrashEntry>, confirmation: Confirmation) {
=======
    /** Restore or permanently delete trash entries through the executor (user already confirmed in the UI). */
    fun restore(entries: List<TrashEntry>) {
>>>>>>> origin/main
        viewModelScope.launch {
            // Owner decision 1.2: a changed item is never put back in place; it is marked and offered separately.
            val changed = entries.filter { app.trashManager.isChanged(it, fullCheck = true) }
            changedFound += changed.map { it.id }
<<<<<<< HEAD
            runTrashPlan(entries - changed.toSet(), OpKind.RESTORE, confirmation)
        }
    }
=======
            runTrashPlan(entries - changed.toSet(), OpKind.RESTORE)
        }
    }

    /** Ids found changed while restoring (content check), kept so the Trash keeps showing them. */
    private val changedFound = mutableSetOf<String>()

    /** Owner decision 1.2: restore a changed item into `KeepClear/Restored-changed/` (user confirmed). */
    fun restoreChanged(entry: TrashEntry) = runTrashPlan(listOf(entry), OpKind.RESTORE_CHANGED)

    /** Duplicate-group sizes of the plan the held-back items came from (SAFETY-8 still applies to them). */
    private var lastCopyGroupSizes: Map<String, Int> = emptyMap()

    /** Owner decision 1.3: the user confirmed these are the last copies; trash them. */
    fun confirmLastCopies() {
        val ops = _state.value.lastCopy ?: return
        _state.update { it.copy(lastCopy = null) }
        viewModelScope.launch {
            val plan = lastCopyPlan(UUID.randomUUID().toString(), ops, lastCopyGroupSizes)
                .confirm(System.currentTimeMillis(), app.sessionId)
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
>>>>>>> origin/main

    /** Ids found changed while restoring (content check), kept so the Trash keeps showing them. */
    private val changedFound = mutableSetOf<String>()

<<<<<<< HEAD
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
=======
    private fun runTrashPlan(entries: List<TrashEntry>, kind: OpKind) {
>>>>>>> origin/main
        if (entries.isEmpty()) {
            loadTrash()
            return
        }
        viewModelScope.launch {
            // Entries whose file vanished are removed from the list rather than silently skipped (TRASH-6).
            app.trashManager.forgetMissing(entries)
            val ops = entries.mapNotNull { e ->
                val stat = app.fileGateway.stat(KPath.Local(e.trashedPath)) ?: return@mapNotNull null
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
            loadTrash()
            if (kind == OpKind.RESTORE || kind == OpKind.RESTORE_CHANGED) scan()
        }
    }

    /** SAFE-1 / REV-3: move the confirmed items to the trash through the executor. */
    fun clean(items: List<ClassifiedItem>, confirmation: Confirmation) {
        viewModelScope.launch {
            // Only path-addressable files can go to KeepClear's trash in this version (ADR 0010). MediaStore-only
            // items (no All files access, `play`) are reported as skipped instead of being acted on.
            val (local, unsupported) = items.partition { it.item.ref is KPath.Local }
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
                    ),
                )
            }
            loadTrash()
            scan()
        }
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
        viewModelScope.launch {
            val freed = withContext(Dispatchers.IO) {
                val sm = app.getSystemService(StorageManager::class.java)
                val before = StatFs(app.filesDir.absolutePath).availableBytes
                runCatching {
                    sm.allocateBytes(StorageManager.UUID_DEFAULT, sm.getAllocatableBytes(StorageManager.UUID_DEFAULT))
                }
                (StatFs(app.filesDir.absolutePath).availableBytes - before).coerceAtLeast(0)
            }
            _state.update { it.copy(trimResult = freed) }
            refresh()
        }
    }

    fun open(screen: Screen) = _state.update { it.copy(screens = it.screens + screen) }

    fun back() = _state.update { if (it.screens.size > 1) it.copy(screens = it.screens.dropLast(1)) else it }

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
