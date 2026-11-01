// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.app.Application
import android.os.StatFs
import android.os.storage.StorageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.tomerar.keepclear.core.model.Category
import io.github.tomerar.keepclear.core.safety.ActionPlan
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

    private suspend fun classify(items: List<ScanItem>) {
        val summary = withContext(Dispatchers.Default) {
            val now = System.currentTimeMillis()
            // Second-pass facts (twins, extracted archives) for files KeepClear can read by path (M7).
            val facts = runCatching { FactEnricher(app.fileGateway).facts(items.filter { it.ref is KPath.Local }) }
                .getOrDefault(emptyMap())
            ClassificationSummary.of(items.map { classifier.classify(it, facts[it.path].orEmpty(), now) })
        }
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
            _state.update { it.copy(trash = TrashUi(entries, destinations)) }
        }
    }

    /** Restore or permanently delete trash entries through the executor (user already confirmed in the UI). */
    fun restore(entries: List<TrashEntry>) = runTrashPlan(entries, OpKind.RESTORE)

    fun purge(entries: List<TrashEntry>) = runTrashPlan(entries, OpKind.PURGE)

    private fun runTrashPlan(entries: List<TrashEntry>, kind: OpKind) {
        viewModelScope.launch {
            // Entries whose file vanished are removed from the list rather than silently skipped (TRASH-6).
            app.trashManager.forgetMissing(entries)
            val ops = entries.mapNotNull { e ->
                val stat = app.fileGateway.stat(KPath.Local(e.trashedPath)) ?: return@mapNotNull null
                PlannedOp(stat.path, e.trashedPath, kind, stat.isDirectory, stat.size, stat.mtimeMillis, stat.identity)
            }
            if (ops.isNotEmpty()) {
                val plan = ActionPlan(
                    UUID.randomUUID().toString(),
                    ops,
                    kind.name,
                ).confirm(System.currentTimeMillis(), app.sessionId)
                app.executor().run(plan)
            }
            loadTrash()
            if (kind == OpKind.RESTORE) scan()
        }
    }

    /** SAFE-1 / REV-3: move the confirmed items to the trash through the executor. */
    fun clean(items: List<ClassifiedItem>) {
        viewModelScope.launch {
            // Only path-addressable files can go to KeepClear's trash in this version (ADR 0010). MediaStore-only
            // items (no All files access, `play`) are reported as skipped instead of being acted on.
            val (local, unsupported) = items.partition { it.item.ref is KPath.Local }
            // SAFETY-6: the plan carries what was scanned and classified, not what is there now; the executor
            // re-checks size, mtime and identity against it and skips anything that changed since.
            val ops = local.map { c ->
                PlannedOp(
                    c.item.ref,
                    c.item.path,
                    OpKind.TRASH,
                    false,
                    c.item.size,
                    c.item.mtimeMillis,
                    c.item.identity,
                    ruleId = c.ruleId,
                )
            }
            var done = emptyList<PlannedOp>()
            var simulated = false
            var skipped = unsupported.size
            var failed = 0
            if (ops.isNotEmpty()) {
                val plan = ActionPlan(
                    UUID.randomUUID().toString(),
                    ops,
                    "clean",
                ).confirm(System.currentTimeMillis(), app.sessionId)
                when (val report = app.executor().run(plan)) {
                    is ExecutionReport.Ran -> {
                        val wouldHave = report.results.filterIsInstance<OpResult.Simulated>().map { it.op }
                        simulated = wouldHave.isNotEmpty()
                        done = report.done.map { it.op } + wouldHave
                        skipped += report.skipped.size
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
    fun undo(result: CleanResult) {
        viewModelScope.launch {
            val paths = result.trashedPaths.toSet()
            val entries = app.trashManager.entries().filter { it.originalPath in paths }
            _state.update { it.copy(cleanResult = null) }
            restore(entries)
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
