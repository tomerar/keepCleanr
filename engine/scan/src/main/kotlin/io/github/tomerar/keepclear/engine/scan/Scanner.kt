// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.scan

import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.core.storage.StorageException
import io.github.tomerar.keepclear.core.storage.StorageGateway
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList

/**
 * Walks storage roots and/or MediaStore and streams [ScanEvent]s (design.md §4 step 1).
 *
 * - Read-only: the scanner never writes; cancelling the flow leaves no state (SCAN-3).
 * - Iterative walk with an explicit stack, no recursion, never follows symlinks (SCAN-6, SEC-6).
 * - Memory is bounded by the directory stack and one batch, not by the number of files (NFR-2).
 * - `Android/data` and `Android/obb` are skipped unless [readRestrictedAppDirs] (Shizuku, v1).
 * - MediaStore rows enrich walked files (favourite, trashed, media id); in MediaStore-only mode
 *   (no file roots) rows become items themselves.
 */
class Scanner(
    private val gateway: StorageGateway,
    private val mediaSource: MediaSource? = null,
    private val batchSize: Int = 500,
    private val readRestrictedAppDirs: Boolean = false,
) {
    fun scan(roots: List<KPath.Local>, sinceGeneration: Long? = null): Flow<ScanEvent> = flow {
        val media = mediaSource?.query(sinceGeneration).orEmpty()
        val mediaByPath = media.associateBy { it.path }
        val maxGeneration = media.maxOfOrNull { it.generation }

        var files = 0
        var dirs = 0
        var bytes = 0L
        var symlinks = 0
        val restricted = mutableListOf<String>()
        val batch = ArrayList<ScanItem>(batchSize)
        val walkedPaths = HashSet<String>()

        suspend fun flush() {
            if (batch.isNotEmpty()) {
                emit(ScanEvent.Batch(batch.toList()))
                batch.clear()
                emit(ScanEvent.Progress(dirs, files, bytes))
            }
        }

        for (root in roots) {
            val stack = ArrayDeque<KPath.Local>().apply { add(root) }
            var lost = false
            while (stack.isNotEmpty() && !lost) {
                currentCoroutineContext().ensureActive()
                val dir = stack.removeLast()
                if (!readRestrictedAppDirs && isRestrictedAppDir(root, dir)) {
                    restricted += dir.absolute
                    continue
                }
                dirs++
                val children = try {
                    gateway.list(dir).toList()
                } catch (e: StorageException.PermissionDenied) {
                    flush()
                    emit(ScanEvent.Unreadable(dir.absolute, "permission denied"))
                    if (dir == root) continue
                    emit(ScanEvent.Done(false, files, bytes, symlinks, restricted, maxGeneration))
                    return@flow
                } catch (e: StorageException.Disconnected) {
                    flush()
                    emit(ScanEvent.VolumeLost(root.absolute))
                    lost = true
                    continue
                } catch (e: StorageException) {
                    emit(ScanEvent.Unreadable(dir.absolute, e.javaClass.simpleName))
                    continue
                }
                for (child in children) {
                    val childPath = child.path as? KPath.Local ?: continue
                    when {
                        child.isSymlink -> {
                            symlinks++
                        }

                        child.isDirectory -> {
                            stack += childPath
                        }

                        else -> {
                            val row = mediaByPath[childPath.absolute]
                            walkedPaths += childPath.absolute
                            batch += ScanItem(
                                ref = childPath,
                                path = childPath.absolute,
                                size = child.size,
                                mtimeMillis = child.mtimeMillis,
                                identity = child.identity,
                                mime = row?.mime,
                                mediaId = row?.id,
                                isFavorite = row?.isFavorite ?: false,
                                isTrashed = row?.isTrashed ?: false,
                                source = Source.FILE_WALK,
                            )
                            files++
                            bytes += child.size
                            if (batch.size >= batchSize) flush()
                        }
                    }
                }
            }
        }

        // MediaStore rows not seen by the walk (no file roots, or outside them).
        for (row in media) {
            currentCoroutineContext().ensureActive()
            if (row.path in walkedPaths) continue
            if (roots.any { row.path.startsWith(it.absolute + "/") }) continue // walked root, file gone
            batch += ScanItem(
                ref = KPath.Media(row.volume, row.id),
                path = row.path,
                size = row.size,
                mtimeMillis = row.mtimeMillis,
                mime = row.mime,
                mediaId = row.id,
                isFavorite = row.isFavorite,
                isTrashed = row.isTrashed,
                source = Source.MEDIA_STORE,
            )
            files++
            bytes += row.size
            if (batch.size >= batchSize) flush()
        }
        flush()
        emit(ScanEvent.Done(true, files, bytes, symlinks, restricted, maxGeneration))
    }

    private fun isRestrictedAppDir(root: KPath.Local, dir: KPath.Local): Boolean {
        val rel = dir.absolute.removePrefix(root.absolute).trimStart('/').lowercase()
        return rel == "android/data" || rel == "android/obb"
    }
}
