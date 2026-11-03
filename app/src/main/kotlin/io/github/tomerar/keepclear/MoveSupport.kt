// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import io.github.tomerar.keepclear.core.database.RoomMoveJobStore
import io.github.tomerar.keepclear.core.i18n.R
import io.github.tomerar.keepclear.engine.move.BatteryPolicy
import io.github.tomerar.keepclear.engine.move.JobState
import io.github.tomerar.keepclear.engine.move.MoveEngine
import io.github.tomerar.keepclear.engine.move.MoveFileState
import io.github.tomerar.keepclear.engine.move.MoveJob
import io.github.tomerar.keepclear.engine.move.PauseReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** E3: work that runs in the foreground service besides copying (MOVE-10, MOVE-15, MOVE-17). */
enum class TaskKind { FREE, CHECK, RESTORE }

/** A running [TaskKind] and how far it got (files done of total; 0 of 0 while it prepares). */
data class TaskProgress(
    val kind: TaskKind,
    val done: Int = 0,
    val total: Int = 0,
    /** Owner decision P1, [TaskKind.FREE] only: how the originals are freed, and how many so far. */
    val freeMode: FreeMode? = null,
    val freed: Int = 0,
)

/** Owner decision P1: what freeing does to the originals in this run. */
enum class FreeMode {
    /** Deleted for good from the phone; the checked copy is the undo. */
    FOR_GOOD,

    /** The user's setting: to KeepClear's 30-day trash. */
    TRASH,

    /** Test mode (SAFETY-9): checked only, nothing changes. */
    TEST,
}

/**
 * E3: results of foreground work, kept app-wide so they survive leaving the app (the screen's ViewModel can be
 * gone when they arrive) and show when KeepClear is opened again.
 */
data class TaskResults(
    val check: BackupCheck? = null,
    val freeResult: FreeResult? = null,
    /** A restore's [RestoreStep.Running] or [RestoreStep.Done]; null when no restore ran since the flow closed. */
    val restore: RestoreStep? = null,
)

/** App-wide MOVE state: which job runs now, for the UI and the notification (MOVE-11/12). */
class MoveController(private val app: KeepClearApplication) {
    /** Tests only: wraps the database access of [store] (e.g. to count reads). */
    internal var daoWrapper: (io.github.tomerar.keepclear.core.database.MoveDao) ->
    io.github.tomerar.keepclear.core.database.MoveDao = { it }

    val store by lazy { RoomMoveJobStore(daoWrapper(app.database.moveDao())) }

    /** App-wide work that must outlive a screen (E3) and the timeout checkpoint (E2). Never the main thread. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _running = MutableStateFlow<String?>(null)

    /** Id of the job the foreground service is running, or null. */
    val running: StateFlow<String?> = _running

    /** Bumped on every file transition so screens can reload. */
    val ticks = MutableStateFlow(0L)

    internal fun setRunning(id: String?) {
        _running.value = id
    }

    private val _task = MutableStateFlow<TaskProgress?>(null)

    /** E3: the free / check / restore running in the foreground service, or null. */
    val task: StateFlow<TaskProgress?> = _task

    /** E3: what the last free / check / restore found. */
    val results = MutableStateFlow(TaskResults())

    /** E3: bumped when a free, check or restore ended; Home then measures free space and reloads trash and scan. */
    val finished = MutableStateFlow(0L)

    @Volatile private var taskJob: Job? = null

    /** Starts the service for a task; tests replace it (Robolectric runs no services). */
    internal var startTaskService: (Context, TaskKind) -> Unit = { context, kind ->
        ContextCompat.startForegroundService(
            context,
            Intent(context, MoveService::class.java).putExtra(MoveService.EXTRA_TASK, kind.name),
        )
    }

