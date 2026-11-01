// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.safety

import io.github.tomerar.keepclear.core.hash.Sha256

/**
 * Plan-level checks before anything runs (design.md §3.3). Any violation rejects the **whole plan**:
 * a plan that reaches the guard with a PROTECTED op is a bug, never a user choice (SAFETY-3).
 */
class SafetyGuard(private val protectedPaths: ProtectedPaths) {
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
<<<<<<< HEAD
            // Review PR B: every op must be one the user saw in the confirmation, and a path ref must be the path
            // that was shown (no "show A, delete B").
            if (shown != null && op.confirmKey !in shown) {
                reasons += "Op not in the user's confirmation: ${op.displayPath}"
            }
            val ref = op.ref
            if (ref is io.github.tomerar.keepclear.core.storage.KPath.Local && ref.absolute != op.displayPath) {
                reasons += "Op target differs from what is shown: ${op.displayPath}"
            }
=======
>>>>>>> origin/main
            val trashOnly = op.kind == OpKind.PURGE || op.kind == OpKind.RESTORE || op.kind == OpKind.RESTORE_CHANGED
            if (!trashOnly) {
                if (protectedPaths.isProtected(op.displayPath)) {
                    reasons += "PROTECTED path in plan (SAFETY-3): ${op.displayPath}"
                } else if (op.isDirectory && protectedPaths.isProtectedContainer(op.displayPath)) {
                    reasons += "Directory op on a protected container (SAFETY-3): ${op.displayPath}"
                }
            } else if (op.kind == OpKind.PURGE && !isInsideTrash(op.displayPath)) {
                reasons += "PURGE outside the trash (SAFETY-2): ${op.displayPath}"
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
     * Allowlist check for permanent deletion. Canonical, not lexical: the trash folder must sit directly at a
     * volume root (`/storage/emulated/<user>/` or `/storage/<uuid>/`), so a user folder such as
     * `DCIM/.KeepClearTrash` is not the trash; the raw segment must also match exactly (no lossy normalisation,
     * so `.KeepClear\u200BTrash` is not the trash), and the target must be strictly inside it.
     * Symlinked folders inside the trash are refused by `TrashManager` on the real file system.
     */
    private fun isInsideTrash(rawPath: String): Boolean {
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
