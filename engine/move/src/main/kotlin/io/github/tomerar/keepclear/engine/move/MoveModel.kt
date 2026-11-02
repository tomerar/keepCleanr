// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.move

import io.github.tomerar.keepclear.core.storage.KPath

/** Per-file state machine (design.md §6.2). Stored by name; never rename (MIG-2). */
enum class MoveFileState {
    PENDING,
    COPYING,
    COPIED,
    PLACED,
    VERIFIED,
    FAILED,
    SRC_CHANGED,

    /** Copied to a target that can't be read back (MOVE-14): kept there, never counted as verified. */
    COPIED_UNVERIFIED,
}

enum class FailReason { SOURCE_MISSING, SOURCE_UNREADABLE, TOO_LARGE_FOR_TARGET, HASH_MISMATCH, TARGET_ERROR }

enum class JobState { READY, RUNNING, PAUSED, DONE }

/** Stored by name; append only (MIG-2). CANCELLED = the user stopped the job; it can still be resumed. */
enum class PauseReason {
    TARGET_DISCONNECTED,
    TARGET_FULL,
    ACCESS_LOST,
    PHONE_LOW_SPACE,
    CANCELLED,
    PAUSED_BY_USER,

    /** Android's foreground-service time limit (Android 15+, MOVE-12). */
    SYSTEM_TIME_LIMIT,

    /** The app was updated or killed mid-job (MIG-4). */
    INTERRUPTED,

    /** A different drive or folder is attached than the one this job started on (E-MOVE-2). */
    WRONG_TARGET,

    /** An unexpected storage error; the job pauses instead of failing (low-severity review item). */
    UNEXPECTED_ERROR,
}

/**
 * H1: proof that a re-read can't come from the phone's page cache. [REATTACHED]: the drive was unmounted and
 * mounted again. [REBOOTED]: the phone restarted since the first check. D4: for a drive on the phone the engine
 * never takes the caller's word for either: it checks per file that the file's first check happened in an
 * earlier boot ([MoveFile.verifyBootId]) or before an unmount → mount the app saw for that very volume
 * ([MoveFile.verifyAttach], [AttachGeneration]). Files without such proof are left unconfirmed.
 */
enum class FreshRead {
    REATTACHED,
    REBOOTED,

    /** M15: a network drive read over a brand-new connection; valid only for targets whose reads come from the server. */
    NEW_SESSION,
}

/**
 * M17b, ADR 0011 ("the method is recorded per file"): how a copy was proven equal to its original. Stored by
 * name; append only (MIG-2).
 */
enum class VerifyMethod {
    /** KeepClear read the copy back and hashed it (drives, network drives, any WebDAV server). */
    READ_BACK,

    /** The server hashed what it stored (Nextcloud `X-Hash-SHA256`, Dropbox `content_hash`) and it matched. */
    SERVER_DIGEST,
}

/** Result of the second pass ("Check backup", MOVE-15). */
data class CheckResult(
    val ok: Int,
    val damaged: Int,
    val missing: Int,
    /** The drive went away or access was lost mid-check; files not reached keep their state. */
    val interrupted: Boolean = false,
    /**
     * D4: files not read, because nothing proves a read now would come from the drive rather than the phone's
     * memory (checked in this boot, and no unmount → mount of the drive seen since). They stay unconfirmed.
     */
    val unproven: Int = 0,
)

/**
 * D4: how often the app has seen one volume come back (an unmount, then a mount of the same volume UUID) since
 * this process started ([epoch], random per process). Unmounting drops the volume's page cache, so a read after
 * such an event comes from the drive itself. Recorded per file when its copy is first verified.
 */
data class AttachGeneration(val epoch: String, val count: Long) {
    fun encode(): String = "$count@$epoch"

    /**
     * True when the volume was unmounted and mounted again after [earlier] was taken: later in the same process,
     * or at least once in this process when [earlier] is from an earlier process (which ended before this one
     * started, so every event seen now came after it).
     */
    fun reattachedSince(earlier: AttachGeneration): Boolean =
        if (earlier.epoch == epoch) count > earlier.count else count >= 1

    companion object {
        /** Null for anything that isn't a well-formed stamp (an old row, a damaged value). */
        fun decode(value: String?): AttachGeneration? {
            val count = value?.substringBefore('@', "")?.toLongOrNull() ?: return null
            val epoch = value.substringAfter('@', "")
            if (epoch.isEmpty() || count < 0) return null
            return AttachGeneration(epoch, count)
        }
    }
}

/**
 * D4: watches one process's volume events (the app feeds it from StorageManager's volume callback) and counts,
 * per volume UUID, how often a volume came back after being away. Pure state; thread-safe.
 */
class AttachTracker(private val epoch: String) {
    private val away = HashSet<String>()
    private val counts = HashMap<String, Long>()

    /** A volume changed state: [mounted] = readable now; anything else (unmounted, ejecting, removed, checking…). */
    @Synchronized
    fun onState(volumeUuid: String, mounted: Boolean) {
        val key = volumeUuid.lowercase(java.util.Locale.ROOT)
        if (!mounted) {
            away += key
        } else if (away.remove(key)) {
            counts[key] = (counts[key] ?: 0) + 1
        }
    }

    @Synchronized
    fun generation(volumeUuid: String): AttachGeneration =
        AttachGeneration(epoch, counts[volumeUuid.lowercase(java.util.Locale.ROOT)] ?: 0)
}