    /**
     * E3: runs [block] in the foreground service's keep-alive, on the app's scope: leaving KeepClear doesn't stop
     * it, and the notification shows its progress. One task at a time; returns null when one already runs.
     */
    fun runTask(
        context: Context,
        kind: TaskKind,
        onCancelled: () -> Unit = {},
        block: suspend (progress: (done: Int, total: Int) -> Unit) -> Unit,
    ): Job? {
        synchronized(this) {
            // PR #45 review: a cancelled task counts as running until it has completed (its clean-up, e.g. closing
            // a network connection, may still run), so a replacement can't start under it.
            if (taskJob?.isCompleted == false) return null
            _task.value = TaskProgress(kind)
            runCatching { startTaskService(context, kind) }
            val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                try {
                    block { done, total -> _task.value = TaskProgress(kind, done, total) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // PR #45 review: Android's time limit (or Stop) must never leave a result that blocks the screen.
                    onCancelled()
                    throw e
                } finally {
                    // Only the task that owns the state clears it (never a newer one's progress or notification).
                    val mine = coroutineContext[Job]
                    synchronized(this@MoveController) { if (taskJob === mine) _task.value = null }
                    ticks.value++
                    finished.value++
                }
            }
            taskJob = job
            job.start()
            return job
        }
    }

    /** Owner decision P1: a finer progress for the running task of [kind] (e.g. how many originals were freed). */
    fun updateTask(kind: TaskKind, update: (TaskProgress) -> TaskProgress) {
        synchronized(this) { _task.value?.takeIf { it.kind == kind }?.let { _task.value = update(it) } }
    }

    /** E3: stops the running task (the user cancelled, or Android's time limit). */
    fun cancelTask() {
        taskJob?.cancel()
    }

    /**
     * MIG-4: a job left RUNNING while no service runs was interrupted (app update, crash, reboot). It is shown
     * as paused; copying resumes only when the user taps Continue. Deleting originals always needs a new
     * confirmation (MIG-5): confirmed plans live only in memory, bound to the session.
     */
    suspend fun markInterrupted(): List<MoveJob> {
        if (_running.value != null) return emptyList()
        return store.jobs().filter { it.state == JobState.RUNNING }.map { job ->
            job.copy(state = JobState.PAUSED, pauseReason = PauseReason.INTERRUPTED).also { store.putJob(it) }
        }
    }

    /** Starts the service for a copy job; tests replace it (Robolectric runs no services). */
    internal var startJobService: (Context, String) -> Unit = { context, jobId ->
        val intent = Intent(context, MoveService::class.java).putExtra(MoveService.EXTRA_JOB, jobId)
        ContextCompat.startForegroundService(context, intent)
    }

    fun start(context: Context, jobId: String) = startJobService(context, jobId)

    /** Set by the running service; same process, so no background service start is needed (Android 12+). */
    @Volatile internal var stopper: ((PauseReason) -> Unit)? = null

    /** Pauses (or stops) the running job after the current file. No-op when nothing runs. */
    fun pause(reason: PauseReason) {
        stopper?.invoke(reason)
    }

    /** E2: the time limit's checkpoint: a job still marked running is paused with [reason]. */
    suspend fun checkpoint(jobId: String, reason: PauseReason) {
        val job = store.job(jobId) ?: return
        if (job.state == JobState.RUNNING) store.putJob(job.copy(state = JobState.PAUSED, pauseReason = reason))
    }
}

/**
 * Runs one MOVE job as a `dataSync` foreground service (MOVE-12), and keeps the app alive with a notification
 * while a free, check or restore runs (E3). Not exported; it only acts on explicit intents from KeepClear itself.
 * The service never deletes anything itself: freeing goes through the executor (SAFETY-1).
 */
