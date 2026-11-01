// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.safety

import io.github.tomerar.keepclear.core.storage.KPath

/** Stored by name; never rename (MIG-2). */
enum class OpKind {
    /** Move a user file/folder into the 30-day trash. */
    TRASH,

    /** Permanently delete something already in the trash (purge / Empty now). */
    PURGE,

    /** Put a trashed item back. */
    RESTORE,

    /** Trash a MOVE original after L3 verification (requires [PlannedOp.expectedSha256]). */
    MOVE_DELETE_ORIGINAL,

    /**
     * Put back a trashed item that changed since it was trashed, only into a separate folder
     * (`KeepClear/Restored-changed/`) after an explicit confirmation; never over anything (owner decision 1.2).
     */
    RESTORE_CHANGED,
}

/**
 * One operation as the user saw it in the confirmation. [displayPath] is the absolute path shown and
 * used for PROTECTED matching (required even for SAF/MediaStore refs, which have opaque ids).
 */
data class PlannedOp(
    val ref: KPath,
    val displayPath: String,
    val kind: OpKind,
    val isDirectory: Boolean,
    val expectedSize: Long,
    val expectedMtime: Long,
    val expectedIdentity: String? = null,
    val expectedSha256: String? = null,
    /** Duplicate group id when this op deletes one copy of a duplicate (SAFETY-8). */
    val duplicateGroup: String? = null,
    /** Classification rule, recorded in the trash entry for "why was this deleted". */
    val ruleId: String? = null,
    /** Verified backup copy for MOVE originals (TRASH-5). */
    val verifiedCopy: String? = null,
    /**
     * The copies that make this op safe (H2): the other copy of a duplicate, or every extracted file of an
     * archive. The executor re-stats each right before acting and skips the op if any is gone or changed.
     */
    val witnesses: List<Witness> = emptyList(),
    /**
     * The user confirmed "this is the last copy" for this item (owner decision 1.3): another trashed item was
     * deleted because this one existed, so deleting it too leaves no copy outside the trash.
     */
    val lastCopyConfirmed: Boolean = false,
)

/** A file that must still be there, unchanged in size, for an op to proceed (H2, SAFETY-8). */
data class Witness(val ref: KPath, val path: String, val size: Long)

/**
 * What the user confirmed (design.md §3.3). The confirmation UI renders from the plan; [confirm]
 * stamps it. A plan is only valid in the process [sessionId] it was confirmed in (SAFETY-1, MIG-5).
 */
data class ActionPlan(
    val id: String,
    val ops: List<PlannedOp>,
    val reason: String,
    /** Number of copies that exist on the device for each duplicate group referenced by [ops]. */
    val duplicateGroupSizes: Map<String, Int> = emptyMap(),
    val confirmedAt: Long? = null,
    val sessionId: String? = null,
) {
    val totalBytes: Long get() = ops.sumOf { it.expectedSize }

    fun confirm(now: Long, session: String): ActionPlan = copy(confirmedAt = now, sessionId = session)
}
