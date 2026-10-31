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

        for (op in plan.ops) {
            val trashOnly = op.kind == OpKind.PURGE || op.kind == OpKind.RESTORE
            if (!trashOnly) {
                if (protectedPaths.isProtected(op.displayPath)) {
                    reasons += "PROTECTED path in plan (SAFETY-3): ${op.displayPath}"
                } else if (op.isDirectory && protectedPaths.isProtectedContainer(op.displayPath)) {
                    reasons += "Directory op on a protected container (SAFETY-3): ${op.displayPath}"
                }
            } else if (CanonicalPath.of(op.displayPath)?.split('/')?.contains(ProtectedPaths.TRASH_DIR) != true &&
                op.kind == OpKind.PURGE
            ) {
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
}
