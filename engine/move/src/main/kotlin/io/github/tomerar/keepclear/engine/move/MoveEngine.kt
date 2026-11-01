// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.move

import io.github.tomerar.keepclear.backup.BackupTarget
import io.github.tomerar.keepclear.backup.DigestKind
import io.github.tomerar.keepclear.core.hash.DropboxContentHash
import io.github.tomerar.keepclear.core.hash.Sha256
import io.github.tomerar.keepclear.core.safety.OpKind
import io.github.tomerar.keepclear.core.safety.PlannedOp
import io.github.tomerar.keepclear.core.storage.StorageException
import io.github.tomerar.keepclear.core.storage.StorageGateway
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
    /** Identifies this boot; the page cache doesn't survive a reboot (H1, [FreshRead.REBOOTED]). */
    private val bootId: () -> String = { "unknown-boot" },
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
        } catch (e: StorageException) {
            // Anything not mapped above: pause and keep every checkpoint; never crash the service (review Low).
            return job.copy(state = JobState.PAUSED, pauseReason = PauseReason.UNEXPECTED_ERROR).also {
                store.putJob(it)
            }
        } catch (e: java.io.IOException) {
            return job.copy(state = JobState.PAUSED, pauseReason = PauseReason.UNEXPECTED_ERROR).also {
                store.putJob(it)
            }
        } catch (e: CancellationException) {
            // Paused from outside (user, system time limit). The current temp file is removed on resume (E-MOVE-1).
            withContext(NonCancellable) {
                store.putJob(job.copy(state = JobState.PAUSED, pauseReason = cancelReason()))
            }
            throw e
        }
        job = job.copy(verifyBootId = bootId())
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
        // M17, ADR 0011: Dropbox reports its own block hash, so it is computed here from the same read.
        val contentHash = DropboxContentHash().takeIf { DigestKind.DROPBOX_CONTENT_HASH in target.caps.serverDigests }
        val (bytes, sha) = try {
            source.openRead(file.source).use { input ->
                // The size is announced so a network target can send it up front (M17); a source that grows or
                // shrinks mid-copy then fails the upload, which is caught as a source change below.
                guarded { target.create(file.tempRel, before.size) }.use { output ->
                    Sha256.copy(input, output, contentHash)
                }
            }
        } catch (e: Pause) {
            runCatching { target.delete(file.tempRel) }
            throw e
        } catch (e: StorageException) {
            val pause = pauseFor(e)
            runCatching { target.delete(file.tempRel) }
            if (pause != null) throw Pause(pause)
            val now = runCatching { source.stat(file.source) }.getOrNull()
            if (now != null && (now.size != before.size || now.mtimeMillis != before.mtimeMillis)) {
                return put(file.copy(state = MoveFileState.SRC_CHANGED, attempts = file.attempts + 1))
            }
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
        return put(
            file.copy(state = MoveFileState.COPIED, sourceSha256 = sha, sourceContentHash = contentHash?.hex()),
        )
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
        // M17, ADR 0011: a digest the server computed over what it stored is the proof; no download needed.
        // Without one (generic WebDAV, a header the server didn't send, a file we didn't upload), read back.
        val byServer = if (stat?.size == file.sourceSize && !file.reusedExisting) serverDigestMatches(file) else null
        val readBack = if (byServer == null && stat?.size == file.sourceSize) readBackHash(file.relTarget) else null
        val matches = byServer ?: (readBack != null && readBack == file.sourceSha256)
        if (matches) {
            // A read-back may have come from the page cache (H1), so freeing needs confirm(). A server digest was
            // computed by the server over what it stored, never from the phone's cache: it counts as confirmed.
            // The copy is still read back and re-hashed at free time (originalsPlan).
            return put(
                file.copy(
                    state = MoveFileState.VERIFIED,
                    verifiedAt = clock(),
                    failReason = null,
                    confirmedAt = if (byServer == true) clock() else null,
                ),
            )
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

    /**
     * True/false when the server reported a well-formed digest of a kind it computes and KeepClear has the local
     * digest of the same kind (match/mismatch); null when there is nothing to compare (then the copy is read back).
     */
    private suspend fun serverDigestMatches(file: MoveFile): Boolean? {
        for (kind in SERVER_DIGEST_ORDER) {
            if (kind !in target.caps.serverDigests) continue
            val local = when (kind) {
                DigestKind.SHA256 -> file.sourceSha256
                DigestKind.DROPBOX_CONTENT_HASH -> file.sourceContentHash
            } ?: continue
            val remote = guarded { target.serverDigest(file.relTarget, kind) }
                ?.takeIf { Sha256.isValidHex(it) } ?: continue
            return remote == local
        }
        return null
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
    private suspend fun writeMerged(job: MoveJob, name: String, merge: (List<String>?) -> String) {
        val rel = "${job.rootRel}/$name"
        val existing = readWhole(rel)
        if (existing is Existing.Unusable) {
            replace("$rel-${job.id}", merge(null))
        } else {
            replace(rel, merge((existing as? Existing.Lines)?.lines))
        }
    }

    private sealed interface Existing {
        data object None : Existing

        data class Lines(val lines: List<String>) : Existing

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
                Manifest.readLines(input)?.let { Existing.Lines(it) } ?: Existing.Unusable
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
        return store.files(jobId).filter {
            // H1: verified twice, the second time with a read that couldn't come from the page cache.
            it.state == MoveFileState.VERIFIED && it.sourceSha256 != null && it.confirmedAt != null
        }.mapNotNull { f ->
            val copy = runCatching { target.stat(f.relTarget) }.getOrNull()
            if (copy?.size != f.sourceSize) return@mapNotNull null
            // Re-hash the copy at free time, not just its size (H1).
            val copyHash = runCatching { readBackHash(f.relTarget) }.getOrNull()
            if (copyHash != f.sourceSha256) return@mapNotNull null
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

    /**
     * Second pass ("Check backup", MOVE-15; H1): re-reads every verified copy with a read that can't be served
     * from the page cache, as attested by [evidence]. Matching copies become freeable; missing or damaged ones go
     * back to "copy again" (never freed). [FreshRead.REBOOTED] is checked here against [bootId].
     */
    suspend fun confirm(jobId: String, evidence: FreshRead): CheckResult {
        val job = store.job(jobId) ?: error("unknown job $jobId")
        if (evidence == FreshRead.REBOOTED) {
            check(job.verifyBootId == null || job.verifyBootId != bootId()) {
                "same boot as the first check: the drive must be unplugged and plugged back in"
            }
        }
        if (evidence == FreshRead.NEW_SESSION) {
            // A drive on the phone may answer from the phone's own cache; only a network drive's reads are fresh.
            check(target.caps.readsFromServer) { "a new connection proves a fresh read only for a network drive" }
        }
        var ok = 0
        var damaged = 0
        var missing = 0
        for (f in store.files(jobId).filter { it.state == MoveFileState.VERIFIED }) {
            currentCoroutineContext().ensureActive()
            // Unplugged or access lost mid-check: stop, keep what was confirmed, touch nothing else.
            val read = try {
                val stat = target.stat(f.relTarget)
                stat to (if (stat?.size == f.sourceSize) readBackHash(f.relTarget) else null)
            } catch (e: Pause) {
                return CheckResult(ok, damaged, missing, interrupted = true)
            } catch (e: StorageException) {
                if (pauseFor(e) != null) return CheckResult(ok, damaged, missing, interrupted = true)
                throw e
            }
            val (stat, hash) = read
            when {
                hash != null && hash == f.sourceSha256 -> {
                    ok++
                    put(f.copy(confirmedAt = clock()))
                }

                else -> {
                    if (stat == null) missing++ else damaged++
                    // Never delete a copy here: it may be the user's only one if the original is already gone.
                    put(f.copy(state = MoveFileState.PENDING, confirmedAt = null, verifiedAt = null, attempts = 0))
                }
            }
        }
        if (damaged + missing > 0) store.putJob(job.copy(state = JobState.READY))
        return CheckResult(ok, damaged, missing)
    }

    private companion object {
        /** Kinds a server may compute, in the order they are asked for (one answer is enough). */
        val SERVER_DIGEST_ORDER = listOf(DigestKind.SHA256, DigestKind.DROPBOX_CONTENT_HASH)
    }
}