class MoveService : Service() {
    companion object {
        const val EXTRA_JOB = "job"
        const val EXTRA_TASK = "task"
        const val EXTRA_REASON = "reason"
        private const val NOTIFICATION_ID = 41

        /** Owner decision P3: how often a backup paused for the battery looks again. */
        private const val BATTERY_POLL_MILLIS = 30_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * The newest start request. Stopping calls `stopSelf(latestStartId)`, which does nothing if another
     * `startForegroundService()` arrived meanwhile (e.g. Continue tapped right after Pause): a plain `stopSelf()`
     * would destroy the service before that start calls `startForeground`, and Android kills the app.
     */
    @Volatile private var latestStartId = 0

    private val app get() = application as KeepClearApplication

    private val runner: MoveRunner by lazy {
        MoveRunner(
            scope = scope,
            checkpointScope = app.moveController.scope,
            runJob = { id, reason -> run(id, reason) },
            checkpoint = { id, reason -> app.moveController.checkpoint(id, reason) },
            // After a job: stop unless a free / check / restore still runs (its end stops the service then).
            stopService = { if (runner.timedOut || app.moveController.task.value == null) stopNow() },
            onStarted = { id ->
                app.moveController.setRunning(id)
                app.moveController.stopper = { reason -> runner.pause(reason) }
            },
            onEnded = {
                app.moveController.stopper = null
                app.moveController.setRunning(null)
                app.moveController.ticks.value++
            },
        )
    }

    /** Watches a free / check / restore (E3); the service stops when it ends and no copy runs. */
    private var taskWatch: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        val jobId = intent?.getStringExtra(EXTRA_JOB)
        val task = intent?.getStringExtra(EXTRA_TASK)?.let { name -> TaskKind.entries.firstOrNull { it.name == name } }
        if (jobId == null && task == null) return START_NOT_STICKY.also { stopSelf(startId) }
        MoveNotifications.ensureChannel(this)
        // Every startForegroundService() must be answered with startForeground(), even one that stops at once.
        val first = if (jobId != null) MoveNotifications.progress(this, 0, 0) else MoveNotifications.task(this, task)
        startForeground(NOTIFICATION_ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (jobId != null) {
            if (!runner.start(jobId)) stopNow()
        } else {
            watchTask()
        }
        // Not restarted by the system: after a kill the job shows as interrupted and waits for the user (MIG-4).
        return START_NOT_STICKY
    }

    private fun watchTask() {
        if (taskWatch?.isActive == true) return
        val nm = getSystemService(NotificationManager::class.java)
        val throttle = NotificationThrottle()
        taskWatch = scope.launch {
            app.moveController.task.collect { progress ->
                if (progress == null) {
                    if (!runner.busy) stopNow()
                    return@collect
                }
                // A copy that runs at the same time keeps its own progress in the notification.
                if (!runner.busy && MoveNotifications.allowed(this@MoveService) && throttle.ready()) {
                    nm.notify(NOTIFICATION_ID, MoveNotifications.task(this@MoveService, progress.kind, progress))
                }
            }
        }
    }

    @Synchronized
    private fun stopNow() {
        stopForeground(STOP_FOREGROUND_REMOVE) // the result notification replaces the progress one
        stopSelf(latestStartId)
    }

    /** Owner decision P3: the job while it waits for the battery (paused, battery low); null while it copies. */
    @Volatile private var currentWait: MoveJob? = null

    private suspend fun run(jobId: String, stopReason: () -> PauseReason) {
        val store = app.moveController.store
        val job = store.job(jobId) ?: return
        // A picked drive/folder or the network drive (M15); identity must match the job's (E-MOVE-2).
        val check = job.targetRef?.let { app.openTargetRef(it, job.targetLabel) }
        if (check !is OpenedTarget.Ok || check.target.id != job.targetId) {
            (check as? OpenedTarget.Ok)?.target?.useTarget { }
            val reason = if (check is OpenedTarget.Ok) PauseReason.WRONG_TARGET else PauseReason.TARGET_DISCONNECTED
            store.putJob(job.copy(state = JobState.PAUSED, pauseReason = reason))
            MoveNotifications.result(this, store.job(jobId)!!, 0)
            return
        }
        val total = store.files(jobId).size
        var verified = store.files(jobId).count { it.state == MoveFileState.VERIFIED }
        val nm = getSystemService(NotificationManager::class.java)
        // E7: at most one notification update a second, however fast small files go.
        val throttle = NotificationThrottle()
        val engine = MoveEngine(
            source = app.localGateway,
            target = check.target,
            store = store,
            onProgress = { f ->
                if (f.state == MoveFileState.VERIFIED) verified++
                app.moveController.ticks.value++
                if (MoveNotifications.allowed(this) && throttle.ready()) {
                    nm.notify(NOTIFICATION_ID, MoveNotifications.progress(this, verified, total))
                }
            },
            cancelReason = stopReason,
            bootId = { app.trashClock.bootId() },
            // D4: each file's first check records the drive's mount generation.
            attachGeneration = { app.volumeEvents.generation(check.target.volumeId) },
            // Owner decision P3: pauses itself at 10 % on battery.
            battery = app.batterySource,
        )
        // The target (a network drive's connection) is released when the job ends, fails or is cancelled.
        val end = check.target.useTarget {
            try {
                // Owner decision P3: a pause for the battery waits here (the service stays, so nothing has to start
                // it again from the background) and goes on once the phone charges; Pause/Stop end the wait.
                BatteryPolicy.runWithPauses(
                    battery = app.batterySource,
                    wait = { kotlinx.coroutines.delay(BATTERY_POLL_MILLIS) },
                    onWaiting = { paused ->
                        app.moveController.ticks.value++
                        if (MoveNotifications.allowed(this@MoveService)) {
                            nm.notify(NOTIFICATION_ID, MoveNotifications.batteryPaused(this@MoveService))
                        }
                        // Pause or Stop while waiting: the job keeps its checkpoints and says why it stopped.
                        currentWait = paused
                    },
                ) {
                    currentWait = null
                    engine.run(jobId)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                currentWait?.let { waiting ->
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        store.putJob(waiting.copy(pauseReason = stopReason()))
                    }
                }
                throw e
            } catch (e: Exception) {
                // Never crash the service: keep every checkpoint and pause (review Low). No paths in the log.
                android.util.Log.w("KeepClear-Move", "job paused after ${e.javaClass.simpleName}")
                (store.job(jobId) ?: job).copy(state = JobState.PAUSED, pauseReason = PauseReason.UNEXPECTED_ERROR)
                    .also { store.putJob(it) }
            }
        }
        MoveNotifications.result(this, end, verified)
    }

    /**
     * Android 15+: the dataSync time limit ends (MOVE-12, E2). Checkpoint, stop at once (a blocked network read may
     * never notice the cancellation), drop a waiting start, and stop a free / check / restore too.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        app.moveController.cancelTask()
        runner.onTimeout()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

/** E7: lets a notification update through at most once per [intervalMillis]. */
class NotificationThrottle(
    private val intervalMillis: Long = 1_000,
    private val clock: () -> Long = android.os.SystemClock::elapsedRealtime,
) {
    private var last = Long.MIN_VALUE

    @Synchronized
    fun ready(): Boolean {
        val now = clock()
        if (last != Long.MIN_VALUE && now - last < intervalMillis) return false
        last = now
        return true
    }
}

/** Notification actions (pause/stop): explicit, immutable, to a non-exported receiver (SEC-5). */
class MoveControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reason = intent.getStringExtra(MoveService.EXTRA_REASON)
            ?.let { name -> PauseReason.entries.firstOrNull { it.name == name } } ?: return
        (context.applicationContext as KeepClearApplication).moveController.pause(reason)
    }
}

