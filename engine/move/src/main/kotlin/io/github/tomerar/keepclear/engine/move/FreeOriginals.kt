// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.move

import io.github.tomerar.keepclear.core.safety.ActionExecutor
import io.github.tomerar.keepclear.core.safety.ExecutionReport
import io.github.tomerar.keepclear.core.safety.OpKind
import io.github.tomerar.keepclear.core.safety.OpResult
import io.github.tomerar.keepclear.core.safety.PlannedOp
import io.github.tomerar.keepclear.core.safety.SkipReason
import io.github.tomerar.keepclear.core.storage.StorageException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.text.Normalizer
import java.util.Locale

/** Why an original was not freed (owner decision P1). Shown as counts in the result. */
enum class FreeSkip {
    /** No second check (any more) for this copy, or not a verified file of this job. */
    NOT_CONFIRMED,

    /** The copy is no longer on the target. */
    COPY_MISSING,

    /** The copy on the target no longer hashes to the original's SHA-256 (or has another size). */
    COPY_DIFFERS,

    /** The original is gone already. */
    ORIGINAL_MISSING,

    /** The original changed since it was copied (size, date, identity or content). */
    ORIGINAL_CHANGED,

    /** PROTECTED, or kept by the user while the free ran. */
    PROTECTED,

    /** Any other reason the executor skipped it. */
    OTHER,
}

/** One batch of [MoveEngine.freeOps]: the ops to confirm and run, and what was left out and why. */
data class FreeOps(
    val ops: List<PlannedOp>,
    val skipped: Map<FreeSkip, Int> = emptyMap(),
    /** The target went away or access was lost; [notReached] files of the batch were not looked at. */
    val interrupted: Boolean = false,
    val notReached: Int = 0,
)

/** What [FreeOriginals.free] did. Counts are files; sizes are the originals' sizes. */
data class FreeReport(
    /** Files the user confirmed. */
    val total: Int,
    /** Deleted for good from the phone (owner decision P1). */
    val deletedForGood: List<PlannedOp> = emptyList(),
    /** Moved to KeepClear's trash (the user's "keep the original in the trash too" setting). */
    val trashed: List<PlannedOp> = emptyList(),
    /** Test mode (SAFETY-9): would have been freed; nothing changed. */
    val simulated: List<PlannedOp> = emptyList(),
    val skipped: Map<FreeSkip, Int> = emptyMap(),
    val failed: Int = 0,
    /** The target went away, access was lost, or an error stopped the run; the rest was not touched. */
    val interrupted: Boolean = false,
    /** The plan was refused as a whole (no confirmation in this session, an op the user didn't see). */
    val rejected: Boolean = false,
) {
    val skippedCount: Int get() = skipped.values.sum()
}

/** Progress of a free: [processed] of [total] files looked at, [freed] of them freed (or would be, in test mode). */
data class FreeProgress(val processed: Int, val freed: Int, val total: Int)

/**
 * Owner decision P1 (2026-11-03): frees the originals of a verified backup **in batches**. For each batch the engine
 * first reads every copy back from the target and hashes it again ([MoveEngine.freeOps]), then the batch goes through
 * the one choke point ([run]: the app confirms it with the user's tap and hands it to `ActionExecutor`), which
 * re-checks each original (size, date, identity, strict re-hash) right before deleting it. So the copy check is at most
 * one batch old when an original is deleted. A file that fails any check is skipped and counted, never deleted. The
 * target stays open for the whole free; if it goes away, the free stops and says so.
 */
