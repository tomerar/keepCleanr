// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.move

import io.github.tomerar.keepclear.backup.BackupTarget
import io.github.tomerar.keepclear.core.hash.Sha256
import io.github.tomerar.keepclear.core.safety.OpKind
import io.github.tomerar.keepclear.core.safety.PlannedOp
import io.github.tomerar.keepclear.core.storage.StorageException
import io.github.tomerar.keepclear.core.storage.StorageGateway
import io.github.tomerar.keepclear.core.storage.Streams.readUpTo
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Copy → hash → place → read back → compare (ADR 0007, MOVE-6/8/11). **Never deletes or changes a
 * source file**: deleting originals is a separate, user-confirmed `ActionPlan` built by
 * [originalsPlan] and run through the executor (strict re-hash) into the trash.
 *
 * Every state transition is checkpointed to [store] so a killed job resumes at the first unverified
 * file (MOVE-11); temp files from an interrupted copy are deleted on resume (E-MOVE-1).
 */
class MoveEngine(
    private val source: StorageGateway,
    private val target: BackupTarget,
    private val store: MoveJobStore,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Files changed less than this long ago are copied last (E-MOVE-6: WhatsApp still writing). */
    private val recentMillis: Long = 2 * 60_000L,
    private val maxAttempts: Int = 3,
    private val onProgress: (MoveFile) -> Unit = {},
    /** Why the job stopped when its coroutine is cancelled from outside. */
    private val cancelReason: () -> PauseReason = { PauseReason.PAUSED_BY_USER },
) {
    suspend fun run(jobId: String): MoveJob {
        var job = store.job(jobId) ?: error("unknown job $jobId")
        job = job.copy(state = JobState.RUNNING, pauseReason = null).also { store.putJob(it) }
        val now = clock()
        val (recent, normal) = store.files(jobId).filter {
            it.state != MoveFileState.VERIFIED &&
                it.state != MoveFileState.FAILED &&
                it.state != MoveFileState.COPIED_UNVERIFIED
        }
            .partition { now - it.sourceMtime in 0 until recentMillis }
        try {
            for (file in normal + recent) {
                currentCoroutineContext().ensureActive()
                process(file)
            }
            writeManifest(job)
        } catch (e: Pause) {
            return job.copy(state = JobState.PAUSED, pauseReason = e.reason).also { store.putJob(it) }
        } catch (e: CancellationException) {
            // Paused from outside (user, system time limit). The current temp file is removed on resume (E-MOVE-1).
            withContext(NonCancellable) {
                store.putJob(job.copy(state = JobState.PAUSED, pauseReason = cancelReason()))
            }
            throw e
        }
        val unfinished = store.files(jobId).any {
            it.state !in setOf(MoveFileState.VERIFIED, MoveFileState.FAILED, MoveFileState.COPIED_UNVERIFIED)
        }
        return job.copy(state = if (unfinished) JobState.READY else JobState.DONE).also { store.putJob(it) }
    }

    private class Pause(val reason: PauseReason) : Exception()

    private suspend fun put(file: MoveFile): MoveFile = file.also {
        store.putFile(it)
        onProgress(it)
    }

    private suspend fun process(initial: MoveFile) {
        var file = initial
        while (true) {
            file = when (file.state) {
                MoveFileState.PENDING, MoveFileState.COPYING, MoveFileState.SRC_CHANGED -> copy(file)
                MoveFileState.COPIED -> place(file)
                MoveFileState.PLACED -> verify(file)
                MoveFileState.VERIFIED, MoveFileState.FAILED, MoveFileState.COPIED_UNVERIFIED -> return
            }
            if (file.state == MoveFileState.SRC_CHANGED && file.attempts >= maxAttempts) return
        }
    }

    private suspend fun copy(start: MoveFile): MoveFile {
        var file = put(start.copy(state = MoveFileState.COPYING))
        val dir = file.relTarget.substringBeforeLast('/')
        guarded { target.mkdirs(dir) }
        guarded { if (target.stat(file.tempRel) != null) target.delete(file.tempRel) } // E-MOVE-1
        val before = guarded { source.stat(file.source) }
            ?: return put(file.copy(state = MoveFileState.FAILED, failReason = FailReason.SOURCE_MISSING))
        target.caps.maxFileSize?.let { max ->
            if (before.size >
                max
            ) {
                return put(file.copy(state = MoveFileState.FAILED, failReason = FailReason.TOO_LARGE_FOR_TARGET))
            }
        }
        file = file.copy(sourceSize = before.size, sourceMtime = before.mtimeMillis, sourceIdentity = before.identity)
        val (bytes, sha) = try {
            source.openRead(file.source).use { input ->
                guarded { target.create(file.tempRel) }.use { output -> Sha256.copy(input, output) }
            }
        } catch (e: Pause) {
            runCatching { target.delete(file.tempRel) }
            throw e
        } catch (e: StorageException) {
            val pause = pauseFor(e)
            runCatching { target.delete(file.tempRel) }
            if (pause != null) throw Pause(pause)
            val reason = if (isSourceError(e, file)) FailReason.SOURCE_UNREADABLE else FailReason.TARGET_ERROR
            return put(file.copy(state = MoveFileState.FAILED, failReason = reason))
        }
        val after = guarded { source.stat(file.source) }
        if (after == null || after.size != before.size || after.mtimeMillis != before.mtimeMillis ||
            bytes != before.size
        ) {
            runCatching { target.delete(file.tempRel) }
            return put(file.copy(state = MoveFileState.SRC_CHANGED, attempts = file.attempts + 1))
        }
        return put(file.copy(state = MoveFileState.COPIED, sourceSha256 = sha))
    }

    private suspend fun place(start: MoveFile): MoveFile {
        var file = start
        val existing = guarded { target.stat(file.relTarget) }
        if (guarded { target.stat(file.tempRel) } == null) {
            // Killed between the rename and the PLACED checkpoint: the copy is already at relTarget (the chosen
            // name is checkpointed before renaming). Verify it, but never delete it, in case it isn't ours.
            return if (existing != null && existing.size == file.sourceSize) {
                put(file.copy(state = MoveFileState.PLACED, reusedExisting = true))
            } else {
                put(file.copy(state = MoveFileState.PENDING))
            }
        }
        if (existing != null) {
            // E-MOVE-9/10: identical content already there → reuse; otherwise never overwrite, pick a free name.
            if (existing.size == file.sourceSize && readBackHash(file.relTarget) == file.sourceSha256) {
                runCatching { target.delete(file.tempRel) }
                return put(file.copy(state = MoveFileState.PLACED, reusedExisting = true))
            }
            // Checkpoint the new name before renaming, so a crash right after the rename can be resumed.
            file = put(file.copy(relTarget = freeName(file.relTarget), nameChanged = true))
        }
        guarded { target.rename(file.tempRel, file.relTarget) }
        return put(file.copy(state = MoveFileState.PLACED))
    }

    private suspend fun verify(file: MoveFile): MoveFile {
        val stat = guarded { target.stat(file.relTarget) }
        if (!target.caps.canReadBack) {
            // MOVE-14: the copy stays, but it can't be checked, so it never counts as verified.
            return if (stat != null && stat.size == file.sourceSize) {
                put(file.copy(state = MoveFileState.COPIED_UNVERIFIED))
            } else {
                put(file.copy(state = MoveFileState.PENDING, attempts = file.attempts + 1))
            }
        }
        val hash = if (stat?.size == file.sourceSize) readBackHash(file.relTarget) else null
        if (hash != null && hash == file.sourceSha256) {
            return put(file.copy(state = MoveFileState.VERIFIED, verifiedAt = clock(), failReason = null))
        }
        val attempts = file.attempts + 1
        if (file.reusedExisting) {
            // A file we didn't write no longer reads back as the source: leave it alone and copy to a free name.
            val fresh = file.copy(relTarget = freeName(file.relTarget), nameChanged = true, reusedExisting = false)
            return if (attempts >= maxAttempts) {
                put(
                    fresh.copy(
                        state = MoveFileState.FAILED,
                        failReason = FailReason.HASH_MISMATCH,
                        attempts = attempts,
                    ),
                )
            } else {
                put(fresh.copy(state = MoveFileState.PENDING, attempts = attempts))
            }
        }
        // Damaged copy we wrote (E-MOVE-7): remove it and retry, then give up.
        if (stat != null) runCatching { target.delete(file.relTarget) }
        return if (attempts >= maxAttempts) {
            put(file.copy(state = MoveFileState.FAILED, failReason = FailReason.HASH_MISMATCH, attempts = attempts))
        } else {
            put(file.copy(state = MoveFileState.PENDING, attempts = attempts))
        }
    }

    private suspend fun readBackHash(rel: String): String? = try {
        guarded { target.openRead(rel) }.use { Sha256.of(it) }
    } catch (e: Pause) {
        throw e
    } catch (e: StorageException) {
        pauseFor(e)?.let { throw Pause(it) }
        null
    }

    private suspend fun freeName(rel: String): String {
        val dir = rel.substringBeforeLast('/')
        val name = rel.substringAfterLast('/')
        var n = 2
        val maxChars = target.caps.maxPathChars?.let { it - dir.length - 1 } ?: Int.MAX_VALUE
        while (true) {
            val candidate = "$dir/${io.github.tomerar.keepclear.backup.TargetNames.withSuffix(
                name,
                n++,
                maxChars = maxChars,
            )}"
            if (guarded { target.stat(candidate) } == null) return candidate
        }
    }

    /** Runs a storage call; connection or access loss pauses the whole job. */
    private suspend fun <T> guarded(block: suspend () -> T): T = try {
        block()
    } catch (e: StorageException) {
        pauseFor(e)?.let { throw Pause(it) }
        throw e
    }

    private fun pauseFor(e: StorageException): PauseReason? = when (e) {
        is StorageException.Disconnected -> PauseReason.TARGET_DISCONNECTED
        is StorageException.NoSpace -> PauseReason.TARGET_FULL
        is StorageException.PermissionDenied -> PauseReason.ACCESS_LOST
        else -> null
    }

    private fun isSourceError(e: StorageException, file: MoveFile): Boolean = when (e) {
        is StorageException.NotFound -> e.path == file.source
        is StorageException.Io -> e.path == file.source
        else -> false
    }

    // -------------------------------------------------------------------------------- manifest

    /** MOVE-8: manifest + `sha256sum -c` file, written after the files verify, via temp + rename. */
    private suspend fun writeManifest(job: MoveJob) {
        // Unverified copies are listed too: the hash is what was sent, so `sha256sum -c` can check them later.
        val verified = store.files(job.id).filter {
            it.state == MoveFileState.VERIFIED || it.state == MoveFileState.COPIED_UNVERIFIED
        }
        if (verified.isEmpty()) return
        writeMerged(job, Manifest.SUMS) { existing -> Manifest.mergeSums(existing, verified, job.rootRel) }
        writeMerged(job, Manifest.JSONL) { existing -> Manifest.mergeJsonl(existing, verified, job.rootRel) }
    }

    /**
     * Merges into the existing file. If that file can't be read whole (I/O error, or larger than
     * [Manifest.MAX_BYTES]), it is left untouched and this job's lines go to a separate `<name>-<jobId>` file,
     * so earlier entries are never lost.
     */
    private suspend fun writeMerged(job: MoveJob, name: String, merge: (String?) -> String) {
        val rel = "${job.rootRel}/$name"
        val existing = readWhole(rel)
        if (existing is Existing.Unusable) {
            replace("$rel-${job.id}", merge(null))
        } else {
            replace(rel, merge((existing as? Existing.Text)?.text))
        }
    }

    private sealed interface Existing {
        data object None : Existing

        data class Text(val text: String) : Existing

        data object Unusable : Existing
    }

    /** Reads [rel], or the previous version kept by an interrupted [replace]. */
    private suspend fun readWhole(rel: String): Existing = try {
        val current = target.stat(rel)
        val name = if (current == null && target.stat(old(rel)) != null) old(rel) else rel
        val stat = if (name == rel) current else target.stat(name)
        when {
            stat == null -> Existing.None

            stat.size > Manifest.MAX_BYTES -> Existing.Unusable

            else -> target.openRead(name).use { input ->
                val bytes = input.readUpTo(Manifest.MAX_BYTES + 1)
                if (bytes.size > Manifest.MAX_BYTES) {
                    Existing.Unusable
                } else {
                    Existing.Text(bytes.toString(Charsets.UTF_8))
                }
            }
        }
    } catch (e: StorageException) {
        pauseFor(e)?.let { throw Pause(it) }
        Existing.Unusable
    }

    private fun old(rel: String) = "$rel.kc-old"

    /**
     * Crash-safe replace: write the temp file, move the current version aside, install the temp file, then drop
     * the old one. At every point either [rel] or `<rel>.kc-old` holds a complete manifest ([readWhole] reads it).
     */
    private suspend fun replace(rel: String, text: String) {
        val temp = "$rel.kc-tmp"
        guarded {
            if (target.stat(temp) != null) target.delete(temp)
            target.create(temp).use { it.write(text.toByteArray(Charsets.UTF_8)) }
            if (target.stat(rel) != null) {
                if (target.stat(old(rel)) != null) target.delete(old(rel))
                target.rename(rel, old(rel))
            }
            target.rename(temp, rel)
            if (target.stat(old(rel)) != null) target.delete(old(rel))
        }
    }

    // --------------------------------------------------------------------------------- results

    suspend fun receipt(jobId: String): Receipt =
        Receipt.of(store.job(jobId) ?: error("unknown job $jobId"), store.files(jobId))

    /**
     * MOVE-9/10: the plan the receipt's "Free from phone" button confirms. Only L3-verified files whose
     * copy is still on the target with the right size; each op carries the verified SHA-256 so the
     * executor re-hashes the original right before trashing it (SAFETY-5, strict mode). Never offered
     * when the target can't be read back (MOVE-14).
     */
    suspend fun originalsPlan(jobId: String): List<PlannedOp> {
        if (!target.caps.canReadBack) return emptyList()
        val job = store.job(jobId) ?: return emptyList()
        return store.files(
            jobId,
        ).filter { it.state == MoveFileState.VERIFIED && it.sourceSha256 != null }.mapNotNull { f ->
            val copy = runCatching { target.stat(f.relTarget) }.getOrNull()
            if (copy?.size != f.sourceSize) return@mapNotNull null
            val now = source.stat(f.source) ?: return@mapNotNull null
            if (now.size != f.sourceSize || now.mtimeMillis != f.sourceMtime) return@mapNotNull null
            PlannedOp(
                ref = f.source,
                displayPath = f.sourcePath,
                kind = OpKind.MOVE_DELETE_ORIGINAL,
                isDirectory = false,
                expectedSize = f.sourceSize,
                expectedMtime = f.sourceMtime,
                expectedIdentity = f.sourceIdentity,
                expectedSha256 = f.sourceSha256,
                verifiedCopy = "${job.targetLabel}: ${f.relTarget}",
            )
        }
    }
}
