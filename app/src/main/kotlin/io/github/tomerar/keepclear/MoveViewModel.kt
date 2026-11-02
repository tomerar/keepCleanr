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
import kotlinx.coroutines.flow.conflate
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

    /** M17b: the Nextcloud's certificate isn't trusted or is for another name. */
    CERTIFICATE,

    /** M17b: the Nextcloud refused the app password, or it can't be opened any more: sign in again (Advanced). */
    NEXTCLOUD_SIGN_IN,

    /** M17b: the Nextcloud didn't answer. */
    NEXTCLOUD_UNREACHABLE,

    /** D3: the network address led to this phone itself (its own address); a backup there is no backup. */
    NETWORK_ON_THIS_PHONE,
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
    /**
     * D3: the place is a network drive used as a guest (no name and password): its session can't be signed, so
     * KeepClear only copies to it and never offers to free the originals (the engine refuses too).
     */
    val copyOnly: Boolean = false,
) {
    /**
     * MOVE-9, H1: freeing is offered only once every file is settled and copies passed the second check
     * ("Check backup"), whose read can't come from the phone's memory. Never for a copy-only place (D3).
     */
    val canFree: Boolean get() = canReadBack && !copyOnly && receipt.complete && receipt.confirmed > 0

    /** Verified copies that still need the second check before their originals may be freed (H1). */
    val needsCheck: Boolean get() =
        canReadBack && !copyOnly && receipt.complete && receipt.verified > receipt.confirmed
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
    /** MOVE-17: the "Restore from a backup" flow, null when closed. */
    val restore: RestoreStep? = null,
    /** E3: a free, check or restore running in the foreground service (survives leaving the app). */
    val task: TaskProgress? = null,
)

class MoveViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as KeepClearApplication
    private val controller = app.moveController
    private val _state = MutableStateFlow(MoveUiState())
    val state: StateFlow<MoveUiState> = _state

    /** How the job will reopen the picked target: a tree URI, or the network drive's `smb://` reference. */
    private var pickedRef: String? = null
    private var pickedTarget: io.github.tomerar.keepclear.backup.BackupTarget? = null

    /** E7: the last view of every job; a progress tick re-reads only the running one. */
    private var views: Map<String, JobView> = emptyMap()
    private var viewedRunning: String? = null

    /** The time-left estimate of the running job (review PR C, E13). */
    private val eta = EtaTracker()

    // Declared before init: init's first reload runs at once on the main thread (Main.immediate).
    init {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { controller.markInterrupted() }
            // E7: progress reloads at most every RELOAD_MILLIS, and only the running job is read again.
            combine(controller.running, controller.ticks) { running, _ -> running }
                .conflate()
                .collect { running ->
                    _state.update { it.copy(running = running) }
                    reload(running)
                    kotlinx.coroutines.delay(RELOAD_MILLIS)
                }
        }
        // E3: free / check / restore run app-wide; their state and results come back here (also after a restart
        // of this screen).
        viewModelScope.launch { controller.task.collect { t -> _state.update { it.copy(task = t) } } }
        viewModelScope.launch {
            controller.results.collect { r ->
                _state.update {
                    it.copy(
                        check = r.check,
                        freeResult = r.freeResult,
                        restore = r.restore ?: it.restore?.takeUnless { s -> s.fromTask },
                    )
                }
            }
        }
    }

    private suspend fun reload(running: String? = controller.running.value) {
        val store = controller.store
        // E13: the time-left estimate starts again whenever a job (re)starts, so a pause never stretches it.
        eta.onRunning(running)
        val jobs = withContext(Dispatchers.IO) {
            val all = store.jobs()
            // A full reload when which job runs changed (a job ended, paused or started); otherwise only the running.
            val full = running != viewedRunning || running == null || all.map { it.id }.toSet() != views.keys
            viewedRunning = running
            all.map { job ->
                val known = views[job.id]
                if (!full && job.id != running && known != null && known.job == job) known else view(job, running)
            }
        }
        views = jobs.associateBy { it.job.id }
        _state.update { it.copy(jobs = jobs) }
    }

    private suspend fun view(job: MoveJob, running: String?): JobView {
        val files = controller.store.files(job.id)
        val freeable = files.filter {
            it.state == io.github.tomerar.keepclear.engine.move.MoveFileState.VERIFIED && it.confirmedAt != null
        }.map { it.sourcePath }.toSet()
        val total = files.sumOf { it.sourceSize }
        val done = files.filter { it.state in Receipt.DONE_STATES }.sumOf { it.sourceSize }
        val checking = files.any { it.state == io.github.tomerar.keepclear.engine.move.MoveFileState.PLACED }
        return JobView(
            job,
            Receipt.of(job, files),
            canReadBack(job),
            freeable,
            totalBytes = total,
            doneBytes = done,
            phase = if (checking) MovePhase.CHECKING else MovePhase.COPYING,
            etaMillis = if (job.id == running) eta.estimate(job.id, System.currentTimeMillis(), done, total) else null,
            copyOnly = copyOnly(job),
        )
    }

    /** What the user selected before opening the folder picker; survives configuration changes. */
    var pendingItems: List<ClassifiedItem> = emptyList()

    /** MOVE-2: the user picked a folder or drive; check it and run the pre-flight for [pendingItems]. */
    fun onTreePicked(uri: Uri) {
        if (_state.value.restore == RestoreStep.Pick) {
            viewModelScope.launch {
                val label = withContext(Dispatchers.IO) { treeLabel(uri) }
                restoreFrom(withContext(Dispatchers.IO) { OpenedTarget.of(app.openTarget(uri, label)) })
            }
            return
        }
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
        if (_state.value.restore == RestoreStep.Pick) {
            viewModelScope.launch {
                val check = withContext(Dispatchers.IO) { app.openNetworkDrive(location, location.host) }
                if (check is OpenedTarget.Ok) withContext(Dispatchers.IO) { app.networkDrives.save(location) }
                restoreFrom(check)
            }
            return
        }
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

    /**
     * M17b: back up to (or restore from) the Nextcloud set up under Settings › Advanced. Its app password stays
     * sealed until here; if it can't be opened any more, the user is asked to sign in again.
     */
    fun onNextcloud() {
        val restoring = _state.value.restore == RestoreStep.Pick
        val items = pendingItems
        if (!restoring && items.isEmpty()) {
            fail(TargetProblem.SELECTION_LOST)
            return
        }
        viewModelScope.launch {
            if (!restoring) _state.update { it.copy(busy = true, picked = null, problem = null) }
            val account = withContext(Dispatchers.IO) { runCatching { app.nextcloud.load() }.getOrNull() }
            if (account == null) {
                if (restoring) closeRestore()
                fail(TargetProblem.NEXTCLOUD_SIGN_IN)
                return@launch
            }
            val label = account.server.host
            val check = withContext(Dispatchers.IO) { app.openNextcloud(account, label) }
            if (restoring) {
                restoreFrom(check, nextcloud = true)
            } else {
                opened(check, label, NextcloudStore.ref(account), items)
            }
        }
    }

    /** The saved network drive, to fill the form again (the password stays sealed until it is used). */
    fun savedNetworkDrive() = runCatching { app.networkDrives.load() }.getOrNull()

    internal suspend fun opened(check: OpenedTarget, label: String, ref: String, items: List<ClassifiedItem>) {
        when (check) {
            is OpenedTarget.Ok -> {
                // E13: planning thousands of files never runs on the main thread.
                val files = withContext(Dispatchers.Default) { plan("preview", items, check.target.caps) }
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
                fail(
                    if (OpenedTarget.isNetwork(
                            ref,
                        )
                    ) {
                        TargetProblem.NETWORK_ON_THIS_PHONE
                    } else {
                        TargetProblem.ON_THIS_PHONE
                    },
                )
            }

            OpenedTarget.DamagedMarker -> {
                fail(TargetProblem.DAMAGED_MARKER)
            }

            OpenedTarget.Refused -> {
                fail(if (OpenedTarget.isNextcloud(ref)) TargetProblem.NEXTCLOUD_SIGN_IN else TargetProblem.REFUSED)
            }

            OpenedTarget.CertificateRejected -> {
                fail(TargetProblem.CERTIFICATE)
            }

            OpenedTarget.Unavailable -> {
                fail(
                    when {
                        OpenedTarget.isNextcloud(ref) -> TargetProblem.NEXTCLOUD_UNREACHABLE
                        OpenedTarget.isSmb(ref) -> TargetProblem.UNREACHABLE
                        else -> TargetProblem.UNAVAILABLE
                    },
                )
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
        // E8: cleared before anything is launched, so a second tap finds nothing to start (one job, not two).
        _state.update { it.copy(picked = null) }
        pickedTarget = null
        pickedRef = null
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
            // The service opens the target itself; release the one used for the preview (PR #25 review).
            withContext(Dispatchers.IO) { target.useTarget { } }
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

    // ------------------------------------------------------------------ MOVE-17: restore from a backup

    private var restoreEngine: io.github.tomerar.keepclear.engine.restore.RestoreEngine? = null
    private var restoreEntries: List<io.github.tomerar.keepclear.engine.restore.BackupEntry> = emptyList()

    /**
     * Starts "Restore from a backup". Files go back where they were, so the app needs to write by path: All files
     * access (decided conservatively for the `play` build, progress.md C10). In trial mode restoring writes nothing
     * and only says what it would do (D1, C11 as changed by the owner's review).
     */
    fun beginRestore() {
        val canWrite = BuildConfig.FLAVOR == "foss" &&
            io.github.tomerar.keepclear.core.storage.media.MediaAccess.hasAllFiles()
        _state.update {
            it.copy(restore = if (canWrite) RestoreStep.Pick else RestoreStep.NeedsAllFiles, problem = null)
        }
    }

    /** The opened backup place; released when the flow closes (a network drive's connection, PR #25 lesson). */
    private var restoreTarget: io.github.tomerar.keepclear.backup.BackupTarget? = null

    fun closeRestore() {
        // E3: a restore still running keeps its connection; the task releases it when it ends.
        if (controller.task.value?.kind == TaskKind.RESTORE) return
        restoreTarget?.let { t -> controller.scope.launch { t.useTarget { } } }
        restoreTarget = null
        restoreEngine = null
        restoreEntries = emptyList()
        controller.results.update { it.copy(restore = null) }
        _state.update { it.copy(restore = null) }
    }

    private suspend fun restoreFrom(check: OpenedTarget, nextcloud: Boolean = false) {
        if (check !is OpenedTarget.Ok) {
            closeRestore()
            fail(
                when (check) {
                    OpenedTarget.Refused -> if (nextcloud) TargetProblem.NEXTCLOUD_SIGN_IN else TargetProblem.REFUSED
                    OpenedTarget.DamagedMarker -> TargetProblem.DAMAGED_MARKER
                    OpenedTarget.CertificateRejected -> TargetProblem.CERTIFICATE
                    else -> if (nextcloud) TargetProblem.NEXTCLOUD_UNREACHABLE else TargetProblem.UNAVAILABLE
                },
            )
            return
        }
        val engine = io.github.tomerar.keepclear.engine.restore.RestoreEngine(
            check.target,
            app.localGateway,
            app.protectedPaths,
            phoneRoot,
            space = phoneSpace,
            // D2: the manifest's original path counts only when this phone's MOVE records hold it.
            records = { src, sha -> controller.store.movedFrom(src, sha) },
            isDryRun = { app.testMode.isOn },
        )
        restoreTarget?.useTarget { }
        restoreEngine = engine
        restoreTarget = check.target
        val roots = withContext(Dispatchers.IO) { engine.backups() }
        when (roots.size) {
            0 -> _state.update { it.copy(restore = RestoreStep.NoBackup) }
            1 -> chooseBackup(roots.single())
            else -> _state.update { it.copy(restore = RestoreStep.Backups(roots)) }
        }
    }

    /** The phone's shared storage, where restored files go. */
    private val phoneRoot: String get() = android.os.Environment.getExternalStorageDirectory().absolutePath

    /** D1: free space on [phoneRoot]. */
    private val phoneSpace = io.github.tomerar.keepclear.engine.restore.SpaceProbe { measurePhoneSpace(app, phoneRoot) }

    fun chooseBackup(rootRel: String) {
        val engine = restoreEngine ?: return
        viewModelScope.launch {
            val (entries, groups, space) = withContext(Dispatchers.IO) {
                val entries = engine.catalog(rootRel).orEmpty()
                // D2: where each file would go, shown per month before anything is chosen.
                Triple(entries, restoreGroups(engine.plan(entries)), phoneSpace.measure())
            }
            restoreEntries = entries
            if (entries.isEmpty()) {
                _state.update { it.copy(restore = RestoreStep.NoBackup) }
                return@launch
            }
            _state.update { it.copy(restore = RestoreStep.Catalog(rootRel, groups, space)) }
        }
    }

    /** MOVE-17, E3: restoring runs in the foreground service with progress; leaving KeepClear doesn't stop it. */
    fun runRestore(keys: Set<String>) {
        val engine = restoreEngine ?: return
        val chosen = restoreEntries.filter { "${it.group}|${it.month}" in keys }
        if (chosen.isEmpty()) return

        fun step(s: RestoreStep) = controller.results.update { it.copy(restore = s) }
        // PR #45 review: stopped by Android's time limit, the restore ends as interrupted (what is done stays done),
        // so the dialog can be closed and the restore started again.
        val cancelled = {
            step(RestoreStep.Done(io.github.tomerar.keepclear.engine.restore.RestoreReport(interrupted = true)))
        }
        controller.runTask(app, TaskKind.RESTORE, onCancelled = cancelled) { progress ->
            step(RestoreStep.Running(0, chosen.size))
            progress(0, chosen.size)
            val report = engine.restore(chosen) { done ->
                step(RestoreStep.Running(done, chosen.size))
                progress(done, chosen.size)
            }
            // Let the gallery and other apps see the files again (MOVE-17 "re-index in MediaStore").
            if (report.restored.isNotEmpty()) {
                android.media.MediaScannerConnection.scanFile(app, report.restored.toTypedArray(), null, null)
            }
            step(RestoreStep.Done(report))
        }
    }

    fun pause() = controller.pause(PauseReason.PAUSED_BY_USER)

    fun stop() = controller.pause(PauseReason.CANCELLED)

    fun resume(jobId: String) = controller.start(app, jobId)

    /**
     * MOVE-10: after the user confirmed in this session, trash the originals whose copy is verified and still on
     * the drive. Everything goes through the executor: re-check, strict re-hash, PROTECTED paths, 30-day trash.
     */
    fun freeOriginals(jobId: String, confirmation: io.github.tomerar.keepclear.core.safety.Confirmation) {
        // E3: in the foreground service, so leaving KeepClear doesn't stop it halfway; one task at a time.
        controller.runTask(app, TaskKind.FREE) { progress ->
            controller.results.update { it.copy(freeResult = null) }
            val store = controller.store
            val job = store.job(jobId)
            val check = job?.targetRef?.let { app.openTargetRef(it, job.targetLabel) }
            if (job == null || check !is OpenedTarget.Ok || check.target.id != job.targetId) {
                (check as? OpenedTarget.Ok)?.target?.useTarget { }
                withContext(Dispatchers.Main) { _state.update { it.copy(problem = TargetProblem.UNAVAILABLE) } }
                return@runTask
            }
            // H1: only copies confirmed by the second check, re-hashed on the drive right now. The drive is
            // released right after; freeing itself only touches the phone.
            val ops = check.target.useTarget { engine(it).originalsPlan(jobId) }
            if (ops.isEmpty()) {
                controller.results.update { it.copy(freeResult = FreeResult(jobId, 0, 0, 0, 0)) }
                return@runTask
            }
            // Review PR B: the confirmation lists the originals the receipt offered; anything else is rejected.
            val plan = ActionPlan(UUID.randomUUID().toString(), ops, "move").confirm(confirmation, app.sessionId)
            var done = 0
            progress(0, ops.size)
            val report = app.executor(onResult = { progress(++done, ops.size) }).run(plan)
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
            controller.results.update { it.copy(freeResult = result) }
        }
    }

    private fun engine(target: io.github.tomerar.keepclear.backup.BackupTarget) = MoveEngine(
        app.localGateway,
        target,
        controller.store,
        bootId = { app.trashClock.bootId() },
        attachGeneration = { app.volumeEvents.generation(target.volumeId) },
    )

    /**
     * MOVE-15 / H1 "Check backup": re-reads every verified copy in a way the phone's memory can't answer. When every
     * copy still to confirm was first checked before a restart, it reads right away; otherwise it asks to unplug
     * the drive and plug it back in, and watches both happen before reading. D4: the engine itself checks the
     * proof per file (boot id, and the drive's real unmount → mount events in [VolumeEvents]); files without it
     * stay unconfirmed and the result says so. Nothing is freed here; the user still decides (principle 3).
     */
    fun checkBackup(jobId: String) {
        // E3: the whole check (also waiting for the unplug) runs in the foreground service, so switching to another
        // app while the drive is unplugged doesn't end it.
        controller.runTask(app, TaskKind.CHECK) { _ -> checkNow(jobId) }
    }

    private fun showCheck(step: BackupCheck?) = controller.results.update { it.copy(check = step) }

    private suspend fun checkNow(jobId: String) {
        run {
            val store = controller.store
            val job = store.job(jobId) ?: return
            val ref = job.targetRef ?: return

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

            // D4: the drive's volume, from the job's own reference (never from what a probe reports).
            val volume = runCatching {
                val uri = ref.toUri()
                SafTargets.volumeId(uri.authority, DocumentsContract.getTreeDocumentId(uri))
            }.getOrNull()

            fun proofEngine() = MoveEngine(
                app.localGateway,
                NoTarget,
                store,
                bootId = { app.trashClock.bootId() },
                attachGeneration = { app.volumeEvents.generation(volume) },
            )
            val rebooted = !OpenedTarget.isNetwork(ref) &&
                withContext(Dispatchers.IO) { proofEngine().unprovenNow(jobId) } == 0
            val evidence = if (OpenedTarget.isNetwork(ref)) {
                // M15: a network drive's reads all come from the server; a new connection is a fresh read, so
                // there is nothing to unplug (decided conservatively: progress.md C8).
                FreshRead.NEW_SESSION
            } else if (rebooted) {
                FreshRead.REBOOTED
            } else {
                showCheck(BackupCheck.Unplug(jobId))
                val gone = kotlinx.coroutines.withTimeoutOrNull(CHECK_WAIT_MILLIS) {
                    while (present()) kotlinx.coroutines.delay(POLL_MILLIS)
                    true
                }
                if (gone == null) return timeout(jobId)
                showCheck(BackupCheck.PlugBack(jobId))
                kotlinx.coroutines.withTimeoutOrNull(CHECK_WAIT_MILLIS) {
                    while (!present()) kotlinx.coroutines.delay(POLL_MILLIS)
                    true
                } ?: return timeout(jobId)
                // D4: Android reports the mount a moment after the files are reachable; wait briefly for it. Without
                // it the engine proves nothing and the result says how many files still need a check.
                kotlinx.coroutines.withTimeoutOrNull(MOUNT_EVENT_WAIT_MILLIS) {
                    while (withContext(Dispatchers.IO) { proofEngine().unprovenNow(jobId) } > 0) {
                        kotlinx.coroutines.delay(POLL_MILLIS / 4)
                    }
                }
                FreshRead.REATTACHED
            }
            val target = attached() ?: return timeout(jobId)
            showCheck(BackupCheck.Checking(jobId))
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
            showCheck(step)
        }
    }

    private fun timeout(jobId: String) = showCheck(BackupCheck.TimedOut(jobId))

    fun cancelCheck() {
        if (controller.task.value?.kind == TaskKind.CHECK) controller.cancelTask()
        showCheck(null)
    }

    private fun canReadBack(job: MoveJob): Boolean = runCatching {
        // A network drive is read back over the network (M15).
        if (OpenedTarget.isNetwork(job.targetRef)) return@runCatching true
        val uri = job.targetRef!!.toUri()
        SafTargets.classify(uri.authority, DocumentsContract.getTreeDocumentId(uri), app.testDriveAuthorities)
            ?.second?.canReadBack == true
    }.getOrDefault(false)

    /** D3: a job to the saved network drive, set up as a guest, is copy-only. */
    private fun copyOnly(job: MoveJob): Boolean =
        OpenedTarget.isSmb(job.targetRef) && runCatching { app.networkDrives.isGuest() }.getOrDefault(true)

    fun clearFreeResult() = controller.results.update { it.copy(freeResult = null) }

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

/** E7: progress on screen is read again at most this often (the notification: once a second). */
internal const val RELOAD_MILLIS = 500L

/** E3: a restore step that belongs to the running (or finished) restore task. */
val RestoreStep.fromTask: Boolean get() = this is RestoreStep.Running || this is RestoreStep.Done

/**
 * The time left of the running job (review PR C). E13: it starts again whenever a job (re)starts, so time spent
 * paused never stretches the estimate.
 */
class EtaTracker {
    private var start: Triple<String, Long, Long>? = null
    private var running: String? = null

    fun onRunning(id: String?) {
        if (id != running) {
            start = null
            running = id
        }
    }

    fun estimate(jobId: String, now: Long, doneBytes: Long, totalBytes: Long): Long? {
        val s = start?.takeIf { it.first == jobId } ?: Triple(jobId, now, doneBytes).also { start = it }
        return etaMillis(s.second, s.third, now, doneBytes, totalBytes)
    }
}

private const val POLL_MILLIS = 1_000L
private const val MOUNT_EVENT_WAIT_MILLIS = 15_000L

/** D4: [MoveEngine.unprovenNow] only reads the job store; it never touches a target. */
private object NoTarget : io.github.tomerar.keepclear.backup.BackupTarget {
    override val id = "none"
    override val label = ""
    override val caps = io.github.tomerar.keepclear.backup.TargetCaps(canReadBack = false)

    private fun no(): Nothing = throw UnsupportedOperationException("no target")

    override suspend fun freeBytes(): Long? = no()

    override suspend fun mkdirs(rel: String) = no()

    override suspend fun create(rel: String): java.io.OutputStream = no()

    override suspend fun rename(fromRel: String, toRel: String) = no()

    override suspend fun stat(rel: String): io.github.tomerar.keepclear.core.storage.Stat? = no()

    override suspend fun openRead(rel: String): java.io.InputStream = no()

    override suspend fun delete(rel: String) = no()

    override suspend fun list(relDir: String): List<io.github.tomerar.keepclear.core.storage.Stat> = no()
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
