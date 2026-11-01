// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.move

import io.github.tomerar.keepclear.backup.BackupTarget
import io.github.tomerar.keepclear.backup.TargetCaps
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.core.storage.Stat
import io.github.tomerar.keepclear.core.storage.StorageException
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * A counterfeit USB stick (H1): it reports plenty of free space but really stores only [realBytes]; writes past
 * that wrap around and silently overwrite older data. Like a real phone, reads of recently written files are
 * served from the page cache (the bytes as written) until [eject] drops it; after that, reads come from the
 * medium and show the damage.
 */
class FakeCapacityDrive(
    private val realBytes: Int,
    override val id: String = "fake-usb",
    network: Boolean = false,
) : BackupTarget {
    override val label = "Fake 2 TB stick"
    override val caps = TargetCaps(canReadBack = true, readsFromServer = network)
    private val medium = ByteArray(realBytes)
    private var head = 0
    private val files = LinkedHashMap<String, Pair<Int, Int>>() // rel -> (offset, size)
    private val dirs = mutableSetOf("")
    private val cache = HashMap<String, ByteArray>()

    /** Unplug and plug back in: the page cache is gone, reads hit the medium. */
    fun eject() = cache.clear()

    override suspend fun freeBytes(): Long = 2_000_000_000_000L

    override suspend fun mkdirs(rel: String) {
        var path = ""
        rel.split('/').filter { it.isNotEmpty() }.forEach {
            path = if (path.isEmpty()) it else "$path/$it"
            dirs += path
        }
    }

    override suspend fun create(rel: String): OutputStream {
        if (rel in files) throw StorageException.AlreadyExists(KPath.Local("/fake/$rel"))
        files[rel] = head to 0
        return object : OutputStream() {
            private val buffer = ByteArrayOutputStream()

            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

            override fun write(b: ByteArray, off: Int, len: Int) = buffer.write(b, off, len)

            override fun close() {
                val bytes = buffer.toByteArray()
                val offset = head
                bytes.forEachIndexed { i, byte -> medium[(offset + i) % realBytes] = byte }
                head = (offset + bytes.size) % realBytes
                files[rel] = offset to bytes.size
                cache[rel] = bytes
            }
        }
    }

    override suspend fun rename(fromRel: String, toRel: String) {
        if (toRel in files) throw StorageException.AlreadyExists(KPath.Local("/fake/$toRel"))
        files[toRel] = files.remove(fromRel) ?: throw StorageException.NotFound(KPath.Local("/fake/$fromRel"))
        cache.remove(fromRel)?.let { cache[toRel] = it }
    }

    override suspend fun stat(rel: String): Stat? {
        val f =
            files[rel]
                ?: return if (rel in dirs) Stat(KPath.Local("/fake/$rel"), rel, Stat.Kind.DIRECTORY, 0, 0) else null
        return Stat(KPath.Local("/fake/$rel"), rel.substringAfterLast('/'), Stat.Kind.FILE, f.second.toLong(), 0)
    }

    override suspend fun openRead(rel: String): InputStream {
        cache[rel]?.let { return it.inputStream() }
        val (offset, size) = files[rel] ?: throw StorageException.NotFound(KPath.Local("/fake/$rel"))
        return ByteArray(size) { medium[(offset + it) % realBytes] }.inputStream()
    }

    override suspend fun delete(rel: String) {
        files.remove(rel)
        cache.remove(rel)
    }

    override suspend fun list(relDir: String): List<Stat> =
        files.keys.filter { it.substringBeforeLast('/', "") == relDir }.mapNotNull { stat(it) }
}
