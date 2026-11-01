// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.tomerar.keepclear.backup.saf.SafTargets
import io.github.tomerar.keepclear.backup.saf.TargetCheck
import io.github.tomerar.keepclear.backup.saf.TargetKind
import io.github.tomerar.keepclear.core.safety.ActionPlan
import io.github.tomerar.keepclear.core.safety.ExecutionReport
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.engine.classify.ClassifiedItem
import io.github.tomerar.keepclear.engine.move.MoveEngine
import io.github.tomerar.keepclear.engine.move.MoveFile
import io.github.tomerar.keepclear.engine.move.MoveJob
import io.github.tomerar.keepclear.engine.move.MovePlanner
import io.github.tomerar.keepclear.engine.move.PauseReason
import io.github.tomerar.keepclear.engine.move.Receipt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** A picked drive after pre-flight (MOVE-5), waiting for "Start copying". */
data class PickedTarget(
    val label: String,
    val kind: TargetKind,
    val canReadBack: Boolean,
    val files: List<MoveFile>,
    val issues: List<MovePlanner.Issue>,
    /** Q4: the drive's format when it could be read; UNKNOWN keeps the 4 GB limit. */
    val format: io.github.tomerar.keepclear.backup.saf.DriveFormat =
        io.github.tomerar.keepclear.backup.saf.DriveFormat.UNKNOWN,
) {
    val blocked: Boolean get() = issues.any { it.blocking }
}

enum class TargetProblem {
    ON_THIS_PHONE,
    DAMAGED_MARKER,
    UNAVAILABLE,

    /** The app was closed while the folder picker was open; the selection is gone and must be made again. */
    SELECTION_LOST,
}

data class JobView(val job: MoveJob, val receipt: Receipt, val canReadBack: Boolean) {
    /** MOVE-9: freeing is offered only once every file is settled and at least one copy is verified. */
    val canFree: Boolean get() = canReadBack && receipt.complete && receipt.verified > 0
}

/** Result of freeing originals after a verified copy (MOVE-10). */
data class FreeResult(
    val jobId: String,
    val done: Int,
    val bytes: Long,
    val skipped: Int,
    val failed: Int,
    /** Test mode (SAFETY-9): nothing was moved to the trash; [done]/[bytes] would have been. */
    val simulated: Boolean = false,
)

data class MoveUiState(
    val jobs: List<JobView> = emptyList(),
    val running: String? = null,
    val picked: PickedTarget? = null,
    val problem: TargetProblem? = null,
    val busy: Boolean = false,
    val freeResult: FreeResult? = null,
)

class MoveViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as KeepClearApplication
    private val controller = app.moveController
    private val _state = MutableStateFlow(MoveUiState())
    val state: StateFlow<MoveUiState> = _state
    private var pickedUri: Uri? = null
    private var pickedTarget: io.github.tomerar.keepclear.backup.saf.SafBackupTarget? = null

    init {
        viewModelScope.launch {
            controller.markInterrupted()
            combine(controller.running, controller.ticks) { running, _ -> running }.collect { running ->
                _state.update { it.copy(running = running) }
                reload()
            }
        }
    }

    private suspend fun reload() {
        val store = controller.store
        val jobs = withContext(Dispatchers.IO) {
            store.jobs().map { JobView(it, Receipt.of(it, store.files(it.id)), canReadBack(it)) }
        }
        _state.update { it.copy(jobs = jobs) }
    }

    /** What the user selected before opening the folder picker; survives configuration changes. */
    var pendingItems: List<ClassifiedItem> = emptyList()

    /** MOVE-2: the user picked a folder or drive; check it and run the pre-flight for [pendingItems]. */
    fun onTreePicked(uri: Uri) {
        val items = pendingItems
        if (items.isEmpty()) {
            // Process death while the picker was open: never start an empty or guessed job.
            fail(TargetProblem.SELECTION_LOST)
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, picked = null, problem = null) }
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { app.contentResolver.takePersistableUriPermission(uri, flags) }
            val label = withContext(Dispatchers.IO) { treeLabel(uri) }
            when (val check = SafTargets.open(app.contentResolver, uri, label)) {
                is TargetCheck.Ok -> {
                    val files = plan("preview", items, check.target.caps)
                    val issues = MovePlanner.preflight(files, check.target, emptySet(), battery(), charging())
                    pickedUri = uri
                    pickedTarget = check.target
                    _state.update {
                        it.copy(
                            busy = false,
                            picked = PickedTarget(
                                label,
                                check.kind,
                                check.target.caps.canReadBack,
                                files,
                                issues,
                                check.format,
                            ),
                        )
                    }
                }

                TargetCheck.OnThisPhone -> {
                    fail(TargetProblem.ON_THIS_PHONE)
                }

                TargetCheck.DamagedMarker -> {
                    fail(TargetProblem.DAMAGED_MARKER)
                }

                TargetCheck.Unavailable -> {
                    fail(TargetProblem.UNAVAILABLE)
                }
            }
        }
    }

    private fun fail(problem: TargetProblem) = _state.update { it.copy(busy = false, problem = problem) }

    /** One job per selection; each source group gets its own folder (MOVE-7), indexes stay unique. */
    private fun plan(
        jobId: String,
        items: List<ClassifiedItem>,
        caps: io.github.tomerar.keepclear.backup.TargetCaps,
    ): List<MoveFile> {
        var offset = 0
        return items.groupBy { MoveSources.folderFor(it.ruleId) }.flatMap { (folder, group) ->
            // Read by path: with media access Android allows reading media files by path (FUSE).
            val scan = group.map { it.item.copy(ref = KPath.Local(it.item.path)) }
            MovePlanner.plan(jobId, scan, deviceName(), folder, caps)
                .map { it.copy(index = it.index + offset) }
                .also { offset += it.size }
        }
    }

    fun start() {
        val picked = _state.value.picked ?: return
        val target = pickedTarget ?: return
        val uri = pickedUri ?: return
        if (picked.blocked || picked.files.isEmpty()) return
        viewModelScope.launch {
            val id = UUID.randomUUID().toString()
            val files = picked.files.map { it.copy(jobId = id) }
            val job = MoveJob(
                id = id,
                targetId = target.id,
                targetLabel = picked.label,
                rootRel = MovePlanner.rootRel(deviceName()),
                createdAt = System.currentTimeMillis(),
                targetRef = uri.toString(),
            )
            withContext(Dispatchers.IO) {
                controller.store.putJob(job)
                controller.store.putFiles(files)
            }
            _state.update { it.copy(picked = null) }
            controller.start(app, id)
            reload()
        }
    }

    fun cancelPick() = _state.update { it.copy(picked = null, problem = null) }

    fun pause() = controller.pause(PauseReason.PAUSED_BY_USER)

    fun stop() = controller.pause(PauseReason.CANCELLED)

    fun resume(jobId: String) = controller.start(app, jobId)

    /**
     * MOVE-10: after the user confirmed in this session, trash the originals whose copy is verified and still on
     * the drive. Everything goes through the executor: re-check, strict re-hash, PROTECTED paths, 30-day trash.
     */
    fun freeOriginals(jobId: String) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val store = controller.store
            val job = store.job(jobId)
            val check = job?.targetRef?.let { SafTargets.open(app.contentResolver, it.toUri(), job.targetLabel) }
            if (job == null || check !is TargetCheck.Ok || check.target.id != job.targetId) {
                _state.update { it.copy(busy = false, problem = TargetProblem.UNAVAILABLE) }
                return@launch
            }
            val ops =
                withContext(Dispatchers.IO) { MoveEngine(app.fileGateway, check.target, store).originalsPlan(jobId) }
            if (ops.isEmpty()) {
                _state.update { it.copy(busy = false, freeResult = FreeResult(jobId, 0, 0, 0, 0)) }
                return@launch
            }
            val plan = ActionPlan(UUID.randomUUID().toString(), ops, "move")
                .confirm(System.currentTimeMillis(), app.sessionId)
            val report = withContext(Dispatchers.IO) { app.executor().run(plan) }
            val result = when (report) {
                is ExecutionReport.Ran -> {
                    val wouldHave = report.results
                        .filterIsInstance<io.github.tomerar.keepclear.core.safety.OpResult.Simulated>()
                        .map { it.op }
                    val freed = report.done.map { it.op } + wouldHave
                    FreeResult(
                        jobId,
                        freed.size,
                        freed.sumOf { it.expectedSize },
                        report.skipped.size,
                        report.failed.size,
                        simulated = wouldHave.isNotEmpty(),
                    )
                }

                is ExecutionReport.Rejected -> {
                    FreeResult(jobId, 0, 0, 0, ops.size)
                }
            }
            _state.update { it.copy(busy = false, freeResult = result) }
        }
    }

    private fun canReadBack(job: MoveJob): Boolean = runCatching {
        val uri = job.targetRef!!.toUri()
        SafTargets.classify(uri.authority, DocumentsContract.getTreeDocumentId(uri))?.second?.canReadBack == true
    }.getOrDefault(false)

    fun clearFreeResult() = _state.update { it.copy(freeResult = null) }

    private fun treeLabel(uri: Uri): String = runCatching {
        val doc = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
        app.contentResolver.query(
            doc,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty()

    private fun deviceName(): String =
        Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
            ?: Build.MODEL

    private fun battery(): Int = app.getSystemService(BatteryManager::class.java)
        .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        .let { if (it in 0..100) it else 100 }

    private fun charging(): Boolean = app.getSystemService(BatteryManager::class.java).isCharging
}

/** Folder per source on the drive (MOVE-7). Names are English on the drive so a PC shows the same folders. */
object MoveSources {
    fun folderFor(ruleId: String): String = when (ruleId) {
        "camera" -> "Camera"
        "wa_received" -> "WhatsApp"
        "telegram_saved" -> "Telegram"
        "pictures_media" -> "Pictures"
        else -> "Other"
    }
}