data class MoveJob(
    val id: String,
    val targetId: String,
    val targetLabel: String,
    /** Folder on the target that holds this device's backup, e.g. "KeepClear/OnePlus 10T". */
    val rootRel: String,
    val createdAt: Long,
    val state: JobState = JobState.READY,
    val pauseReason: PauseReason? = null,
    /** Opaque locator the app uses to reopen the target (a SAF tree URI). Never trusted on its own (E-MOVE-2). */
    val targetRef: String? = null,
    /** Boot of the last first-pass verification; a second pass in another boot is uncached (H1). */
    val verifyBootId: String? = null,
)

data class MoveFile(
    val jobId: String,
    val index: Int,
    val source: KPath,
    val sourcePath: String,
    val sourceSize: Long,
    val sourceMtime: Long,
    val sourceIdentity: String?,
    /** Final path on the target, relative to the target root. */
    val relTarget: String,
    val state: MoveFileState = MoveFileState.PENDING,
    val sourceSha256: String? = null,
    /**
     * M17, ADR 0011: Dropbox's content hash of the same bytes as [sourceSha256], computed in the same read while
     * copying, only for targets whose server reports one. Compared with the server's own value; it is never what
     * the original is checked against before freeing (that is always [sourceSha256]). Not stored by the Room job
     * store yet (M17b): after a restart it is null and the copy is read back instead.
     */
    val sourceContentHash: String? = null,
    val attempts: Int = 0,
    val failReason: FailReason? = null,
    val verifiedAt: Long? = null,
    /** True when the target name differs from the original (sanitised, deduplicated, shortened). */
    val nameChanged: Boolean = false,
    /**
     * True when [relTarget] is a file that was already on the drive and matched (E-MOVE-10). KeepClear did not
     * write it, so it is never deleted, even if a later read-back fails.
     */
    val reusedExisting: Boolean = false,
    /**
     * H1: when a **second**, uncached read of the copy matched ([FreshRead]). Only files with this set may be
     * freed. Cleared whenever the file is copied or verified again.
     */
    val confirmedAt: Long? = null,
    /** M17b: how the copy was verified; null until it is (and for files verified before this was recorded). */
    val verifiedBy: VerifyMethod? = null,
    /**
     * D4: the boot in which this copy's first check ran. The second check may rely on a reboot only when it runs
     * in another boot. Null for files verified before this was recorded (the job's [MoveJob.verifyBootId] is used).
     */
    val verifyBootId: String? = null,
    /** D4: [AttachGeneration.encode] of the drive's volume when this copy was first checked; null if unknown. */
    val verifyAttach: String? = null,
) {
    val tempRel: String get() = relTarget.substringBeforeLast('/') + "/.kc-tmp-$jobId-$index"
}

/** Port: durable job state (Room in the app, in-memory in tests). Every transition is written here (MOVE-11). */
interface MoveJobStore {
    suspend fun putJob(job: MoveJob)

    suspend fun job(id: String): MoveJob?

    suspend fun putFile(file: MoveFile)

    suspend fun files(jobId: String): List<MoveFile>
}

/** What the receipt shows (MOVE-9). */
data class Receipt(
    val jobId: String,
    val targetLabel: String,
    val rootRel: String,
    val verified: Int,
    val verifiedBytes: Long,
    val failed: Map<FailReason, Int>,
    val changed: Int,
    val pending: Int,
    val namesChanged: Int,
    /** Copied but not checkable (MOVE-14); never offered for freeing. */
    val unverified: Int = 0,
    /** Verified twice, the second time uncached (H1); only these may be freed. */
    val confirmed: Int = 0,
    val confirmedBytes: Long = 0,
    /** M17b: verified files the server itself checked ([VerifyMethod.SERVER_DIGEST]); shown as "checked by …". */
    val checkedByServer: Int = 0,
    val total: Int = verified + failed.values.sum() + changed + pending + unverified,
) {
    val complete: Boolean get() = pending == 0

    companion object {
        /** States that need no more work in this job. */
        val DONE_STATES = setOf(
            MoveFileState.VERIFIED,
            MoveFileState.FAILED,
            MoveFileState.SRC_CHANGED,
            MoveFileState.COPIED_UNVERIFIED,
        )

        fun of(job: MoveJob, files: List<MoveFile>): Receipt {
            val verified = files.filter { it.state == MoveFileState.VERIFIED }
            return Receipt(
                jobId = job.id,
                targetLabel = job.targetLabel,
                rootRel = job.rootRel,
                verified = verified.size,
                verifiedBytes = verified.sumOf { it.sourceSize },
                failed = files.filter { it.state == MoveFileState.FAILED }
                    .groupingBy { it.failReason ?: FailReason.TARGET_ERROR }
                    .eachCount(),
                changed = files.count { it.state == MoveFileState.SRC_CHANGED },
                pending = files.count { it.state !in DONE_STATES },
                namesChanged = files.count { it.nameChanged },
                unverified = files.count { it.state == MoveFileState.COPIED_UNVERIFIED },
                confirmed = verified.count { it.confirmedAt != null },
                confirmedBytes = verified.filter { it.confirmedAt != null }.sumOf { it.sourceSize },
                checkedByServer = verified.count { it.verifiedBy == VerifyMethod.SERVER_DIGEST },
                total = files.size,
            )
        }
    }
}
