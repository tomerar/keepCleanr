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
import kotlinx.coroutines.delay
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
    /**
     * Owner decisions P1/P2: the originals the receipt offers to free (confirmed copies), with what the free step's
     * "keep on the phone" options need (date, favourite). Empty while the job runs.
     */
    val freeCandidates: List<io.github.tomerar.keepclear.engine.move.FreeCandidate> = emptyList(),
    /** Owner brief H1 item 8: bytes still to copy (what a full drive would need). */
    val remainingBytes: Long = 0,
    /** Owner brief H1 item 8: the drive's free space when it filled up; null when not known (e.g. after a restart). */
    val targetFreeBytes: Long? = null,
    /** Owner brief H1 item 8: the files that couldn't be backed up, for "Finished with 3 errors · Show" (≤ 200). */
    val failedFiles: List<FailedFile> = emptyList(),
    /** The place is a network drive or a Nextcloud (no USB drive to keep plugged in). */
    val network: Boolean = false,
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

/** Owner brief H1 item 8: one file the backup couldn't copy, and why. */
data class FailedFile(val path: String, val reason: io.github.tomerar.keepclear.engine.move.FailReason?)

/** At most this many failed files are listed by name (the counts per reason are always complete). */
internal const val MAX_FAILED_LISTED = 200

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

/** Result of freeing originals after a verified copy (MOVE-10, owner decision P1). */
data class FreeResult(
    val jobId: String,
    val done: Int,
    val bytes: Long,
    val skipped: Int,
    val failed: Int,
    /** Test mode (SAFETY-9): nothing was deleted or moved; [done]/[bytes] would have been. */
    val simulated: Boolean = false,
    /** Owner decision P1: the originals were deleted for good (the copy on [targetLabel] is the undo). */
    val forGood: Boolean = false,
    val targetLabel: String = "",
    /** The drive went away (or access was lost) mid-way; what wasn't freed stays on the phone. */
    val interrupted: Boolean = false,
    /**
     * Owner brief H1 item 7: free space measured before and after (StatFs, like the clean result), only when originals
     * were deleted for good; null otherwise or when the phone reported no difference.
     */
    val measuredFreed: Long? = null,
    val nowFree: Long? = null,
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
    /** F2 (C76): setting up the computer at home under Settings › Advanced; null when no set-up ran. */
    val networkSetup: NetworkSetup? = null,
    /** Owner decision P1: the user's "keep the original in the 30-day trash too" setting. */
    val keepInTrash: Boolean = false,
    /** Owner brief H1 item 3: the removable drive plugged in now (live), or null. */
    val drive: RemovableDrive? = null,
    /** Owner brief H1 item 11: the reminder was tapped: the job whose backup to check again, or null. */
    val recheck: String? = null,
)

/** F2: the computer at home is set up in Settings › Advanced: KeepClear opens it once, and saves it only then. */
sealed interface NetworkSetup {
    data object Checking : NetworkSetup

    data class Saved(val host: String) : NetworkSetup