class FreeOriginals(
    private val engine: MoveEngine,
    /** Confirms [ops] with the user's tap and runs them through the executor, reporting each result. */
    private val run: suspend (ops: List<PlannedOp>, onResult: (OpResult) -> Unit) -> ExecutionReport,
    private val batchFiles: Int = BATCH_FILES,
    private val batchBytes: Long = BATCH_BYTES,
) {
    suspend fun free(jobId: String, chosen: Set<String>, onProgress: (FreeProgress) -> Unit = {}): FreeReport {
        val candidates = engine.freeCandidates(jobId).filter { it.sourcePath in chosen }
        val total = chosen.size
        val skipped = mutableMapOf<FreeSkip, Int>()

        fun skip(why: FreeSkip, n: Int = 1) {
            if (n > 0) skipped.merge(why, n, Int::plus)
        }
        // Chosen on the receipt but no longer verified twice (copied again, a check found it damaged): left alone.
        skip(FreeSkip.NOT_CONFIRMED, total - candidates.size)
        val forGood = mutableListOf<PlannedOp>()
        val trashed = mutableListOf<PlannedOp>()
        val simulated = mutableListOf<PlannedOp>()
        var failed = 0
        var processed = total - candidates.size

        fun progress() = onProgress(FreeProgress(processed, forGood.size + trashed.size + simulated.size, total))
        progress()
        for (batch in batches(candidates)) {
            currentCoroutineContext().ensureActive()
            val planned = engine.freeOps(jobId, batch)
            planned.skipped.forEach { (why, n) -> skip(why, n) }
            processed += batch.size - planned.ops.size - planned.notReached
            progress()
            // The target went away (or paused) while this batch's copies were being checked: nothing of this batch
            // is deleted, not even the files whose copies were checked before that (SAFETY-5, E-MOVE-26).
            if (planned.interrupted) {
                return FreeReport(total, forGood, trashed, simulated, skipped, failed, interrupted = true)
            }
            var stopped = false
            if (planned.ops.isNotEmpty()) {
                val report = run(planned.ops) { result ->
                    processed++
                    when (result) {
                        is OpResult.Done -> {
                            if (result.op.kind == OpKind.DELETE_FOR_GOOD) {
                                forGood += result.op
                            } else {
                                trashed += result.op
                            }
                        }

                        is OpResult.Simulated -> {
                            simulated += result.op
                        }

                        is OpResult.Skipped -> {
                            if (result.reason == SkipReason.STOPPED) stopped = true
                            skip(skipOf(result.reason))
                        }

                        is OpResult.Failed -> {
                            failed++
                            if (stops(result.error)) stopped = true
                        }

                        // Freeing originals never asks Android (their refs are paths); counted as not done.
                        is OpResult.NeedsConsent -> {
                            skip(FreeSkip.OTHER)
                        }
                    }
                    progress()
                }
                if (report is ExecutionReport.Rejected) {
                    return FreeReport(total, forGood, trashed, simulated, skipped, failed, rejected = true)
                }
            }
            if (stopped) return FreeReport(total, forGood, trashed, simulated, skipped, failed, interrupted = true)
        }
        return FreeReport(total, forGood, trashed, simulated, skipped, failed)
    }

    private fun stops(e: StorageException) = e is StorageException.PermissionDenied ||
        e is StorageException.Disconnected || e.message?.startsWith(ActionExecutor.UNEXPECTED) == true

    /** At most [batchFiles] files and about [batchBytes] per batch (one file larger than that is a batch alone). */
    private fun batches(files: List<MoveFile>): List<List<MoveFile>> {
        val out = mutableListOf<List<MoveFile>>()
        var current = mutableListOf<MoveFile>()
        var bytes = 0L
        for (f in files) {
            if (current.isNotEmpty() && (current.size >= batchFiles || bytes + f.sourceSize > batchBytes)) {
                out += current
                current = mutableListOf()
                bytes = 0
            }
            current += f
            bytes += f.sourceSize
        }
        if (current.isNotEmpty()) out += current
        return out
    }

    companion object {
        const val BATCH_FILES = 200
        const val BATCH_BYTES = 2_000_000_000L

        fun skipOf(reason: SkipReason): FreeSkip = when (reason) {
            SkipReason.MISSING -> {
                FreeSkip.ORIGINAL_MISSING
            }

            SkipReason.CHANGED, SkipReason.IDENTITY_CHANGED, SkipReason.HASH_MISMATCH, SkipReason.SYMLINK -> {
                FreeSkip.ORIGINAL_CHANGED
            }

            SkipReason.PROTECTED, SkipReason.CONTAINS_PROTECTED -> {
                FreeSkip.PROTECTED
            }

            SkipReason.COPY_NOT_CONFIRMED -> {
                FreeSkip.NOT_CONFIRMED
            }

            SkipReason.STOPPED, SkipReason.WITNESS_GONE, SkipReason.LAST_COPY, SkipReason.UNSUPPORTED -> {
                FreeSkip.OTHER
            }
        }
    }
}

/** One original the receipt offers to free, as the free step's options see it (owner decisions P2, P4). */
data class FreeCandidate(
    val path: String,
    val size: Long,
    /** Modification time of the original (the "last 30 days" option). */
    val mtime: Long,
    /** True/false from the latest scan; null when the scan doesn't know this file (then it counts as a favourite). */
    val favourite: Boolean?,
)

/** The free step's two options, both ticked by default (owner decision P2): they **keep** files on the phone. */
data class FreeOptions(val keepFavourites: Boolean = true, val keepRecent: Boolean = true)

/**
 * Owner decisions P2 and P4 (2026-11-03): what the free step will free with [FreeOptions]. Favourites and the last
 * [RECENT_DAYS] days are backed up like everything else, but kept on the phone while their option is ticked (the
 * Google Photos / Immich pattern). A file the scan doesn't know counts as a favourite (decided conservatively, C119).
 * [hasWhatsApp]: the plan frees WhatsApp media, so the confirmation says how WhatsApp will show them.
 */
data class FreePlan(
    val paths: Set<String>,
    val bytes: Long,
    val keptFavourites: Int,
    val keptRecent: Int,
    val hasWhatsApp: Boolean,
) {
    val count: Int get() = paths.size

    companion object {
        const val RECENT_DAYS = 30
        private const val DAY = 86_400_000L

        fun of(candidates: List<FreeCandidate>, options: FreeOptions, now: Long): FreePlan {
            val chosen = mutableListOf<FreeCandidate>()
            var favourites = 0
            var recent = 0
            for (c in candidates) {
                val favourite = c.favourite != false
                val isRecent = now - c.mtime < RECENT_DAYS * DAY
                when {
                    options.keepFavourites && favourite -> favourites++
                    options.keepRecent && isRecent -> recent++
                    else -> chosen += c
                }
            }
            return FreePlan(
                paths = chosen.map { it.path }.toSet(),
                bytes = chosen.sumOf { it.size },
                keptFavourites = favourites,
                keptRecent = recent,
                hasWhatsApp = chosen.any { isWhatsAppMedia(it.path) },
            )
        }

        /**
         * Owner decision P4: a file inside a WhatsApp or WhatsApp Business folder (scoped `Android/media/com.whatsapp…`
         * or the old `/WhatsApp/` root). Over-matching only adds a sentence to the confirmation.
         */
        fun isWhatsAppMedia(path: String): Boolean =
            Normalizer.normalize(path, Normalizer.Form.NFC).lowercase(Locale.ROOT).split('/').any {
                it == "whatsapp" || it == "whatsapp business" || it == "com.whatsapp" || it == "com.whatsapp.w4b"
            }
    }
}
