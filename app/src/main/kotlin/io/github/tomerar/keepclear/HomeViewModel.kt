// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.app.Application
import android.os.StatFs
import android.os.storage.StorageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.tomerar.keepclear.core.model.Category
import io.github.tomerar.keepclear.core.safety.ActionPlan
import io.github.tomerar.keepclear.core.safety.OpKind
import io.github.tomerar.keepclear.core.safety.PlannedOp
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.core.storage.media.MediaAccess
import io.github.tomerar.keepclear.engine.classify.ClassificationSummary
import io.github.tomerar.keepclear.engine.classify.Classifier
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
            val inMemory = mutableListOf<ScanItem>()
            scanner.scan(roots).collect { event ->
                when (event) {
                    is ScanEvent.Batch -> {
                        val id = runId
                        val written = id != null && runCatching { app.scanIndex.write(id, event.items) }.isSuccess
                        if (id != null && !written) {
                            runId = null
                            storageFailed = true
                        }
                        if (!written) inMemory += event.items
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
                        val items = if (id != null) null else inMemory.toList()
                        _state.update { it.copy(scan = ScanState.Done(event.files, event.bytes, complete)) }
                        classify(items)
                    }

                    is ScanEvent.Unreadable, is ScanEvent.VolumeLost -> {
                        // Listed per directory from M5 on; the summary only needs counts.
                    }
                }
            }
        }
    }

    private suspend fun classify(inMemory: List<ScanItem>?) {
        val summary = withContext(Dispatchers.Default) {
            val items = inMemory ?: app.scanIndex.previous().values.toList()
            val now = System.currentTimeMillis()
            ClassificationSummary.of(items.map { classifier.classify(it, now = now) })
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
                manager.purgeExpired()
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
