// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.safety

import io.github.tomerar.keepclear.core.hash.Sha256

/**
 * Plan-level checks before anything runs (design.md §3.3). A **structural** problem rejects the whole plan: not
 * confirmed, confirmed in another session, an op the user didn't see, a ref that differs from the path shown, a
 * purge outside the trash, a MOVE original without a verified SHA-256, a duplicate group that would lose its last
 * copy. A PROTECTED item (SAFETY-3) is refused per item instead ([skipReason], D5): the executor skips it with
 * [SkipReason.PROTECTED] and the result says so, while the rest of the plan still runs.
 */
class SafetyGuard(
    private val protectedPaths: ProtectedPaths,
    /**
     * M12b: folders the user granted (e.g. WhatsApp's folder), each with its own trash directly inside it
     * (`<folder>/.KeepClearTrash`). Only these exact folders count; a `.KeepClearTrash` anywhere else is not the
     * trash.
     */
    private val grantedTrashRoots: Collection<String> = emptyList(),
) {
    sealed interface Verdict {
        data object Accepted : Verdict

        data class Rejected(val reasons: List<String>) : Verdict
    }

    fun check(plan: ActionPlan, currentSession: String): Verdict {
        val reasons = mutableListOf<String>()
        if (plan.confirmedAt == null) reasons += "Plan was not confirmed by the user (SAFETY-1)"
        if (plan.sessionId != currentSession) reasons += "Plan was confirmed in another session (SAFETY-1, MIG-5)"
        if (plan.ops.isEmpty()) reasons += "Plan is empty"

        val shown = plan.confirmedPaths
        if (plan.confirmedAt != null && shown == null) reasons += "Plan has no confirmation of what was shown (PR B)"
        for (op in plan.ops) {
            // Review PR B: every op must be one the user saw in the confirmation, and a path ref must be the path
            // that was shown (no "show A, delete B").
            if (shown != null && op.confirmKey !in shown) {
                reasons += "Op not in the user's confirmation: ${op.displayPath}"
            }
            val ref = op.ref
            if (ref is io.github.tomerar.keepclear.core.storage.KPath.Local && ref.absolute != op.displayPath) {
                reasons += "Op target differs from what is shown: ${op.displayPath}"
            }
            if (op.kind == OpKind.PURGE && op.ref !is io.github.tomerar.keepclear.core.storage.KPath.Media &&
                !isInsideTrash(op.displayPath)
            ) {
                // A MediaStore row may be purged only while it is in Android's trash; the media handler checks that
                // right before acting (M12). Anything else must be inside KeepClear's own trash.
                reasons += "PURGE outside the trash (SAFETY-2): ${op.displayPath}"
            }
            // Owner decision 2026-11-02: only the executor decides that an item is deleted for good, per item, from
            // its classification and path (PermanentKinds). A plan can't ask for it.
            if (op.kind == OpKind.DELETE_FOR_GOOD || op.permanentReason != null) {
                reasons += "Permanent delete requested by the plan, not decided by the executor: ${op.displayPath}"
            }
            // Owner decision P1: a copy check belongs only to a MOVE original, and only to the same content.
            val proof = op.backupProof
            if (proof != null && (op.kind != OpKind.MOVE_DELETE_ORIGINAL || proof.copySha256 != op.expectedSha256)) {
                reasons += "Backup proof on an op it doesn't belong to: ${op.displayPath}"
            }
            if (op.kind == OpKind.MOVE_DELETE_ORIGINAL) {
                val sha = op.expectedSha256
                if (sha == null || !Sha256.isValidHex(sha)) {
                    reasons += "MOVE original without a verified SHA-256 (SAFETY-5): ${op.displayPath}"
                }
            }
        }

        val dupCounts = plan.ops.mapNotNull { it.duplicateGroup }.groupingBy { it }.eachCount()
        for ((group, selected) in dupCounts) {
            val size = plan.duplicateGroupSizes[group]
            if (size == null || selected >= size) {
                reasons += "Duplicate group $group would lose its last copy (SAFETY-8)"
            }
        }
        return if (reasons.isEmpty()) Verdict.Accepted else Verdict.Rejected(reasons)
    }

    /**
     * D5: [SkipReason.PROTECTED] when [op] would change a PROTECTED path (SAFETY-3) or act on a folder that holds
     * protected things (a messenger root), else null. Ops on items already in the trash are exempt (they are
     * contained by the trash checks in [check]). The executor asks this before anything else for every op.
     */
    fun skipReason(op: PlannedOp): SkipReason? {
        val trashOnly = op.kind == OpKind.PURGE || op.kind == OpKind.RESTORE || op.kind == OpKind.RESTORE_CHANGED
        if (trashOnly) return null
        if (protectedPaths.isProtected(op.displayPath)) return SkipReason.PROTECTED
        if (op.shownAs?.let(protectedPaths::isProtected) == true) return SkipReason.PROTECTED
        if (op.isDirectory && protectedPaths.isProtectedContainer(op.displayPath)) return SkipReason.PROTECTED
        return null
    }

    /**
     * Allowlist check for permanent deletion. Canonical, not lexical: the trash folder must sit directly at a
     * volume root (`/storage/emulated/<user>/` or `/storage/<uuid>/`), so a user folder such as
     * `DCIM/.KeepClearTrash` is not the trash; the raw segment must also match exactly (no lossy normalisation,
     * so `.KeepClear\u200BTrash` is not the trash), and the target must be strictly inside it.
     * Symlinked folders inside the trash are refused by `TrashManager` on the real file system.
     */
    private fun isInsideTrash(rawPath: String): Boolean = isInsideVolumeTrash(rawPath) || isInsideGrantedTrash(rawPath)

    /** Strictly inside `<granted folder>/.KeepClearTrash/`, for an explicitly granted folder only (M12b). */
    private fun isInsideGrantedTrash(rawPath: String): Boolean {
        val canonical = CanonicalPath.of(rawPath) ?: return false
        val raw = rawPath.split('/').filter { it.isNotEmpty() }
        if (raw.size != canonical.split('/').drop(1).size) return false
        return grantedTrashRoots.any { root ->
            val rootCanonical = CanonicalPath.of(root) ?: return@any false
            val prefix = "$rootCanonical/${ProtectedPaths.TRASH_DIR}/"
            val index = rootCanonical.split('/').drop(1).size
            canonical.startsWith(prefix) && canonical.length > prefix.length &&
                raw[index].equals(ProtectedPaths.TRASH_FOLDER_NAME, ignoreCase = true)
        }
    }

    private fun isInsideVolumeTrash(rawPath: String): Boolean {
        val canonical = CanonicalPath.of(rawPath) ?: return false
        val segs = canonical.split('/').drop(1)
        val trashIndex = when {
            segs.size > 2 && segs[0] == "storage" && segs[1] == "emulated" -> 3
            segs.size > 1 && segs[0] == "storage" && segs[1] != "emulated" && segs[1] != "self" -> 2
            else -> return false
        }
        if (segs.size <= trashIndex + 1) return false // the trash folder itself, or above it
        if (segs[trashIndex] != ProtectedPaths.TRASH_FOLDER_NAME.lowercase()) return false
        val raw = rawPath.split('/').filter { it.isNotEmpty() }
        return raw.size == segs.size && raw[trashIndex].equals(ProtectedPaths.TRASH_FOLDER_NAME, ignoreCase = true)
    }
}
