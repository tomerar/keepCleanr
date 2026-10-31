// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.backup

import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.core.storage.Stat
import io.github.tomerar.keepclear.core.storage.StorageException
import io.github.tomerar.keepclear.core.storage.StorageGateway
import kotlinx.coroutines.flow.toList
import java.io.InputStream
import java.io.OutputStream

/**
 * A [BackupTarget] on any [StorageGateway] directory: a USB/SD volume path with All files access, or (M10)
 * a SAF tree. Free space comes from [freeSpace] (StatFs on Android).
 */
class GatewayBackupTarget(
    private val gateway: StorageGateway,
    private val root: KPath.Local,
    override val id: String,
    override val label: String,
    override val caps: TargetCaps,
    private val freeSpace: suspend () -> Long? = { null },
) : BackupTarget {
    private fun path(rel: String): KPath.Local {
        require(!rel.startsWith("/")) { "relative path expected: $rel" }
        return if (rel.isEmpty()) root else KPath.Local("${root.absolute}/$rel")
    }

    override suspend fun freeBytes() = freeSpace()

    override suspend fun mkdirs(rel: String) {
        var current = root
        for (segment in rel.split('/').filter { it.isNotEmpty() }) {
            val next = current.child(segment)
            val stat = gateway.stat(next)
            when {
                stat == null -> gateway.mkdir(current, segment)
                !stat.isDirectory -> throw StorageException.NotADirectory(next)
            }
            current = next
        }
    }

    override suspend fun create(rel: String): OutputStream {
        val p = path(rel)
        return gateway.create(p.parent!!, p.name)
    }

    override suspend fun rename(fromRel: String, toRel: String) {
        val to = path(toRel)
        gateway.rename(path(fromRel), to.parent!!, to.name)
    }

    override suspend fun stat(rel: String): Stat? = gateway.stat(path(rel))

    override suspend fun openRead(rel: String): InputStream = gateway.openRead(path(rel))

    override suspend fun delete(rel: String) = gateway.delete(path(rel))

    override suspend fun list(relDir: String): List<Stat> = gateway.list(path(relDir)).toList()
}
