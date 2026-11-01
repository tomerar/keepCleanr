// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.storage.saf

import io.github.tomerar.keepclear.core.storage.Capability
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.core.storage.Stat
import io.github.tomerar.keepclear.core.storage.StorageException
import io.github.tomerar.keepclear.core.storage.StorageGateway
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * M12b: a granted folder ([root], a SAF tree) served under its real path [mountPoint], e.g.
 * `/storage/emulated/0/Android/media/com.whatsapp/WhatsApp`. Callers use ordinary [KPath.Local] paths, so the
 * scanner, the rules, PROTECTED paths and the trash work unchanged; every call is a [SafGateway] call underneath.
 *
 * Paths are resolved to documents by name, one folder at a time; a folder's children are cached when it is
 * listed and dropped from the cache when something inside it changes. A cached document that has vanished is
 * looked up again by name once, so a stale cache never makes a file look missing.
 */
class SafTreeMount(
    private val saf: StorageGateway,
    private val root: KPath.Saf,
    private val mountPoint: String,
) : StorageGateway {
    private val mount = KPath.Local(mountPoint)

    override val capabilities: Set<Capability> get() = saf.capabilities

    /** Local path → document id, filled by listings. */
    private val ids = ConcurrentHashMap<String, String>()

    private fun local(path: KPath): KPath.Local {
        val p = path as? KPath.Local ?: throw StorageException.Unsupported(path, "not a path")
        if (p != mount && !p.isInside(mount)) throw StorageException.Unsupported(p, "outside the granted folder")
        return p
    }

    private fun doc(id: String) = KPath.Saf(root.treeUri, id)

    private fun forget(path: KPath.Local) {
        ids.keys.removeIf { it == path.absolute || it.startsWith(path.absolute + "/") }
    }

    /** Lists [dir]'s document and remembers each child's id. */
    private suspend fun children(dir: KPath.Local, dirDoc: KPath.Saf): List<Stat> {
        val list = saf.list(dirDoc).toList()
        return list.mapNotNull { s ->
            if (!KPath.isValidName(s.name)) return@mapNotNull null
            val child = dir.child(s.name)
            ids[child.absolute] = (s.path as KPath.Saf).documentId
            s.copy(path = child)
        }
    }

    /** The document at [path], or null if nothing is there. */
    private suspend fun resolve(path: KPath.Local, useCache: Boolean = true): KPath.Saf? {
        if (path == mount) return root
        if (useCache) ids[path.absolute]?.let { return doc(it) }
        val parent = path.parent ?: return null
        val parentDoc = resolve(parent) ?: return null
        val listed = try {
            children(parent, parentDoc)
        } catch (_: StorageException.NotFound) {
            return null
        } catch (_: StorageException.NotADirectory) {
            return null
        }
        return listed.firstOrNull { it.name == path.name }?.let { doc(ids.getValue(path.absolute)) }
    }

    private suspend fun require(path: KPath.Local): KPath.Saf =
        resolveFresh(path) ?: throw StorageException.NotFound(path)

    /** Like [resolve], but a cached id whose document is gone is looked up again by name. */
    private suspend fun resolveFresh(path: KPath.Local): KPath.Saf? {
        val cached = resolve(path) ?: return resolve(path, useCache = false)
        if (cached == root || saf.stat(cached) != null) return cached
        forget(path)
        return resolve(path, useCache = false)
    }

    override fun list(dir: KPath): Flow<Stat> = flow {
        val d = local(dir)
        val doc = require(d)
        children(d, doc).forEach { emit(it) }
    }

    override suspend fun stat(path: KPath): Stat? {
        val p = local(path)
        val doc = resolveFresh(p) ?: return null
        return saf.stat(doc)?.copy(path = p, name = p.name)
    }

    override suspend fun openRead(path: KPath): InputStream = saf.openRead(require(local(path)))

    override suspend fun create(dir: KPath, name: String): OutputStream {
        val d = local(dir)
        val out = saf.create(require(d), name)
        if (KPath.isValidName(name)) forget(d.child(name))
        return out
    }

    override suspend fun mkdir(dir: KPath, name: String): KPath {
        val d = local(dir)
        val created = saf.mkdir(require(d), name) as KPath.Saf
        val child = d.child(name)
        ids[child.absolute] = created.documentId
        return child
    }

    override suspend fun rename(path: KPath, newParent: KPath, newName: String): KPath {
        val p = local(path)
        if (p == mount) throw StorageException.Unsupported(p, "the granted folder itself")
        val dest = local(newParent)
        val moved = saf.rename(require(p), require(dest), newName) as KPath.Saf
        forget(p)
        val target = dest.child(newName)
        forget(target)
        ids[target.absolute] = moved.documentId
        return target
    }

    override suspend fun delete(path: KPath) {
        val p = local(path)
        if (p == mount) throw StorageException.Unsupported(p, "the granted folder itself")
        saf.delete(require(p))
        forget(p)
    }
}
