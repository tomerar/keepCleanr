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
 * H1: proof that a re-read can't come from the phone's page cache. [REATTACHED]: the app watched the drive
 * disappear and come back (unmount drops the cache). [REBOOTED]: the phone restarted since the first check.
 */
enum class FreshRead { REATTACHED, REBOOTED }

/** Result of the second pass ("Check backup", MOVE-15). */
data class CheckResult(
    val ok: Int,
    val damaged: Int,
    val missing: Int,
    /** The drive went away or access was lost mid-check; files not reached keep their state. */
    val interrupted: Boolean = false,
)

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
                total = files.size,
            )
        }
    }
}
