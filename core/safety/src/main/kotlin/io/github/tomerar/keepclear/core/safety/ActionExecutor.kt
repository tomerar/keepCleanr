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

    /** Handler-specific reason to skip [op] before anything changes (e.g. the trash's last-copy check). */
    suspend fun veto(op: PlannedOp, gateway: StorageGateway): SkipReason? = null

    /**
     * M12: true when Android itself must ask the user before [op] happens (MediaStore trash without
     * `MANAGE_MEDIA`). Such ops are re-checked like any other and then handed over in one batch.
     */
    fun needsConsent(op: PlannedOp): Boolean = false

    /**
     * Splits [ops] into the requests Android will show, one dialog each. The UI builds and launches them one at a
     * time (Android cancels an earlier pending request of the same kind when a new one is created).
     */
    suspend fun requestConsent(ops: List<PlannedOp>): List<ConsentRequest> = emptyList()
}

/** One dialog Android shows (M12); [ops] are exactly what it covers, so only approved requests count as done. */
interface ConsentRequest {
    val ops: List<PlannedOp>
}

/** Outcome per op (E-DEL-11: partial success is normal and reported). */
sealed interface OpResult {
    val op: PlannedOp

    data class Done(override val op: PlannedOp) : OpResult

    /** Dry-run: would have been performed (SAFETY-9). */
    data class Simulated(override val op: PlannedOp) : OpResult

    data class Skipped(override val op: PlannedOp, val reason: SkipReason) : OpResult

    data class Failed(override val op: PlannedOp, val error: StorageException) : OpResult

    /** Re-checked and handed to Android, which asks the user (M12); done only if the user agrees there. */
    data class NeedsConsent(override val op: PlannedOp) : OpResult
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

    /** The other copy this item was redundant with is gone or changed (H2, SAFETY-8). */
    WITNESS_GONE,

    /**
     * A copy of this file is already in the trash because this file existed; deleting it too needs the user's
     * explicit "this is the last copy" confirmation (owner decision 1.3, SAFETY-8).
     */
    LAST_COPY,

    /** The storage can't do this for this item (e.g. a MediaStore row Android's trash doesn't accept). */
    UNSUPPORTED,
}

sealed interface ExecutionReport {
    data class Rejected(val reasons: List<String>) : ExecutionReport

    data class Ran(
        val results: List<OpResult>,
        /** Android's consent requests for the [OpResult.NeedsConsent] ops, for the UI to launch (M12). */
        val consent: List<ConsentRequest> = emptyList(),
    ) : ExecutionReport {
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
    /**
     * Asked again right before **each** operation (SAFETY-9), so switching test mode on while a long plan runs
     * (e.g. strict re-hashes before freeing originals) stops every remaining operation from being performed.
     */
    private val isDryRun: () -> Boolean = { dryRun },
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
        val needConsent = results.filterIsInstance<OpResult.NeedsConsent>().map { it.op }
        if (needConsent.isEmpty()) return ExecutionReport.Ran(results)
        return try {
            ExecutionReport.Ran(results, handler.requestConsent(needConsent))
        } catch (e: StorageException) {
            ExecutionReport.Ran(results.map { if (it is OpResult.NeedsConsent) OpResult.Failed(it.op, e) else it })
        }
    }

    private suspend fun runOne(op: PlannedOp): OpResult = try {
        precheck(op) ?: handler.veto(op, gateway)?.let { OpResult.Skipped(op, it) } ?: run {
            if (isDryRun()) {
                OpResult.Simulated(op)
            } else if (handler.needsConsent(op)) {
                OpResult.NeedsConsent(op)
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
        // Restore validation lives in the trash module (SEC-1).
        if (op.kind == OpKind.RESTORE || op.kind == OpKind.RESTORE_CHANGED) return null
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
        for (w in op.witnesses) {
            val ws = gateway.stat(w.ref)
            if (ws == null || !ws.isFile || ws.size != w.size || ws.path == stat.path) {
                return OpResult.Skipped(op, SkipReason.WITNESS_GONE)
            }
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