/** MIG-4: after an update, an unfinished job waits for the user: "Backup paused by an app update." */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        // Null when called outside a broadcast (tests).
        val pending: PendingResult? = goAsync()
        val app = context.applicationContext as KeepClearApplication
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val interrupted = app.moveController.markInterrupted()
                if (interrupted.isNotEmpty()) MoveNotifications.updated(context)
            } finally {
                pending?.finish()
            }
        }
    }
}

object MoveNotifications {
    private const val CHANNEL = "backup"
    private const val RESULT_ID = 42

    fun allowed(context: Context): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                context.getString(R.string.move_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun control(context: Context, reason: PauseReason, code: Int): PendingIntent = PendingIntent.getBroadcast(
        context,
        code,
        Intent(context, MoveControlReceiver::class.java).putExtra(MoveService.EXTRA_REASON, reason.name),
        PendingIntent.FLAG_IMMUTABLE,
    )

    /** E3: free / check / restore in progress (no pause or stop here: those live in the app). */
    fun task(context: Context, kind: TaskKind?, progress: TaskProgress? = null): Notification =
        NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(
                context.getString(
                    when (kind) {
                        TaskKind.FREE -> R.string.task_notification_free
                        TaskKind.CHECK -> R.string.task_notification_check
                        TaskKind.RESTORE, null -> R.string.task_notification_restore
                    },
                ),
            )
            .apply {
                if (progress != null && progress.total > 0) {
                    setContentText(
                        freeProgressText(context, progress) ?: context.resources.getQuantityString(
                            R.plurals.task_progress,
                            progress.total,
                            progress.done,
                            progress.total,
                        ),
                    )
                    setProgress(progress.total, progress.done, false)
                } else {
                    setProgress(0, 0, true)
                }
            }
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(context))
            .build()

