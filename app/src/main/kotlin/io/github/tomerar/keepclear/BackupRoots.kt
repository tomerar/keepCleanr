// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.provider.DocumentsContract
import androidx.core.net.toUri
import io.github.tomerar.keepclear.backup.saf.SafTargets
import io.github.tomerar.keepclear.core.safety.ProtectedPaths
import io.github.tomerar.keepclear.engine.move.MoveJob

/**
 * H3, CLAUDE.md safety rule 4: the folders KeepClear backs up to are PROTECTED, so no clean, review or trash
 * action can ever touch a backup copy, even when the drive is mounted as a path the scan can see.
 */
object BackupRoots {
    fun of(jobs: List<MoveJob>): List<String> = jobs.mapNotNull { job ->
        val uri = job.targetRef?.toUri() ?: return@mapNotNull null
        val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return@mapNotNull null
        SafTargets.localPath(uri.authority, docId)
    }.distinct()

    fun protectedPaths(
        jobs: List<MoveJob>,
        ownPackage: String = "io.github.tomerar.keepclear",
        /** CLS-7: files and folders the user kept. */
        kept: Collection<String> = emptyList(),
    ) = ProtectedPaths(extraRoots = of(jobs) + kept, ownPackage = ownPackage)
}
