// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.storage.file

import io.github.tomerar.keepclear.core.storage.Capability
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.core.storage.Stat
import io.github.tomerar.keepclear.core.storage.StorageException
import io.github.tomerar.keepclear.core.storage.StorageGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * [StorageGateway] over `java.nio.file` for [KPath.Local] paths (design.md §3.1). Used by the `foss`
 * flavor with All files access, and on the JVM for contract tests against a real file system.
 *
 * Never follows symlinks; never overwrites. Rename is `ATOMIC_MOVE` only, so a cross-volume move fails
 * ([StorageException.Unsupported]) instead of silently copying. Java has no portable "rename without
 * replace", so [rename] checks the target first; the remaining race window is tiny and callers use
 * unique names (UUIDs) for trash targets.
 */
class FileGateway : StorageGateway {
    override val capabilities: Set<Capability> = Capability.entries.toSet()

    override fun list(dir: KPath): Flow<Stat> = flow {
        val p = local(dir)
        val nio = nio(p)
        val stream = mapped(p) {
            if (!Files.isDirectory(nio, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.exists(nio, LinkOption.NOFOLLOW_LINKS)) throw StorageException.NotADirectory(p)
                throw StorageException.NotFound(p)
            }
            Files.newDirectoryStream(nio)
        }
        stream.use { entries ->
            for (child in entries) {
                val name = child.fileName.toString()
                val stat = mapped(p) { statOf(p.child(name), child) } ?: continue
                emit(stat)
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun stat(path: KPath): Stat? = io(path) {
        val p = local(path)
        statOf(p, nio(p))
    }

    override suspend fun openRead(path: KPath): InputStream = io(path) {
        val p = local(path)
        val nio = nio(p)
        if (Files.isDirectory(nio, LinkOption.NOFOLLOW_LINKS)) throw StorageException.IsADirectory(p)
        if (Files.isSymbolicLink(nio)) throw StorageException.Unsupported(p, "symlink")
        Files.newInputStream(nio, LinkOption.NOFOLLOW_LINKS)
    }

    override suspend fun create(dir: KPath, name: String): OutputStream = io(dir) {
        val d = local(dir)
        if (!KPath.isValidName(name)) throw StorageException.Io(d, message = "Invalid name '$name'")
        requireDirectory(d)
        val target = nio(d.child(name))
        Files.newOutputStream(
            target,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
        )
    }

    override suspend fun mkdir(dir: KPath, name: String): KPath = io(dir) {
        val d = local(dir)
        if (!KPath.isValidName(name)) throw StorageException.Io(d, message = "Invalid name '$name'")
        requireDirectory(d)
        val target = d.child(name)
        Files.createDirectory(nio(target))
        target
    }

    override suspend fun rename(path: KPath, newParent: KPath, newName: String): KPath = io(path) {
        val from = local(path)
        val toDir = local(newParent)
        if (!KPath.isValidName(newName)) throw StorageException.Io(toDir, message = "Invalid name '$newName'")
        if (!Files.exists(nio(from), LinkOption.NOFOLLOW_LINKS)) throw StorageException.NotFound(from)
        requireDirectory(toDir)
        val to = toDir.child(newName)
        if (to == from || to.isInside(from)) throw StorageException.Unsupported(from, "move into itself")
        if (Files.exists(nio(to), LinkOption.NOFOLLOW_LINKS)) throw StorageException.AlreadyExists(to)
        try {
            Files.move(nio(from), nio(to), StandardCopyOption.ATOMIC_MOVE, LinkOption.NOFOLLOW_LINKS)
        } catch (e: AtomicMoveNotSupportedException) {
            throw StorageException.Unsupported(from, "cross-volume rename")
        }
        to
    }

    override suspend fun delete(path: KPath): Unit = io(path) {
        val p = local(path)
        Files.delete(nio(p))
    }

    // ------------------------------------------------------------------ internals

    private fun local(path: KPath): KPath.Local =
        path as? KPath.Local ?: throw StorageException.Unsupported(path, "FileGateway handles Local paths")

    private fun nio(p: KPath.Local): Path = Paths.get(p.absolute)

    private fun requireDirectory(d: KPath.Local) {
        val nio = nio(d)
        if (!Files.exists(nio, LinkOption.NOFOLLOW_LINKS)) throw StorageException.NotFound(d)
        if (!Files.isDirectory(nio, LinkOption.NOFOLLOW_LINKS)) throw StorageException.NotADirectory(d)
    }

    private fun statOf(p: KPath.Local, nio: Path): Stat? {
        val attrs = try {
            Files.readAttributes(nio, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (e: NoSuchFileException) {
            return null
        }
        val kind = when {
            attrs.isSymbolicLink -> Stat.Kind.SYMLINK
            attrs.isDirectory -> Stat.Kind.DIRECTORY
            else -> Stat.Kind.FILE
        }
        return Stat(
            path = p,
            name = p.name,
            kind = kind,
            size = if (kind == Stat.Kind.DIRECTORY) 0 else attrs.size(),
            mtimeMillis = attrs.lastModifiedTime().toMillis(),
            identity = attrs.fileKey()?.toString(),
        )
    }

    private suspend fun <T> io(path: KPath, block: () -> T): T = withContext(Dispatchers.IO) { mapped(path, block) }

    private inline fun <T> mapped(path: KPath, block: () -> T): T = try {
        block()
    } catch (e: StorageException) {
        throw e
    } catch (e: NoSuchFileException) {
        throw StorageException.NotFound(path)
    } catch (e: FileAlreadyExistsException) {
        throw StorageException.AlreadyExists(KPath.Local(e.file ?: (path as? KPath.Local)?.absolute ?: "/"))
    } catch (e: DirectoryNotEmptyException) {
        throw StorageException.NotEmpty(path)
    } catch (e: NotDirectoryException) {
        throw StorageException.NotADirectory(path)
    } catch (e: AccessDeniedException) {
        throw StorageException.PermissionDenied(path, e)
    } catch (e: java.nio.file.InvalidPathException) {
        throw StorageException.Io(path, e, "Name not representable on this file system")
    } catch (e: SecurityException) {
        throw StorageException.PermissionDenied(path, e)
    } catch (e: IOException) {
        val message = e.message.orEmpty()
        when {
            "No space left" in message || "ENOSPC" in message -> throw StorageException.NoSpace(path)
            "EACCES" in message || "Permission denied" in message -> throw StorageException.PermissionDenied(path, e)
            else -> throw StorageException.Io(path, e)
        }
    }
}