    /** Owner decision P1: "Deleted 400 of 6,830" while freeing; null for any other task. */
    fun freeProgressText(context: Context, progress: TaskProgress): String? {
        val mode = progress.freeMode ?: return null
        val nf = java.text.NumberFormat.getIntegerInstance(context.resources.configuration.locales[0])
        return when (mode) {
            FreeMode.FOR_GOOD -> context.getString(
                R.string.free_progress_deleted,
                nf.format(progress.freed),
                nf.format(progress.total),
            )

            FreeMode.TRASH -> context.getString(
                R.string.free_progress_trash,
                nf.format(progress.freed),
                nf.format(progress.total),
            )

            FreeMode.TEST -> context.getString(
                R.string.free_progress_test,
                nf.format(progress.done),
                nf.format(progress.total),
            )
        }
    }

    /** Owner decision P3: the backup waits for the charger (or the battery) and goes on by itself. */
    fun batteryPaused(context: Context): Notification = NotificationCompat.Builder(context, CHANNEL)
        .setSmallIcon(android.R.drawable.stat_sys_upload)
        .setContentTitle(context.getString(R.string.move_notification_title))
        .setContentText(context.getString(R.string.move_paused_battery))
        .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.move_paused_battery)))
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(openApp(context))
        .addAction(0, context.getString(R.string.move_stop), control(context, PauseReason.CANCELLED, 2))
        .build()

    fun progress(context: Context, verified: Int, total: Int): Notification =
        NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(context.getString(R.string.move_notification_title))
            .setContentText(context.getString(R.string.move_notification_progress, verified, total))
            .setProgress(total, verified, total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(context))
            .addAction(0, context.getString(R.string.move_pause), control(context, PauseReason.PAUSED_BY_USER, 1))
            .addAction(0, context.getString(R.string.move_stop), control(context, PauseReason.CANCELLED, 2))
            .build()

    fun result(context: Context, job: MoveJob, verified: Int) {
        if (!allowed(context)) return
        ensureChannel(context)
        val text = if (job.state == JobState.DONE) {
            context.resources.getQuantityString(R.plurals.move_notification_done, verified, verified)
        } else {
            context.getString(R.string.move_notification_paused)
        }
        context.getSystemService(NotificationManager::class.java).notify(
            RESULT_ID,
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentTitle(context.getString(R.string.move_notification_title))
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(openApp(context))
                .build(),
        )
    }

    fun updated(context: Context) {
        if (!allowed(context)) return
        ensureChannel(context)
        context.getSystemService(NotificationManager::class.java).notify(
            RESULT_ID,
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentTitle(context.getString(R.string.move_notification_title))
                .setContentText(context.getString(R.string.move_notification_updated))
                .setAutoCancel(true)
                .setContentIntent(openApp(context))
                .build(),
        )
    }
}
