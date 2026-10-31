// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.storage

import kotlinx.coroutines.flow.Flow
import java.io.InputStream
import java.io.OutputStream

/**
 * Backend-neutral access to one kind of storage (design.md §3.1).
 *
 * Contract (verified by `StorageGatewayContract` for every implementation):
 * - Nothing follows symlinks: [stat] and [list] report links as [Stat.Kind.SYMLINK]; [rename] and
 *   [delete] act on the link itself.
 * - [create], [mkdir] and [rename] never overwrite: an existing target is [StorageException.AlreadyExists].
 * - [delete] removes a file, a symlink or an **empty** directory only.
 * - Names are stored byte-exact (no Unicode normalisation by the gateway).
 * - All failures are [StorageException]s.
 *
 * Destructive members ([rename], [delete]) are called only from `core:safety` and the trash/storage
 * implementations; Konsist enforces this from M2.
 */
interface StorageGateway {
    val capabilities: Set<Capability>

    /** Streams the direct children of [dir]; cancellable. */
    fun list(dir: KPath): Flow<Stat>

    /** lstat; null when the path doesn't exist. */
    suspend fun stat(path: KPath): Stat?

    /** Opens a file for reading. Reading a directory is [StorageException.IsADirectory]. */
    suspend fun openRead(path: KPath): InputStream

    /** Creates a new file [name] in [dir] and opens it for writing (create-new, never overwrite). */
    suspend fun create(dir: KPath, name: String): OutputStream

    /** Creates directory [name] in [dir] (fails if it exists). */
    suspend fun mkdir(dir: KPath, name: String): KPath

    /** Moves [path] to [newParent]/[newName] on the same volume, without following links. */
    suspend fun rename(path: KPath, newParent: KPath, newName: String): KPath

    /** Deletes a file, a symlink or an empty directory. */
    suspend fun delete(path: KPath)
}
