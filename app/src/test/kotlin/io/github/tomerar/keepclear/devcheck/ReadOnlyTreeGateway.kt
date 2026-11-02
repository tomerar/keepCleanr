// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.devcheck

import io.github.tomerar.keepclear.core.storage.Capability
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.core.storage.Stat
import io.github.tomerar.keepclear.core.storage.StorageException
import io.github.tomerar.keepclear.core.storage.StorageGateway
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import java.io.InputStream
import java.io.OutputStream

/**
 * A small in-memory file tree for the device-check tests. Every write member fails the test: the device check
 * must only list, stat and read. Names match ignoring case when [caseInsensitive], like Android's shared storage.
 */
class ReadOnlyTreeGateway(private val caseInsensitive: Boolean = true) : StorageGateway {
    private val files = linkedMapOf<String, Long>()
    private val dirs = linkedSetOf("/")
    private val links = linkedSetOf<String>()
    private val denied = mutableSetOf<String>()
    val writes = mutableListOf<String>()

    override val capabilities: Set<Capability> = emptySet()

    private fun key(p: String) = if (caseInsensitive) p.lowercase() else p

    fun file(path: String, size: Long) = apply {
        addDirs(path.substringBeforeLast('/'))
        files[path] = size
    }

    fun dir(path: String) = apply { addDirs(path) }

    fun link(path: String) = apply {
        addDirs(path.substringBeforeLast('/'))
        links += path
    }

    /** Listing [path] fails with "permission denied". */
    fun deny(path: String) = apply { denied += key(path) }

    private fun addDirs(path: String) {
        var p = path
        while (p.isNotEmpty() && p != "/") {
            dirs += p
            p = p.substringBeforeLast('/')
        }
    }

    private fun realDir(path: String) = dirs.firstOrNull { key(it) == key(path) }

    override fun list(dir: KPath): Flow<Stat> {
        val p = (dir as KPath.Local).absolute
        if (key(p) in denied) throw StorageException.PermissionDenied(dir)
        val real = realDir(p) ?: throw StorageException.NotFound(dir)
        val prefix = if (real == "/") "/" else "$real/"

        fun direct(child: String) = child.startsWith(prefix) && '/' !in child.removePrefix(prefix) && child != real
        val children =
            dirs.filter(::direct).map { Stat(KPath.Local(it), it.substringAfterLast('/'), Stat.Kind.DIRECTORY, 0, 0) } +
                files.filterKeys(::direct).map { (f, size) ->
                    Stat(KPath.Local(f), f.substringAfterLast('/'), Stat.Kind.FILE, size, 1_600_000_000_000)
                } +
                links.filter(::direct).map { node(it, Stat.Kind.SYMLINK) }
        return children.asFlow()
    }

    private fun node(path: String, kind: Stat.Kind) = Stat(KPath.Local(path), path.substringAfterLast('/'), kind, 0, 0)

    override suspend fun stat(path: KPath): Stat? {
        val p = (path as KPath.Local).absolute
        files.entries.firstOrNull { key(it.key) == key(p) }?.let {
            return Stat(
                KPath.Local(it.key),
                it.key.substringAfterLast('/'),
                Stat.Kind.FILE,
                it.value,
                1_600_000_000_000,
            )
        }
        return realDir(p)?.let { Stat(KPath.Local(it), it.substringAfterLast('/'), Stat.Kind.DIRECTORY, 0, 0) }
    }

    override suspend fun openRead(path: KPath): InputStream {
        val size = stat(path)?.size ?: throw StorageException.NotFound(path)
        return java.io.ByteArrayInputStream(ByteArray(size.coerceAtMost(4096).toInt()))
    }

    private fun refuse(what: String): Nothing {
        writes += what
        throw AssertionError("the device check must not $what")
    }

    override suspend fun create(dir: KPath, name: String): OutputStream = refuse("create")

    override suspend fun mkdir(dir: KPath, name: String): KPath = refuse("mkdir")

    override suspend fun rename(path: KPath, newParent: KPath, newName: String): KPath = refuse("rename")

    override suspend fun delete(path: KPath) = refuse("delete")
}
