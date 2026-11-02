// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.move

import io.github.tomerar.keepclear.backup.BackupTarget
import io.github.tomerar.keepclear.core.hash.Sha256
import io.github.tomerar.keepclear.core.storage.StorageException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * D6: "Release now" permanently deletes an original that was freed after its backup was checked twice. Right
 * before that, the backup copy must still be there and intact: same size, and the SHA-256 of the copy, read back
 * now, equal to the original's (C52: always both, on every kind of backup place, whatever the size; Release can't
 * be undone, so the slower check is chosen).
 *
 * Only files whose MOVE record is VERIFIED and confirmed by the second check (H1) count, and only a record of the
 * **same content** as the trashed item ([ReleaseKey]: path, size and SHA-256 of the trash entry, PR #43 review): a
 * path backed up twice with different contents is released only when the copy of the trashed version is intact.
 * A backup place that can't be opened, isn't the same one ([BackupTarget.id]) or fails mid-read leaves those files
 * in the trash.
 */
class ReleaseCheck(
    private val store: MoveJobStore,
    private val jobs: List<MoveJob>,
    /** Opens the job's backup place, or null when it isn't available right now. The check closes it. */
    private val open: suspend (MoveJob) -> BackupTarget?,
) {
    /** The subset of [wanted] whose own backup copy (same path, size and SHA-256) is still intact right now. */
    suspend fun intact(wanted: Collection<ReleaseKey>): Set<ReleaseKey> {
        val keys = wanted.filter { Sha256.isValidHex(it.sha256) }.toSet()
        val ok = mutableSetOf<ReleaseKey>()
        for (job in jobs) {
            val candidates = store.files(job.id).filter {
                it.state == MoveFileState.VERIFIED && it.confirmedAt != null && it.key() in keys && it.key() !in ok
            }
            if (candidates.isEmpty()) continue
            val target = try {
                open(job)
            } catch (e: StorageException) {
                null
            } ?: continue
            try {
                if (target.id != job.targetId) continue
                for (file in candidates) {
                    currentCoroutineContext().ensureActive()
                    if (file.key() !in ok && copyIntact(target, file)) ok += file.key()
                }
            } finally {
                withContext(NonCancellable) { runCatching { target.close() } }
            }
        }
        return ok
    }

    private fun MoveFile.key() = ReleaseKey(sourcePath, sourceSize, sourceSha256.orEmpty())

    private suspend fun copyIntact(target: BackupTarget, file: MoveFile): Boolean = try {
        val stat = target.stat(file.relTarget)
        stat != null && stat.isFile && stat.size == file.sourceSize &&
            target.openRead(file.relTarget).use { Sha256.of(it) } == file.sourceSha256
    } catch (e: IOException) {
        // StorageException is an IOException too: unreadable or gone mid-read counts as "not intact".
        false
    }
}

/**
 * What a trashed original was (its trash entry's original path, size and SHA-256). A trash entry without a recorded
 * SHA-256 has no key and is never released early (C57).
 */
data class ReleaseKey(val path: String, val size: Long, val sha256: String)
