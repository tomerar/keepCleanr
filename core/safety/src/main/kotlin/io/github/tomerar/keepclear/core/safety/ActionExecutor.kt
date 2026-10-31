// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.safety

import io.github.tomerar.keepclear.core.hash.Sha256
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.core.storage.Stat
import io.github.tomerar.keepclear.core.storage.StorageException
import io.github.tomerar.keepclear.core.storage.StorageGateway
import kotlinx.coroutines.flow.toList

/**
 * Performs the actual change for one op. Implemented by the trash module (M6); the executor is the
 * only caller (CLAUDE.md safety rule 3).
 */
fun interface ActionHandler {
    suspend fun perform(op: PlannedOp, gateway: StorageGateway)
}

/** Outcome per op (E-DEL-11: partial success is normal and reported). */
sealed interface OpResult {
    val op: PlannedOp

    data class Done(override val op: PlannedOp) : OpResult

    /** Dry-run: would have been performed (SAFETY-9). */
    data class Simulated(override val op: PlannedOp) : OpResult

    data class Skipped(override val op: PlannedOp, val reason: SkipReason) : OpResult

    data class Failed(override val op: PlannedOp, val error: StorageException) : OpResult
}

enum class SkipReason {
    MISSING,
    CHANGED,
    IDENTITY_CHANGED,
    SYMLINK,
    CONTAINS_PROTECTED,
    HASH_MISMATCH,

    /** Access was lost earlier in this run (permission revoked, volume gone): not attempted (E-PERM-2). */
    STOPPED,
}

sealed interface ExecutionReport {
    data class Rejected(val reasons: List<String>) : ExecutionReport

    data class Ran(val results: List<OpResult>) : ExecutionReport {
        val done get() = results.filterIsInstance<OpResult.Done>()
        val skipped get() = results.filterIsInstance<OpResult.Skipped>()
        val failed get() = results.filterIsInstance<OpResult.Failed>()
    }
}

/**
 * The single choke point for destructive actions (design.md §3.3, SAFETY-1…9, SEC-4).
 *
 * For each op, in order: re-stat without following links → compare size/mtime/identity → for
 * directories, walk the subtree and refuse if anything inside is PROTECTED → for MOVE originals,
 * re-hash (strict mode) → perform via [handler] (or simulate in [dryRun]).
 * Storage errors become [OpResult.Failed]; they never crash the run (LOW-4).
 */
class ActionExecutor(
    private val gateway: StorageGateway,
    private val guard: SafetyGuard,
    private val protectedPaths: ProtectedPaths,
    private val handler: ActionHandler,
    private val currentSession: String,
    private val dryRun: Boolean = false,
    /** Strict mode (SEC-4, owner decision Q13): re-hash MOVE originals right before trashing. */
    private val strictRehash: Boolean = true,
    private val onResult: (OpResult) -> Unit = {},
) {
    suspend fun run(plan: ActionPlan): ExecutionReport {
        when (val verdict = guard.check(plan, currentSession)) {
            is SafetyGuard.Verdict.Rejected -> return ExecutionReport.Rejected(verdict.reasons)
            SafetyGuard.Verdict.Accepted -> Unit
        }
        val results = mutableListOf<OpResult>()
        var stopped = false
        for (op in plan.ops) {
            val result = if (stopped) OpResult.Skipped(op, SkipReason.STOPPED) else runOne(op)
            if (result is OpResult.Failed &&
                (result.error is StorageException.PermissionDenied || result.error is StorageException.Disconnected)
            ) {
                stopped = true
            }
            results += result
            onResult(result)
        }
        return ExecutionReport.Ran(results)
    }

    private suspend fun runOne(op: PlannedOp): OpResult = try {
        precheck(op) ?: run {
            if (dryRun) {
                OpResult.Simulated(op)
            } else {
                handler.perform(op, gateway)
                OpResult.Done(op)
            }
        }
    } catch (e: StorageException) {
        OpResult.Failed(op, e)
    }

    /** Returns a skip result, or null when the op may proceed. */
    private suspend fun precheck(op: PlannedOp): OpResult? {
        if (op.kind == OpKind.RESTORE) return null // restore validation lives in the trash module (SEC-1)
        val stat = gateway.stat(op.ref) ?: return OpResult.Skipped(op, SkipReason.MISSING)
        if (stat.isSymlink) return OpResult.Skipped(op, SkipReason.SYMLINK)
        if (stat.isDirectory != op.isDirectory) return OpResult.Skipped(op, SkipReason.CHANGED)
        if (op.expectedIdentity != null && stat.identity != null && stat.identity != op.expectedIdentity) {
            return OpResult.Skipped(op, SkipReason.IDENTITY_CHANGED)
        }
        // Directories: a changed mtime means entries were added or removed since confirmation (SAFETY-6).
        if (stat.mtimeMillis != op.expectedMtime || (!stat.isDirectory && stat.size != op.expectedSize)) {
            return OpResult.Skipped(op, SkipReason.CHANGED)
        }
        if (stat.isDirectory && op.kind != OpKind.PURGE && subtreeHasProtected(stat)) {
            return OpResult.Skipped(op, SkipReason.CONTAINS_PROTECTED)
        }
        if (op.kind == OpKind.MOVE_DELETE_ORIGINAL && strictRehash) {
            val actual = gateway.openRead(op.ref).use { Sha256.of(it) }
            if (actual != op.expectedSha256) return OpResult.Skipped(op, SkipReason.HASH_MISMATCH)
        }
        return null
    }

    private suspend fun subtreeHasProtected(dir: Stat): Boolean {
        val stack = ArrayDeque<Stat>().apply { add(dir) }
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            for (child in gateway.list(current.path).toList()) {
                val display = (child.path as? KPath.Local)?.absolute ?: return true // can't check → refuse
                if (protectedPaths.isProtected(display)) return true
                if (child.isSymlink) return true // never move a tree that contains links
                if (child.isDirectory) stack += child
            }
        }
        return false
    }
}
