// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.storage

import kotlinx.coroutines.flow.Flow
import java.io.InputStream
import java.io.OutputStream

/**
 * Picks the gateway for each path by its kind (design.md §3.1 `GatewayRouter`): file paths, MediaStore rows and
 * SAF documents each go to their own backend. A kind without a backend is [StorageException.Unsupported].
 */
class RoutingGateway(
    private val local: StorageGateway,
    private val media: StorageGateway? = null,
    private val saf: StorageGateway? = null,
) : StorageGateway {
    override val capabilities: Set<Capability> get() = local.capabilities

    private fun of(path: KPath): StorageGateway = when (path) {
        is KPath.Local -> local
        is KPath.Media -> media ?: throw StorageException.Unsupported(path, "no MediaStore backend")
        is KPath.Saf -> saf ?: throw StorageException.Unsupported(path, "no SAF backend")
    }

    override fun list(dir: KPath): Flow<Stat> = of(dir).list(dir)

    override suspend fun stat(path: KPath): Stat? = of(path).stat(path)

    override suspend fun openRead(path: KPath): InputStream = of(path).openRead(path)

    override suspend fun create(dir: KPath, name: String): OutputStream = of(dir).create(dir, name)

    override suspend fun mkdir(dir: KPath, name: String): KPath = of(dir).mkdir(dir, name)

    override suspend fun rename(path: KPath, newParent: KPath, newName: String): KPath {
        val gateway = of(path)
        if (of(newParent) !== gateway) throw StorageException.Unsupported(path, "rename across backends")
        return gateway.rename(path, newParent, newName)
    }

    override suspend fun delete(path: KPath) = of(path).delete(path)
}