    data class Failed(val problem: TargetProblem) : NetworkSetup
}

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
            // Owner brief H1 item 8: found still running with no service and no user stop: Android ended it (an app
            // update is marked by PackageReplacedReceiver first, as INTERRUPTED).
            withContext(Dispatchers.IO) { controller.markInterrupted(PauseReason.STOPPED_BY_PHONE) }
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
        viewModelScope.launch { app.freeSettings.state.collect { on -> _state.update { it.copy(keepInTrash = on) } } }
        // Owner brief H1 item 3: "Found ✓" the moment a drive is plugged in.
        viewModelScope.launch {
            app.drives.drives.collect { list -> _state.update { it.copy(drive = list.firstOrNull { d -> d.mounted }) } }
        }
        // Owner decision P3: a backup that paused for the battery and whose service has gone goes on by itself once
        // the app is open and the phone charges (or the battery is back up). It only copies; nothing is freed.
        // Keeps looking while the screen is open (review of PR #53): the charger often goes in after the app opened.
        viewModelScope.launch(Dispatchers.IO) {
            while (resumeAfterBattery()) delay(batteryPollMillis)
        }
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

    /**
     * Owner decision P3: see init. Only when nothing runs and the job is still paused for the battery. Returns true
     * while there is still a backup waiting for the battery (so the caller looks again later), false otherwise.
     */
    private suspend fun resumeAfterBattery(): Boolean {
        val waiting = controller.store.jobs().firstOrNull {
            it.state == io.github.tomerar.keepclear.engine.move.JobState.PAUSED &&
                it.pauseReason == PauseReason.BATTERY_LOW
        } ?: return false
        if (controller.running.value != null) return true
        if (!io.github.tomerar.keepclear.engine.move.BatteryPolicy.canResume(app.batterySource())) return true
        withContext(Dispatchers.Main) { controller.start(app, waiting.id) }
        return false
    }

    private suspend fun view(job: MoveJob, running: String?): JobView {
        val files = controller.store.files(job.id)
        val confirmed = files.filter {
            it.state == io.github.tomerar.keepclear.engine.move.MoveFileState.VERIFIED && it.confirmedAt != null
        }
        val freeable = confirmed.map { it.sourcePath }.toSet()
        // Owner decision P2: favourites from the latest scan; a file it doesn't know counts as a favourite (C119).
        val favourites = if (confirmed.isEmpty() || job.id == running) {
            emptyMap()
        } else {
            runCatching { app.scanIndex.favourites(freeable) }.getOrDefault(emptyMap())
        }
        val candidates = if (job.id == running) {
            emptyList()
        } else {
            confirmed.map {
                io.github.tomerar.keepclear.engine.move.FreeCandidate(
                    it.sourcePath,
                    it.sourceSize,
                    it.sourceMtime,
                    favourites[it.sourcePath],
                )
            }
        }
        val total = files.sumOf { it.sourceSize }
        val done = files.filter { it.state in Receipt.DONE_STATES }.sumOf { it.sourceSize }
        val checking = files.any { it.state == io.github.tomerar.keepclear.engine.move.MoveFileState.PLACED }
        val failedStates = setOf(
            io.github.tomerar.keepclear.engine.move.MoveFileState.FAILED,
            io.github.tomerar.keepclear.engine.move.MoveFileState.SRC_CHANGED,
        )
        val failed = if (job.state == io.github.tomerar.keepclear.engine.move.JobState.DONE) {
            files.filter {
                it.state in failedStates
            }.take(MAX_FAILED_LISTED).map { FailedFile(it.sourcePath, it.failReason) }
        } else {
            emptyList()
        }
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
            freeCandidates = candidates,
            remainingBytes = (total - done).coerceAtLeast(0),
            targetFreeBytes = controller.targetFree[job.id],
            failedFiles = failed,
            network = OpenedTarget.isNetwork(job.targetRef),
        )
    }

    /** What the user selected before opening the folder picker; survives configuration changes. */
    var pendingItems: List<ClassifiedItem> = emptyList()

    /** MOVE-2: the user picked a folder or drive; check it and run the pre-flight for [pendingItems]. */
    fun onTreePicked(uri: Uri) {
        if (_state.value.restore == RestoreStep.Pick) {
            val load = restoreLoading()
            viewModelScope.launch {
                val label = withContext(Dispatchers.IO) { treeLabel(uri) }
                restoreFrom(load, withContext(Dispatchers.IO) { OpenedTarget.of(app.openTarget(uri, label)) })
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
            val load = restoreLoading()
            viewModelScope.launch {
                val check = withContext(Dispatchers.IO) { app.openNetworkDrive(location, location.host) }
                if (check is OpenedTarget.Ok) withContext(Dispatchers.IO) { app.networkDrives.save(location) }
                restoreFrom(load, check)
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
        val load = if (restoring) restoreLoading() else RestoreLoads.NONE
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
                restoreFrom(load, check, nextcloud = true)
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

    /**
     * F2 (C76): Settings › Advanced › "Computer at home". KeepClear connects to the address typed, once; the place is
     * saved (password sealed) only if that worked, and the connection is closed again. Backing up and restoring
     * then offer it next to the USB drive or memory card.
     */
    fun setUpNetworkDrive(location: io.github.tomerar.keepclear.backup.smb.SmbLocation) {
        if (_state.value.networkSetup == NetworkSetup.Checking) return
        _state.update { it.copy(networkSetup = NetworkSetup.Checking) }
        viewModelScope.launch {
            val check = withContext(Dispatchers.IO) { app.openNetworkDrive(location, location.host) }
            val result = when (check) {
                is OpenedTarget.Ok -> {
                    withContext(Dispatchers.IO) {
                        app.networkDrives.save(location)
                        check.target.useTarget { }
                    }
                    NetworkSetup.Saved(location.host)
                }

                OpenedTarget.OnThisPhone -> {
                    NetworkSetup.Failed(TargetProblem.NETWORK_ON_THIS_PHONE)
                }

                OpenedTarget.Refused -> {
                    NetworkSetup.Failed(TargetProblem.REFUSED)
                }

                else -> {
                    NetworkSetup.Failed(TargetProblem.UNREACHABLE)
                }
            }
            _state.update { it.copy(networkSetup = result) }
        }
    }

    fun clearNetworkSetup() = _state.update { it.copy(networkSetup = null) }

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
        restoreLoads.end()
        controller.results.update { it.copy(restore = null) }
        _state.update { it.copy(restore = null) }
    }

    /** PR #48 review: each load belongs to the restore flow that started it; a late one never shows in a newer flow. */
    private val restoreLoads = RestoreLoads()

    /** F2: the backup is being opened or read; the dialog shows progress and Cancel. Returns this load's token. */
    private fun restoreLoading(): Int {
        _state.update { it.copy(restore = RestoreStep.Loading) }
        return restoreLoads.begin()
    }

    /** F2: true while the flow still waits for [load] (Cancel closes it; a late or older result is dropped). */
    private fun restoreWaiting(load: Int): Boolean =
        restoreLoads.isCurrent(load) && _state.value.restore == RestoreStep.Loading

    /** Shows [step] only if the flow still waits for [load]. */
    private fun restoreLoaded(load: Int, step: RestoreStep) {
        if (restoreWaiting(load)) _state.update { it.copy(restore = step) }
    }

    private suspend fun restoreFrom(load: Int, check: OpenedTarget, nextcloud: Boolean = false) {
        if (check is OpenedTarget.Ok && !restoreWaiting(load)) {
            // Cancelled while it opened: close the connection again, show nothing.
            withContext(Dispatchers.IO) { check.target.useTarget { } }
            return
        }
        if (!restoreWaiting(load)) return
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
        if (!restoreWaiting(load)) return
        when (roots.size) {
            0 -> restoreLoaded(load, RestoreStep.NoBackup)
            1 -> chooseBackup(roots.single())
            else -> restoreLoaded(load, RestoreStep.Backups(roots))
        }
    }

    /** The phone's shared storage, where restored files go. */
    private val phoneRoot: String get() = android.os.Environment.getExternalStorageDirectory().absolutePath

    /** D1: free space on [phoneRoot]. */
    private val phoneSpace = io.github.tomerar.keepclear.engine.restore.SpaceProbe { measurePhoneSpace(app, phoneRoot) }

    fun chooseBackup(rootRel: String) {
        val engine = restoreEngine ?: return
        val load = restoreLoading()
        viewModelScope.launch {
            val (entries, groups, space) = withContext(Dispatchers.IO) {
                val entries = engine.catalog(rootRel).orEmpty()
                // D2: where each file would go, shown per month before anything is chosen.
                Triple(entries, restoreGroups(engine.plan(entries)), phoneSpace.measure())
            }
            if (!restoreWaiting(load)) return@launch
            restoreEntries = entries
            if (entries.isEmpty()) {
                restoreLoaded(load, RestoreStep.NoBackup)
                return@launch
            }
            restoreLoaded(load, RestoreStep.Catalog(rootRel, groups, space))
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

    /** F2: "Stop" while restoring: what was restored stays; the result says the restore was interrupted. */
    fun cancelRestore() {
        if (controller.task.value?.kind == TaskKind.RESTORE) controller.cancelTask()
    }

    fun pause() = controller.pause(PauseReason.PAUSED_BY_USER)

    fun stop() = controller.pause(PauseReason.CANCELLED)

    fun resume(jobId: String) = controller.start(app, jobId)

    /**
     * MOVE-10, owner decision P1: after the user confirmed in this session, free the originals the confirmation names
     * (the free step's "keep on the phone" options already left the rest out). In batches: the copies of a batch are
     * read back from the drive and hashed again, then the executor re-checks and re-hashes each original right before
     * deleting it for good (or, with the user's setting, moving it to the 30-day trash). The drive stays open until
     * the end; if it goes away, freeing stops and says so.
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
            val mode = when {
                app.testMode.isOn -> FreeMode.TEST
                app.freeSettings.keepInTrash -> FreeMode.TRASH
                else -> FreeMode.FOR_GOOD
            }
            controller.updateTask(TaskKind.FREE) { it.copy(freeMode = mode) }
            // Owner brief H1 item 7: measured like the clean result (StatFs before and after, off the main thread).
            val before = controller.phoneFree()
            val report = check.target.useTarget { target ->
                io.github.tomerar.keepclear.engine.move.FreeOriginals(
                    engine(target),
                    run = { ops, onResult ->
                        // Review PR B: the confirmation lists the originals the user saw; anything else is rejected.
                        val plan = ActionPlan(UUID.randomUUID().toString(), ops, "move")
                            .confirm(confirmation, app.sessionId)
                        app.executor(onResult = onResult).run(plan)
                    },
                ).free(jobId, confirmation.paths) { p ->
                    progress(p.processed, p.total)
                    controller.updateTask(TaskKind.FREE) { it.copy(freed = p.freed, freeMode = mode) }
                }
            }
            val after = controller.phoneFree()
            controller.results.update {
                it.copy(freeResult = freeResult(jobId, job.targetLabel, report).measured(before, after))
            }
        }
    }

    private fun engine(
        target: io.github.tomerar.keepclear.backup.BackupTarget,
        onProgress: (MoveFile) -> Unit = {},
    ) = MoveEngine(
        app.localGateway,
        target,
        controller.store,
        onProgress = onProgress,
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
        _state.update { it.copy(recheck = null) }
        controller.runTask(app, TaskKind.CHECK) { progress -> checkNow(jobId, progress) }
    }

    private fun showCheck(step: BackupCheck?) = controller.results.update { it.copy(check = step) }

    private suspend fun checkNow(jobId: String, progress: (done: Int, total: Int) -> Unit) {
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
                withContext(Dispatchers.IO) { proofEngine().unprovenNow(jobId, includeConfirmed = true) } == 0
            val evidence = if (OpenedTarget.isNetwork(ref)) {
                // M15: a network drive's reads all come from the server; a new connection is a fresh read, so
                // there is nothing to unplug (decided conservatively: progress.md C8).
                FreshRead.NEW_SESSION
            } else if (rebooted) {
                FreshRead.REBOOTED
            } else {
                // Owner brief H1 item 6: live states from the volume source ("Unplugged ✓ — plug it back in").
                val waiter = ReplugWaiter(
                    mounted = { app.drives.isMounted(volume) },
                    present = { present() },
                    pollMillis = REPLUG_POLL_MILLIS,
                )
                showCheck(BackupCheck.Unplug(jobId))
                kotlinx.coroutines.withTimeoutOrNull(CHECK_WAIT_MILLIS) { waiter.awaitGone() } ?: return timeout(jobId)
                showCheck(BackupCheck.PlugBack(jobId))
                kotlinx.coroutines.withTimeoutOrNull(CHECK_WAIT_MILLIS) { waiter.awaitBack() } ?: return timeout(jobId)
                // D4: Android reports the mount a moment after the files are reachable; wait briefly for it. Without
                // it the engine proves nothing and the result says how many files still need a check.
                kotlinx.coroutines.withTimeoutOrNull(MOUNT_EVENT_WAIT_MILLIS) {
                    suspend fun stale() = withContext(Dispatchers.IO) {
                        proofEngine().unprovenNow(jobId, includeConfirmed = true)
                    }
                    while (stale() > 0) {
                        kotlinx.coroutines.delay(POLL_MILLIS / 4)
                    }
                }
                FreshRead.REATTACHED
            }
            val target = attached() ?: return timeout(jobId)
            showCheck(BackupCheck.Checking(jobId))
            // Owner brief H1 item 5: the check's own segment in the notification counts the files read.
            val toCheck = withContext(Dispatchers.IO) {
                store.files(jobId).count { it.state == io.github.tomerar.keepclear.engine.move.MoveFileState.VERIFIED }
            }
            var checked = 0
            progress(0, toCheck)
            val result = try {
                withContext(Dispatchers.IO) {
                    target.useTarget {
                        engine(it, onProgress = { _ -> progress(++checked, toCheck) }).confirm(jobId, evidence)
                    }
                }
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

    /** Owner brief H1 item 11: the reminder was tapped; the Backup tab offers to check [jobId] again. */
    fun offerRecheck(jobId: String) = _state.update { it.copy(recheck = jobId) }

    /** The re-check offer was used or closed. */
    fun clearRecheck() = _state.update { it.copy(recheck = null) }

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

private const val POLL_MILLIS = 1_000L

/** Owner brief H1 item 6: how often the re-plug step looks at the volume source (a memory read; cheap). */
private const val REPLUG_POLL_MILLIS = 500L
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

/**
 * PR #48 review: tokens for the restore flow's loads. A new load or closing the flow makes every older token stale,
 * so an opening or catalog read that finishes late is dropped instead of showing in a newer flow. Main thread only.
 */
internal class RestoreLoads {
    private var current = 0

    fun begin(): Int = ++current

    fun end() {
        current++
    }

    fun isCurrent(load: Int): Boolean = load != NONE && load == current

    companion object {
        const val NONE = 0
    }
}

/** How often the Backup screen looks again whether a backup paused for the battery can go on (P3). Tests shorten it. */
@Volatile
internal var batteryPollMillis: Long = 30_000L

/**
 * Owner brief H1 item 7: adds the measured difference ([before] → [after] free bytes) when originals were deleted for
 * good (the trash and test mode free nothing yet). Nothing is claimed when the phone reported no difference.
 */
internal fun FreeResult.measured(before: Long?, after: Long?): FreeResult {
    if (!forGood || simulated || done == 0 || before == null || after == null) return this
    val freed = after - before
    return if (freed > 0) copy(measuredFreed = freed, nowFree = after) else this
}

/** Owner decision P1: what the result banner shows after freeing. */
internal fun freeResult(
    jobId: String,
    targetLabel: String,
    report: io.github.tomerar.keepclear.engine.move.FreeReport,
): FreeResult {
    val freed = report.deletedForGood + report.trashed + report.simulated
    return FreeResult(
        jobId = jobId,
        done = freed.size,
        bytes = freed.sumOf { it.expectedSize },
        skipped = report.skippedCount,
        failed = report.failed + if (report.rejected) report.total - freed.size else 0,
        simulated = report.simulated.isNotEmpty(),
        forGood = report.deletedForGood.isNotEmpty() ||
            report.simulated.any { it.kind == io.github.tomerar.keepclear.core.safety.OpKind.DELETE_FOR_GOOD },
        targetLabel = targetLabel,
        interrupted = report.interrupted,
    )
}
