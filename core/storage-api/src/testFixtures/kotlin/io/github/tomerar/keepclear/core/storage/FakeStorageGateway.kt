// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.storage

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.SplittableRandom

/**
 * In-memory [StorageGateway] for JVM tests, with fault injection (tasks.md M1).
 *
 * - Files can hold real bytes or **virtual** content (deterministic bytes from a seed), so owner-scale
 *   trees (tens of GB) cost almost no memory.
 * - [caseInsensitive] mimics Android's FUSE emulated storage.
 * - Volumes ([volumeRoots]) have capacities; cross-volume rename is [StorageException.Unsupported].
 * - Faults: [fail] (per op + path), [failAfterBytes] (mid-read/mid-write), [disconnect], [revoke],
 *   [beforeOp] (kill points / races), [onReadProgress] (file changes during read).
 */
class FakeStorageGateway(
    val caseInsensitive: Boolean = false,
    volumeRoots: List<String> = listOf("/storage/emulated/0"),
    private val clock: () -> Long = { 1_700_000_000_000L },
) : StorageGateway {
    enum class Op { LIST, STAT, OPEN_READ, READ, CREATE, WRITE, MKDIR, RENAME, DELETE }

    sealed interface Content {
        val size: Long

        data class Bytes(val bytes: ByteArray) : Content {
            override val size: Long get() = bytes.size.toLong()

            override fun equals(other: Any?) = other is Bytes && bytes.contentEquals(other.bytes)

            override fun hashCode() = bytes.contentHashCode()
        }

        /** Deterministic pseudo-random bytes; same (seed, size) ⇒ same content. */
        data class Virtual(val seed: Long, override val size: Long) : Content
    }

    private sealed class Node {
        abstract val name: String
        abstract var mtime: Long
        abstract val inode: Long

        class Dir(override val name: String, override var mtime: Long, override val inode: Long) : Node()

        class File(
            override val name: String,
            override var mtime: Long,
            override val inode: Long,
            var content: Content,
        ) : Node()

        class Link(override val name: String, override var mtime: Long, override val inode: Long, val target: String) :
            Node()
    }

    private class Fault(
        val op: Op,
        val matches: (KPath) -> Boolean,
        var remaining: Int,
        val afterBytes: Long,
        val error: (KPath) -> StorageException,
    )

    private val lock = Any()
    private val nodes = HashMap<String, Node>()
    private val children = HashMap<String, LinkedHashMap<String, String>>()
    private val volumes = volumeRoots.associateWith { Volume(it) }.toMutableMap()
    private val faults = mutableListOf<Fault>()
    private val disconnected = mutableSetOf<String>()
    private val revoked = mutableSetOf<String>()
    private var nextInode = 1L

    /** Called before every operation (outside the lock); throw to simulate a crash/kill point. */
    var beforeOp: ((Op, KPath) -> Unit)? = null

    /** Called while reading, with bytes read so far (outside the lock); use to mutate files mid-read. */
    var onReadProgress: ((KPath, Long) -> Unit)? = null

    private class Volume(val root: String) {
        var capacity: Long = Long.MAX_VALUE
        var used: Long = 0
    }

    init {
        nodes[key("/")] = Node.Dir("", clock(), nextInode++)
        volumeRoots.forEach { mkdirs(it) }
    }

    override val capabilities: Set<Capability> = Capability.entries.toSet()

    // ---------------------------------------------------------------- test setup API

    fun setCapacity(volumeRoot: String, bytes: Long) = synchronized(lock) {
        volume(volumeRoot).capacity = bytes
    }

    fun freeBytes(volumeRoot: String): Long = synchronized(lock) {
        volume(volumeRoot).let { it.capacity - it.used }
    }

    fun mkdirs(path: String, mtime: Long = clock()): KPath.Local = synchronized(lock) {
        val p = KPath.Local(path)
        var current = KPath.Local("/")
        for (segment in path.split('/').filter { it.isNotEmpty() }) {
            val next = current.child(segment)
            when (val existing = nodes[key(next.absolute)]) {
                null -> insert(current, Node.Dir(segment, mtime, nextInode++))
                is Node.Dir -> Unit
                else -> error("Not a directory: $next (${existing::class.simpleName})")
            }
            current = next
        }
        p
    }

    fun addFile(path: String, size: Long, mtime: Long = clock(), seed: Long = path.hashCode().toLong()): KPath.Local =
        addFile(path, Content.Virtual(seed, size), mtime)

    fun addFile(path: String, bytes: ByteArray, mtime: Long = clock()): KPath.Local =
        addFile(path, Content.Bytes(bytes), mtime)

    fun addFile(path: String, content: Content, mtime: Long = clock()): KPath.Local = synchronized(lock) {
        val p = KPath.Local(path)
        val parent = mkdirs(p.parent!!.absolute)
        check(nodes[key(path)] == null) { "Exists: $path" }
        volumeOf(path)?.let { it.used += content.size }
        insert(parent, Node.File(p.name, mtime, nextInode++, content))
        p
    }

    fun addSymlink(path: String, target: String, mtime: Long = clock()): KPath.Local = synchronized(lock) {
        val p = KPath.Local(path)
        val parent = mkdirs(p.parent!!.absolute)
        check(nodes[key(path)] == null) { "Exists: $path" }
        insert(parent, Node.Link(p.name, mtime, nextInode++, target))
        p
    }

    /** Replaces the content of an existing file (simulates another app editing it). */
    fun modify(path: String, content: Content, mtime: Long = clock()) = synchronized(lock) {
        val node = nodes[key(path)] as? Node.File ?: error("Not a file: $path")
        volumeOf(path)?.let { it.used += content.size - node.content.size }
        node.content = content
        node.mtime = mtime
    }

    fun setMtime(path: String, mtime: Long) = synchronized(lock) { nodes.getValue(key(path)).mtime = mtime }

    fun exists(path: String): Boolean = synchronized(lock) { nodes.containsKey(key(path)) }

    fun contentOf(path: String): Content? = synchronized(lock) { (nodes[key(path)] as? Node.File)?.content }

    fun readAllBytes(path: String): ByteArray = (contentOf(path) ?: error("Not a file: $path")).let(::materialize)

    /** All file paths (sorted), optionally under [prefix]. */
    fun files(prefix: String = "/"): List<String> = synchronized(lock) {
        nodes.entries.filter { it.value is Node.File }.map { pathOf(it.key) }
            .filter { prefix == "/" || it == prefix || it.startsWith("$prefix/") }.sorted()
    }

    fun fail(
        op: Op,
        times: Int = 1,
        matches: (KPath) -> Boolean = { true },
        error: (KPath) -> StorageException = { StorageException.Io(it) },
    ) = synchronized(lock) { faults += Fault(op, matches, times, -1, error) }

    /** Fails a READ or WRITE once more than [bytes] have been transferred on a matching stream. */
    fun failAfterBytes(
        op: Op,
        bytes: Long,
        matches: (KPath) -> Boolean = { true },
        error: (KPath) -> StorageException = { StorageException.Disconnected(it) },
    ) {
        require(op == Op.READ || op == Op.WRITE)
        synchronized(lock) { faults += Fault(op, matches, 1, bytes, error) }
    }

    fun clearFaults() = synchronized(lock) { faults.clear() }

    fun disconnect(volumeRoot: String) = synchronized(lock) { disconnected += volumeRoot }

    fun reconnect(volumeRoot: String) = synchronized(lock) { disconnected -= volumeRoot }

    /** Every op under [prefix] fails with PermissionDenied until [restore]. */
    fun revoke(prefix: String) = synchronized(lock) { revoked += prefix }

    fun restore(prefix: String) = synchronized(lock) { revoked -= prefix }

    // ---------------------------------------------------------------- StorageGateway

    override fun list(dir: KPath): Flow<Stat> = flow {
        val p = local(dir)
        guard(Op.LIST, p)
        val names = synchronized(lock) {
            val node = nodes[key(p.absolute)] ?: throw StorageException.NotFound(p)
            if (node !is Node.Dir) throw StorageException.NotADirectory(p)
            children[key(p.absolute)]?.values?.toList().orEmpty()
        }
        for (name in names) {
            val stat = synchronized(lock) { nodes[key(p.child(name).absolute)]?.let { statOf(p.child(name), it) } }
            if (stat != null) emit(stat)
        }
    }

    override suspend fun stat(path: KPath): Stat? {
        val p = local(path)
        guard(Op.STAT, p)
        return synchronized(lock) { nodes[key(p.absolute)]?.let { statOf(KPath.Local(pathOf(key(p.absolute))), it) } }
    }

    override suspend fun openRead(path: KPath): InputStream {
        val p = local(path)
        guard(Op.OPEN_READ, p)
        val content = synchronized(lock) {
            // Never follow symlinks (StorageGateway contract), same as FileGateway.
            when (val node = nodes[key(p.absolute)]) {
                null -> throw StorageException.NotFound(p)
                is Node.Dir -> throw StorageException.IsADirectory(p)
                is Node.File -> node.content
                is Node.Link -> throw StorageException.Unsupported(p, "symlink")
            }
        }
        val readFault = synchronized(lock) { takeFault(Op.READ, p) }
        return FaultyInputStream(p, content, readFault)
    }

    override suspend fun create(dir: KPath, name: String): OutputStream {
        val d = local(dir)
        guard(Op.CREATE, d)
        if (!KPath.isValidName(name)) throw StorageException.Io(d, message = "Invalid name '$name'")
        val target = d.child(name)
        synchronized(lock) {
            val parent = nodes[key(d.absolute)] ?: throw StorageException.NotFound(d)
            if (parent !is Node.Dir) throw StorageException.NotADirectory(d)
            if (nodes.containsKey(key(target.absolute))) throw StorageException.AlreadyExists(target)
            insert(d, Node.File(name, clock(), nextInode++, Content.Bytes(ByteArray(0))))
        }
        val writeFault = synchronized(lock) { takeFault(Op.WRITE, target) }
        return FakeOutputStream(target, writeFault)
    }

    override suspend fun mkdir(dir: KPath, name: String): KPath {
        val d = local(dir)
        guard(Op.MKDIR, d)
        if (!KPath.isValidName(name)) throw StorageException.Io(d, message = "Invalid name '$name'")
        val target = d.child(name)
        synchronized(lock) {
            val parent = nodes[key(d.absolute)] ?: throw StorageException.NotFound(d)
            if (parent !is Node.Dir) throw StorageException.NotADirectory(d)
            if (nodes.containsKey(key(target.absolute))) throw StorageException.AlreadyExists(target)
            insert(d, Node.Dir(name, clock(), nextInode++))
        }
        return target
    }

    override suspend fun rename(path: KPath, newParent: KPath, newName: String): KPath {
        val from = local(path)
        val toDir = local(newParent)
        guard(Op.RENAME, from)
        guard(Op.RENAME, toDir)
        if (!KPath.isValidName(newName)) throw StorageException.Io(toDir, message = "Invalid name '$newName'")
        val to = toDir.child(newName)
        synchronized(lock) {
            val node = nodes[key(from.absolute)] ?: throw StorageException.NotFound(from)
            val parent = nodes[key(toDir.absolute)] ?: throw StorageException.NotFound(toDir)
            if (parent !is Node.Dir) throw StorageException.NotADirectory(toDir)
            if (volumeOf(from.absolute)?.root != volumeOf(to.absolute)?.root) {
                throw StorageException.Unsupported(from, "cross-volume rename")
            }
            val fromKey = key(from.absolute)
            val toKey = key(to.absolute)
            if (node is Node.Dir && (toKey == fromKey || toKey.startsWith("$fromKey/"))) {
                throw StorageException.Unsupported(from, "move into itself")
            }
            if (nodes.containsKey(key(to.absolute))) throw StorageException.AlreadyExists(to)
            moveSubtree(from, to, node)
        }
        return to
    }

    override suspend fun delete(path: KPath) {
        val p = local(path)
        guard(Op.DELETE, p)
        synchronized(lock) {
            val node = nodes[key(p.absolute)] ?: throw StorageException.NotFound(p)
            if (node is Node.Dir && children[key(p.absolute)].orEmpty().isNotEmpty()) {
                throw StorageException.NotEmpty(p)
            }
            if (node is Node.File) volumeOf(p.absolute)?.let { it.used -= node.content.size }
            removeEntry(p)
        }
    }

    // ---------------------------------------------------------------- internals

    private fun local(path: KPath): KPath.Local =
        path as? KPath.Local ?: throw StorageException.Unsupported(path, "FakeStorageGateway handles Local paths")

    private fun key(path: String) = if (caseInsensitive) path.lowercase() else path

    private fun pathOf(key: String): String {
        if (key == "/") return "/"
        // Rebuild the real-cased path from stored names.
        val segments = key.split('/').filter { it.isNotEmpty() }
        val sb = StringBuilder()
        var currentKey = ""
        for (segment in segments) {
            val parentKey = currentKey.ifEmpty { "/" }
            currentKey += "/$segment"
            val realName = children[parentKey]?.get(currentKey) ?: segment
            sb.append('/').append(realName)
        }
        return sb.toString()
    }

    private fun insert(parent: KPath.Local, node: Node) {
        val childPath = parent.child(node.name).absolute
        nodes[key(childPath)] = node
        children.getOrPut(key(parent.absolute)) { LinkedHashMap() }[key(childPath)] = node.name
        nodes[key(parent.absolute)]?.mtime = clock()
    }

    private fun removeEntry(p: KPath.Local) {
        nodes.remove(key(p.absolute))
        children.remove(key(p.absolute))
        p.parent?.let { parent ->
            children[key(parent.absolute)]?.remove(key(p.absolute))
            nodes[key(parent.absolute)]?.mtime = clock()
        }
    }

    private fun moveSubtree(from: KPath.Local, to: KPath.Local, node: Node) {
        // Collect the subtree (parents first), detach it deepest-first, then re-insert under [to].
        val entries = mutableListOf<Pair<String, Node>>()

        fun collect(p: KPath.Local, rel: String, n: Node) {
            entries += rel to n
            children[key(p.absolute)]?.values?.toList().orEmpty().forEach { childName ->
                val child = p.child(childName)
                collect(child, if (rel.isEmpty()) childName else "$rel/$childName", nodes.getValue(key(child.absolute)))
            }
        }
        collect(from, "", node)

        fun under(base: KPath.Local, rel: String) = if (rel.isEmpty()) base else KPath.Local("${base.absolute}/$rel")
        entries.asReversed().forEach { (rel, _) -> removeEntry(under(from, rel)) }
        entries.forEach { (rel, n) ->
            val target = under(to, rel)
            val copy = when (n) {
                is Node.Dir -> Node.Dir(target.name, n.mtime, n.inode)
                is Node.File -> Node.File(target.name, n.mtime, n.inode, n.content)
                is Node.Link -> Node.Link(target.name, n.mtime, n.inode, n.target)
            }
            insert(target.parent!!, copy)
        }
    }

    private fun statOf(path: KPath.Local, node: Node): Stat = when (node) {
        is Node.Dir -> Stat(path, node.name, Stat.Kind.DIRECTORY, 0, node.mtime, "inode:${node.inode}")

        is Node.File -> Stat(path, node.name, Stat.Kind.FILE, node.content.size, node.mtime, "inode:${node.inode}")

        is Node.Link -> Stat(
            path,
            node.name,
            Stat.Kind.SYMLINK,
            node.target.length.toLong(),
            node.mtime,
            "inode:${node.inode}",
        )
    }

    private fun volume(root: String) = volumes[root] ?: error("Unknown volume $root")

    private fun volumeOf(path: String): Volume? =
        volumes.values.filter { path == it.root || path.startsWith(it.root + "/") }.maxByOrNull { it.root.length }

    private fun guard(op: Op, p: KPath.Local) {
        beforeOp?.invoke(op, p)
        synchronized(lock) {
            volumeOf(p.absolute)?.let { if (it.root in disconnected) throw StorageException.Disconnected(p) }
            if (revoked.any { p.absolute == it || p.absolute.startsWith("$it/") }) {
                throw StorageException.PermissionDenied(p)
            }
            takeFault(op, p)?.let { throw it.error(p) }
        }
    }

    /** Takes (and consumes) a matching fault. For READ/WRITE byte-faults returns it without throwing. */
    private fun takeFault(op: Op, p: KPath): Fault? {
        val fault = faults.firstOrNull { it.op == op && it.remaining > 0 && it.matches(p) } ?: return null
        fault.remaining--
        if (fault.remaining == 0) faults.remove(fault)
        return fault
    }

    private fun materialize(content: Content): ByteArray = when (content) {
        is Content.Bytes -> content.bytes.copyOf()
        is Content.Virtual -> VirtualStream(content).readBytes()
    }

    private class VirtualStream(content: Content.Virtual) : InputStream() {
        private val random = SplittableRandom(content.seed)
        private var remaining = content.size
        private val buffer = ByteArray(8192)
        private var pos = 0
        private var limit = 0

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            if (pos == limit) {
                val n = minOf(buffer.size.toLong(), remaining).toInt()
                random.nextBytes(buffer)
                pos = 0
                limit = n
            }
            val n = minOf(len, limit - pos)
            System.arraycopy(buffer, pos, b, off, n)
            pos += n
            remaining -= n
            return n
        }
    }

    private inner class FaultyInputStream(
        private val path: KPath.Local,
        content: Content,
        private val fault: Fault?,
    ) : InputStream() {
        private val delegate: InputStream = when (content) {
            is Content.Bytes -> content.bytes.inputStream()
            is Content.Virtual -> VirtualStream(content)
        }
        private var read = 0L

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (fault != null && read >= fault.afterBytes) throw fault.error(path)
            synchronized(lock) {
                volumeOf(path.absolute)?.let { if (it.root in disconnected) throw StorageException.Disconnected(path) }
            }
            val allowed = if (fault !=
                null
            ) {
                minOf(len.toLong(), fault.afterBytes - read).toInt().coerceAtLeast(1)
            } else {
                len
            }
            val n = delegate.read(b, off, allowed)
            if (n > 0) {
                read += n
                onReadProgress?.invoke(path, read)
            }
            return n
        }
    }

    private inner class FakeOutputStream(private val path: KPath.Local, private val fault: Fault?) : OutputStream() {
        private val buffer = ByteArrayOutputStream()
        private var closed = false

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            check(!closed) { "closed" }
            if (fault != null && buffer.size() + len > fault.afterBytes) {
                val fit = (fault.afterBytes - buffer.size()).coerceAtLeast(0).toInt()
                commit(b, off, fit)
                throw fault.error(path)
            }
            synchronized(lock) {
                val vol = volumeOf(path.absolute)
                if (vol != null) {
                    if (vol.root in disconnected) throw StorageException.Disconnected(path)
                    if (vol.used + len > vol.capacity) {
                        commit(b, off, (vol.capacity - vol.used).coerceAtLeast(0).toInt())
                        throw StorageException.NoSpace(path)
                    }
                }
            }
            commit(b, off, len)
        }

        private fun commit(b: ByteArray, off: Int, len: Int) {
            if (len <= 0) return
            buffer.write(b, off, len)
            synchronized(lock) {
                val node = nodes[key(path.absolute)] as? Node.File ?: return
                volumeOf(path.absolute)?.let { it.used += len }
                node.content = Content.Bytes(buffer.toByteArray())
                node.mtime = clock()
            }
        }

        override fun close() {
            closed = true
        }
    }
}
