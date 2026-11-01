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
import io.github.tomerar.keepclear.backup.saf.TargetKind
import io.github.tomerar.keepclear.core.safety.ActionPlan
import io.github.tomerar.keepclear.core.safety.ExecutionReport
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.engine.classify.ClassifiedItem
import io.github.tomerar.keepclear.engine.move.CheckResult
import io.github.tomerar.keepclear.engine.move.FreshRead
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

    /** M15: the network drive answered but refused the name or password. */
    REFUSED,

    /** M15: nothing answered at the network drive's address (computer off or asleep, other Wi-Fi, typo). */
    UNREACHABLE,
}

data class JobView(
    val job: MoveJob,
    val receipt: Receipt,
    val canReadBack: Boolean,
    /** Originals the user is offered to free: confirmed copies (H1). The confirm tap names exactly these. */
    val freeablePaths: Set<String> = emptySet(),
    /** Review PR C: progress by bytes, the current phase and an estimate of the time left. */
    val totalBytes: Long = 0,
    val doneBytes: Long = 0,
    val phase: MovePhase = MovePhase.COPYING,
    val etaMillis: Long? = null,
) {
    /**
     * MOVE-9, H1: freeing is offered only once every file is settled and copies passed the second check
     * ("Check backup"), whose read can't come from the phone's memory.
     */
    val canFree: Boolean get() = canReadBack && receipt.complete && receipt.confirmed > 0

    /** Verified copies that still need the second check before their originals may be freed (H1). */
    val needsCheck: Boolean get() = canReadBack && receipt.complete && receipt.verified > receipt.confirmed
}

enum class MovePhase { COPYING, CHECKING }

/** Bytes per millisecond since [startAt]; null until there is enough to say anything honest. */
fun etaMillis(startAt: Long, startBytes: Long, now: Long, doneBytes: Long, totalBytes: Long): Long? {
    val elapsed = now - startAt
    val moved = doneBytes - startBytes
    if (elapsed < 10_000 || moved <= 0) return null
    return ((totalBytes - doneBytes) * elapsed / moved).coerceAtLeast(0)
}

/** H1: the second check, step by step (MOVE-15). */
sealed interface BackupCheck {
    val jobId: String

    /** Waiting for the drive to be unplugged (drops what the phone remembers of it). */
    data class Unplug(override val jobId: String) : BackupCheck

    /** Waiting for the same drive to come back. */
    data class PlugBack(override val jobId: String) : BackupCheck

    data class Checking(override val jobId: String) : BackupCheck

    data class Done(override val jobId: String, val result: CheckResult) : BackupCheck

    /** The drive didn't come back in time, or another drive was attached. */
    data class TimedOut(override val jobId: String) : BackupCheck

    /** The drive went away or access was lost while checking; what was checked is kept. */
    data class Interrupted(override val jobId: String) : BackupCheck
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
    val check: BackupCheck? = null,
)

class MoveViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as KeepClearApplication
    private val controller = app.moveController
    private val _state = MutableStateFlow(MoveUiState())
    val state: StateFlow<MoveUiState> = _state

    /** How the job will reopen the picked target: a tree URI, or the network drive's `smb://` reference. */
    private var pickedRef: String? = null
    private var pickedTarget: io.github.tomerar.keepclear.backup.BackupTarget? = null

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
            store.jobs().map { job ->
                val files = store.files(job.id)
                val freeable = files.filter {
                    it.state == io.github.tomerar.keepclear.engine.move.MoveFileState.VERIFIED && it.confirmedAt != null
                }.map { it.sourcePath }.toSet()
                val total = files.sumOf { it.sourceSize }
                val done = files.filter { it.state in Receipt.DONE_STATES }.sumOf { it.sourceSize }
                val checking = files.any { it.state == io.github.tomerar.keepclear.engine.move.MoveFileState.PLACED }
                val now = System.currentTimeMillis()
                val eta = if (job.id == controller.running.value) {
                    val start =
                        runStart.takeIf { it?.first == job.id } ?: Triple(job.id, now, done).also { runStart = it }
                    etaMillis(start.second, start.third, now, done, total)
                } else {
                    null
                }
                JobView(
                    job,
                    Receipt.of(job, files),
                    canReadBack(job),
                    freeable,
                    totalBytes = total,
                    doneBytes = done,
                    phase = if (checking) MovePhase.CHECKING else MovePhase.COPYING,
                    etaMillis = eta,
                )
            }
        }
        _state.update { it.copy(jobs = jobs) }
    }

    /** When and at how many bytes the running job was first seen, for the time-left estimate. */
    private var runStart: Triple<String, Long, Long>? = null

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
            opened(OpenedTarget.of(app.openTarget(uri, label)), label, uri.toString(), items)
        }
    }

    /**
     * M15: the user typed a network drive's address and tapped "Connect". It is saved (password sealed) only when
     * KeepClear could open it; otherwise the form says why (refused, or nothing answered).
     */
    fun onNetworkDrive(location: io.github.tomerar.keepclear.backup.smb.SmbLocation) {
        val items = pendingItems
        if (items.isEmpty()) {
            fail(TargetProblem.SELECTION_LOST)
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, picked = null, problem = null) }
            val label = location.host
            val check = withContext(Dispatchers.IO) { app.openNetworkDrive(location, label) }
            if (check is OpenedTarget.Ok) withContext(Dispatchers.IO) { app.networkDrives.save(location) }
            opened(check, label, io.github.tomerar.keepclear.backup.smb.SmbTargets.ref(location), items)
        }
    }

    /** The saved network drive, to fill the form again (the password stays sealed until it is used). */
    fun savedNetworkDrive() = runCatching { app.networkDrives.load() }.getOrNull()

    private suspend fun opened(check: OpenedTarget, label: String, ref: String, items: List<ClassifiedItem>) {
        when (check) {
            is OpenedTarget.Ok -> {
                val files = plan("preview", items, check.target.caps)
                val issues = withContext(Dispatchers.IO) {
                    MovePlanner.preflight(files, check.target, emptySet(), battery(), charging())
                }
                pickedRef = ref
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

            OpenedTarget.OnThisPhone -> {
                fail(TargetProblem.ON_THIS_PHONE)
            }

            OpenedTarget.DamagedMarker -> {
                fail(TargetProblem.DAMAGED_MARKER)
            }

            OpenedTarget.Refused -> {
                fail(TargetProblem.REFUSED)
            }

            OpenedTarget.Unavailable -> {
                fail(if (OpenedTarget.isNetwork(ref)) TargetProblem.UNREACHABLE else TargetProblem.UNAVAILABLE)
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
        val ref = pickedRef ?: return
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
                targetRef = ref,
            )
            withContext(Dispatchers.IO) {
                controller.store.putJob(job)
                controller.store.putFiles(files)
            }
            _state.update { it.copy(picked = null) }
            // The service opens the target itself; release the one used for the preview (PR #25 review).
            releasePicked()
            controller.start(app, id)
            reload()
        }
    }

    fun cancelPick() {
        _state.update { it.copy(picked = null, problem = null) }
        viewModelScope.launch { releasePicked() }
    }

    private suspend fun releasePicked() {
        pickedTarget?.useTarget { }
        pickedTarget = null
    }

    fun pause() = controller.pause(PauseReason.PAUSED_BY_USER)

    fun stop() = controller.pause(PauseReason.CANCELLED)

    fun resume(jobId: String) = controller.start(app, jobId)

    /**
     * MOVE-10: after the user confirmed in this session, trash the originals whose copy is verified and still on
     * the drive. Everything goes through the executor: re-check, strict re-hash, PROTECTED paths, 30-day trash.
     */
    fun freeOriginals(jobId: String, confirmation: io.github.tomerar.keepclear.core.safety.Confirmation) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val store = controller.store
            val job = store.job(jobId)
            val check = job?.targetRef?.let { withContext(Dispatchers.IO) { app.openTargetRef(it, job.targetLabel) } }
            if (job == null || check !is OpenedTarget.Ok || check.target.id != job.targetId) {
                (check as? OpenedTarget.Ok)?.target?.useTarget { }
                _state.update { it.copy(busy = false, problem = TargetProblem.UNAVAILABLE) }
                return@launch
            }
            // H1: only copies confirmed by the second check, re-hashed on the drive right now. The drive is
            // released right after; freeing itself only touches the phone.
            val ops = withContext(Dispatchers.IO) { check.target.useTarget { engine(it).originalsPlan(jobId) } }
            if (ops.isEmpty()) {
                _state.update { it.copy(busy = false, freeResult = FreeResult(jobId, 0, 0, 0, 0)) }
                return@launch
            }
            // Review PR B: the confirmation lists the originals the receipt offered; anything else is rejected.
            val plan = ActionPlan(UUID.randomUUID().toString(), ops, "move").confirm(confirmation, app.sessionId)
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

    private fun engine(target: io.github.tomerar.keepclear.backup.BackupTarget) =
        MoveEngine(app.localGateway, target, controller.store, bootId = { app.trashClock.bootId() })

    private var checkJob: kotlinx.coroutines.Job? = null

    /**
     * MOVE-15 / H1 "Check backup": re-reads every verified copy in a way the phone's memory can't answer. After a
     * restart since the backup, it reads right away; otherwise it asks to unplug the drive and plug it back in,
     * and watches both happen before reading. Nothing is freed here; the user still decides (principle 3).
     */
    fun checkBackup(jobId: String) {
        checkJob?.cancel()
        checkJob = viewModelScope.launch {
            val store = controller.store
            val job = withContext(Dispatchers.IO) { store.job(jobId) } ?: return@launch
            val ref = job.targetRef ?: return@launch

            // Each call opens the target anew (for a network drive: a brand-new connection).
            suspend fun attached(): io.github.tomerar.keepclear.backup.BackupTarget? {
                val opened = withContext(Dispatchers.IO) { app.openTargetRef(ref, job.targetLabel) } as? OpenedTarget.Ok
                val target = opened?.target ?: return null
                if (target.id == job.targetId) return target
                target.useTarget { }
                return null
            }

            // Polling only asks whether the drive is there; each probe is released at once.
            suspend fun present(): Boolean = attached()?.useTarget { true } ?: false
            val rebooted = job.verifyBootId != null && job.verifyBootId != app.trashClock.bootId()
            val evidence = if (OpenedTarget.isNetwork(ref)) {
                // M15: a network drive's reads all come from the server; a new connection is a fresh read, so
                // there is nothing to unplug (decided conservatively: progress.md C8).
                FreshRead.NEW_SESSION
            } else if (rebooted) {
                FreshRead.REBOOTED
            } else {
                _state.update { it.copy(check = BackupCheck.Unplug(jobId)) }
                val gone = kotlinx.coroutines.withTimeoutOrNull(CHECK_WAIT_MILLIS) {
                    while (present()) kotlinx.coroutines.delay(POLL_MILLIS)
                    true
                }
                if (gone == null) return@launch timeout(jobId)
                _state.update { it.copy(check = BackupCheck.PlugBack(jobId)) }
                kotlinx.coroutines.withTimeoutOrNull(CHECK_WAIT_MILLIS) {
                    while (!present()) kotlinx.coroutines.delay(POLL_MILLIS)
                    true
                } ?: return@launch timeout(jobId)
                FreshRead.REATTACHED
            }
            val target = attached() ?: return@launch timeout(jobId)
            _state.update { it.copy(check = BackupCheck.Checking(jobId)) }
            val result = try {
                withContext(Dispatchers.IO) { target.useTarget { engine(it).confirm(jobId, evidence) } }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Never crash on a storage error mid-check; confirmed files are already saved (review P2).
                null
            }
            val step = if (result == null || result.interrupted) {
                BackupCheck.Interrupted(jobId)
            } else {
                BackupCheck.Done(jobId, result)
            }
            _state.update { it.copy(check = step) }
            reload()
        }
    }

    private fun timeout(jobId: String) = _state.update { it.copy(check = BackupCheck.TimedOut(jobId)) }

    fun cancelCheck() {
        checkJob?.cancel()
        _state.update { it.copy(check = null) }
    }

    private fun canReadBack(job: MoveJob): Boolean = runCatching {
        // A network drive is read back over the network (M15).
        if (OpenedTarget.isNetwork(job.targetRef)) return@runCatching true
        val uri = job.targetRef!!.toUri()
        SafTargets.classify(uri.authority, DocumentsContract.getTreeDocumentId(uri), app.testDriveAuthorities)
            ?.second?.canReadBack == true
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

private const val CHECK_WAIT_MILLIS = 5 * 60_000L
private const val POLL_MILLIS = 1_000L

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
