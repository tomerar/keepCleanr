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
)

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
