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
import io.github.tomerar.keepclear.backup.saf.SafTargets
import io.github.tomerar.keepclear.backup.saf.TargetCheck
import io.github.tomerar.keepclear.core.database.RoomMoveJobStore
import io.github.tomerar.keepclear.core.i18n.R
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

/** App-wide MOVE state: which job runs now, for the UI and the notification (MOVE-11/12). */
class MoveController(private val app: KeepClearApplication) {
    val store by lazy { RoomMoveJobStore(app.database.moveDao()) }

    private val _running = MutableStateFlow<String?>(null)

    /** Id of the job the foreground service is running, or null. */
    val running: StateFlow<String?> = _running

    /** Bumped on every file transition so screens can reload. */
    val ticks = MutableStateFlow(0L)

    internal fun setRunning(id: String?) {
        _running.value = id
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

    fun start(context: Context, jobId: String) {
        val intent = Intent(context, MoveService::class.java).putExtra(MoveService.EXTRA_JOB, jobId)
        ContextCompat.startForegroundService(context, intent)
    }

    /** Set by the running service; same process, so no background service start is needed (Android 12+). */
    @Volatile internal var stopper: ((PauseReason) -> Unit)? = null

    /** Pauses (or stops) the running job after the current file. No-op when nothing runs. */
    fun pause(reason: PauseReason) {
        stopper?.invoke(reason)
    }
}

/**
 * Runs one MOVE job as a `dataSync` foreground service (MOVE-12). Not exported; it only acts on explicit
 * intents from KeepClear itself and never deletes anything: copy and verify only (SAFETY-1).
 */
class MoveService : Service() {
    companion object {
        const val EXTRA_JOB = "job"
        const val EXTRA_REASON = "reason"
        private const val NOTIFICATION_ID = 41
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var work: Job? = null

    @Volatile private var stopReason: PauseReason = PauseReason.PAUSED_BY_USER

    private val app get() = application as KeepClearApplication

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val jobId = intent?.getStringExtra(EXTRA_JOB) ?: return START_NOT_STICKY.also { stopSelf() }
        MoveNotifications.ensureChannel(this)
        val first = MoveNotifications.progress(this, 0, 0)
        startForeground(NOTIFICATION_ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (work?.isActive == true) return START_NOT_STICKY
        app.moveController.setRunning(jobId)
        app.moveController.stopper = { reason ->
            stopReason = reason
            work?.cancel()
        }
        work = scope.launch {
            try {
                run(jobId)
            } finally {
                app.moveController.stopper = null
                app.moveController.setRunning(null)
                app.moveController.ticks.value++
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }
        // Not restarted by the system: after a kill the job shows as interrupted and waits for the user (MIG-4).
        return START_NOT_STICKY
    }

    private suspend fun run(jobId: String) {
        val store = app.moveController.store
        val job = store.job(jobId) ?: return
        val treeUri = job.targetRef?.toUri()
        val check = treeUri?.let { SafTargets.open(contentResolver, it, job.targetLabel) }
        if (check !is TargetCheck.Ok || check.target.id != job.targetId) {
            val reason = if (check is TargetCheck.Ok) PauseReason.WRONG_TARGET else PauseReason.TARGET_DISCONNECTED
            store.putJob(job.copy(state = JobState.PAUSED, pauseReason = reason))
            MoveNotifications.result(this, store.job(jobId)!!, 0)
            return
        }
        val total = store.files(jobId).size
        var verified = store.files(jobId).count { it.state == MoveFileState.VERIFIED }
        val nm = getSystemService(NotificationManager::class.java)
        val engine = MoveEngine(
            source = app.fileGateway,
            target = check.target,
            store = store,
            onProgress = { f ->
                if (f.state == MoveFileState.VERIFIED) verified++
                app.moveController.ticks.value++
                if (MoveNotifications.allowed(this)) {
                    nm.notify(NOTIFICATION_ID, MoveNotifications.progress(this, verified, total))
                }
            },
            cancelReason = { stopReason },
        )
        val end = engine.run(jobId)
        MoveNotifications.result(this, end, verified)
    }

    /** Android 15+: the dataSync time limit ends; checkpoint and pause (MOVE-12). */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopReason = PauseReason.SYSTEM_TIME_LIMIT
        work?.cancel()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
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
            context.getString(R.string.move_notification_done, verified)
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
