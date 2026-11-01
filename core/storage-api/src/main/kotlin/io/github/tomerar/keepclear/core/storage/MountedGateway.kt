// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.storage

import kotlinx.coroutines.flow.Flow
import java.io.InputStream
import java.io.OutputStream

/**
 * M12b: file paths inside a mounted folder go to that folder's gateway; every other path goes to [base].
 *
 * A folder the user granted through the system picker (e.g. WhatsApp's folder in the `play` flavor) is mounted at
 * its real path, so scanning, rules, PROTECTED paths and the trash keep working on ordinary paths. The longest
 * mount wins. [mounts] is read on every call, so granting or losing a folder takes effect at once.
 */
class MountedGateway(
    private val base: StorageGateway,
    private val mounts: () -> Map<String, StorageGateway>,
) : StorageGateway {
    override val capabilities: Set<Capability> get() = base.capabilities

    private fun of(path: KPath): StorageGateway {
        val local = path as? KPath.Local ?: return base
        return mounts().entries
            .filter { (point, _) -> local.absolute == point || local.absolute.startsWith("$point/") }
            .maxByOrNull { it.key.length }?.value ?: base
    }

    override fun list(dir: KPath): Flow<Stat> = of(dir).list(dir)

    override suspend fun stat(path: KPath): Stat? = of(path).stat(path)

    override suspend fun openRead(path: KPath): InputStream = of(path).openRead(path)

    override suspend fun create(dir: KPath, name: String): OutputStream = of(dir).create(dir, name)

    override suspend fun mkdir(dir: KPath, name: String): KPath = of(dir).mkdir(dir, name)

    override suspend fun rename(path: KPath, newParent: KPath, newName: String): KPath {
        val gateway = of(path)
        // A granted folder can only move documents within itself; never fall back to copy + delete.
        if (of(newParent) !== gateway) throw StorageException.Unsupported(path, "move into or out of a granted folder")
        return gateway.rename(path, newParent, newName)
    }

    override suspend fun delete(path: KPath) = of(path).delete(path)
}
