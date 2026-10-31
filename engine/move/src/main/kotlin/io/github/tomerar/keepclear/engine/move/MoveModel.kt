// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.move

import io.github.tomerar.keepclear.core.storage.KPath

/** Per-file state machine (design.md §6.2). Stored by name; never rename (MIG-2). */
enum class MoveFileState { PENDING, COPYING, COPIED, PLACED, VERIFIED, FAILED, SRC_CHANGED }

enum class FailReason { SOURCE_MISSING, SOURCE_UNREADABLE, TOO_LARGE_FOR_TARGET, HASH_MISMATCH, TARGET_ERROR }

enum class JobState { READY, RUNNING, PAUSED, DONE }

enum class PauseReason { TARGET_DISCONNECTED, TARGET_FULL, ACCESS_LOST, PHONE_LOW_SPACE, CANCELLED }

data class MoveJob(
    val id: String,
    val targetId: String,
    val targetLabel: String,
    /** Folder on the target that holds this device's backup, e.g. "KeepClear/OnePlus 10T". */
    val rootRel: String,
    val createdAt: Long,
    val state: JobState = JobState.READY,
    val pauseReason: PauseReason? = null,
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
)
