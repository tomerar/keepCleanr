// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.storage

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * The behaviour every [StorageGateway] must have (tasks.md M1). Subclass it per implementation:
 * the fake (JVM), `FileGateway` (M3), `SafGateway` (instrumented, M11).
 *
 * Tests only use the gateway API, so they also run against real storage.
 */
abstract class StorageGatewayContract {
    /** A gateway and an empty, writable directory to work in. */
    protected abstract fun newGateway(): Pair<StorageGateway, KPath>

    /** Creates a symlink [name] → [target] in [dir]; return false if the backend can't. */
    protected open fun createSymlink(gateway: StorageGateway, dir: KPath, name: String, target: String): Boolean = false

    /** Child path under [dir] (backend-specific for opaque paths). */
    protected open suspend fun child(gateway: StorageGateway, dir: KPath, name: String): KPath =
        gateway.list(dir).toList().single { it.name == name }.path

    private suspend fun StorageGateway.write(dir: KPath, name: String, bytes: ByteArray): KPath {
        create(dir, name).use { it.write(bytes) }
        return child(this, dir, name)
    }

    private suspend fun StorageGateway.read(path: KPath): ByteArray = openRead(path).use { it.readBytes() }

    @Test
    fun `create then read returns the same bytes`(): Unit = runBlocking {
        val (g, root) = newGateway()
        val bytes = ByteArray(100_000) { (it * 31).toByte() }
        val p = g.write(root, "a.bin", bytes)
        g.read(p) shouldBe bytes
        g.stat(p)!!.size shouldBe bytes.size.toLong()
        g.stat(p)!!.isFile shouldBe true
    }

    @Test
    fun `create never overwrites`(): Unit = runBlocking {
        val (g, root) = newGateway()
        val p = g.write(root, "a.txt", "one".toByteArray())
        shouldThrow<StorageException.AlreadyExists> { g.create(root, "a.txt") }
        g.read(p) shouldBe "one".toByteArray()
    }

    @Test
    fun `mkdir never overwrites`(): Unit = runBlocking {
        val (g, root) = newGateway()
        g.mkdir(root, "d")
        shouldThrow<StorageException.AlreadyExists> { g.mkdir(root, "d") }
    }

    @Test
    fun `list streams direct children with kinds and sizes`(): Unit = runBlocking {
        val (g, root) = newGateway()
        g.write(root, "f1", ByteArray(3))
        val d = g.mkdir(root, "sub")
        g.write(d, "nested", ByteArray(5))
        val entries = g.list(root).toList()
        entries.map { it.name to it.kind } shouldContainExactlyInAnyOrder
            listOf("f1" to Stat.Kind.FILE, "sub" to Stat.Kind.DIRECTORY)
        entries.single { it.name == "f1" }.size shouldBe 3
    }

    @Test
    fun `stat of a missing path is null`(): Unit = runBlocking {
        val (g, root) = newGateway()
        val p = g.write(root, "gone", ByteArray(1))
        g.delete(p)
        g.stat(p) shouldBe null
    }

    @Test
    fun `rename moves a file and keeps its content and identity`(): Unit = runBlocking {
        val (g, root) = newGateway()
        val p = g.write(root, "a", "x".toByteArray())
        val before = g.stat(p)!!
        val d = g.mkdir(root, "dest")
        val moved = g.rename(p, d, "b")
        g.stat(p) shouldBe null
        g.read(moved) shouldBe "x".toByteArray()
        if (Capability.STAT_IDENTITY in g.capabilities) g.stat(moved)!!.identity shouldBe before.identity
    }

    @Test
    fun `rename onto an existing name fails and changes nothing`(): Unit = runBlocking {
        val (g, root) = newGateway()
        val a = g.write(root, "a", "A".toByteArray())
        val b = g.write(root, "b", "B".toByteArray())
        shouldThrow<StorageException.AlreadyExists> { g.rename(a, root, "b") }
        g.read(a) shouldBe "A".toByteArray()
        g.read(b) shouldBe "B".toByteArray()
    }

    @Test
    fun `rename moves a whole directory`(): Unit = runBlocking {
        val (g, root) = newGateway()
        val d = g.mkdir(root, "d")
        val inner = g.mkdir(d, "inner")
        g.write(inner, "f", "deep".toByteArray())
        val target = g.mkdir(root, "target")
        val moved = g.rename(d, target, "d2")
        val movedInner = child(g, moved, "inner")
        g.read(child(g, movedInner, "f")) shouldBe "deep".toByteArray()
        g.list(root).toList().map { it.name } shouldContainExactlyInAnyOrder listOf("target")
    }

    @Test
    fun `delete removes files and empty directories but not full ones`(): Unit = runBlocking {
        val (g, root) = newGateway()
        val d = g.mkdir(root, "d")
        val f = g.write(d, "f", ByteArray(1))
        shouldThrow<StorageException.NotEmpty> { g.delete(d) }
        g.delete(f)
        g.delete(d)
        g.list(root).toList() shouldBe emptyList()
    }

    @Test
    fun `reading a directory fails`(): Unit = runBlocking {
        val (g, root) = newGateway()
        val d = g.mkdir(root, "d")
        shouldThrow<StorageException.IsADirectory> { g.openRead(d) }
    }

    @Test
    fun `missing paths raise NotFound`(): Unit = runBlocking {
        val (g, root) = newGateway()
        val p = g.write(root, "x", ByteArray(1))
        g.delete(p)
        shouldThrow<StorageException.NotFound> { g.openRead(p) }
        shouldThrow<StorageException.NotFound> { g.delete(p) }
        shouldThrow<StorageException.NotFound> { g.rename(p, root, "y") }
    }

    @Test
    fun `unicode names are stored byte-exact`(): Unit = runBlocking {
        val (g, root) = newGateway()
        val names = listOf(
            "תמונה של אמא 🎂.jpg",
            "été.txt", // NFD
            "été.txt", // NFC (different bytes, may coexist)
            "IMG_‏2021.jpg", // RLM inside
        )
        names.forEach { g.write(root, it, it.toByteArray()) }
        g.list(root).toList().map { it.name } shouldContainExactlyInAnyOrder names
        names.forEach { g.read(child(g, root, it)) shouldBe it.toByteArray() }
    }

    @Test
    fun `invalid names are rejected`(): Unit = runBlocking {
        val (g, root) = newGateway()
        for (bad in listOf("", ".", "..", "a/b", "nul\u0000")) {
            shouldThrow<StorageException> { g.create(root, bad) }
            shouldThrow<StorageException> { g.mkdir(root, bad) }
        }
    }

    @Test
    fun `writing updates mtime`(): Unit = runBlocking {
        val (g, root) = newGateway()
        if (Capability.STAT_MTIME !in g.capabilities) return@runBlocking
        val p = g.write(root, "f", ByteArray(1))
        g.stat(p)!!.mtimeMillis shouldNotBe 0L
    }

    @Test
    fun `symlinks are never followed by stat, rename or delete`(): Unit = runBlocking {
        val (g, root) = newGateway()
        val target = g.write(root, "real.txt", "real".toByteArray())
        if (!createSymlink(g, root, "link", (target as? KPath.Local)?.absolute ?: return@runBlocking)) return@runBlocking
        val link = child(g, root, "link")
        g.stat(link)!!.kind shouldBe Stat.Kind.SYMLINK
        val moved = g.rename(link, root, "link2")
        g.stat(moved)!!.kind shouldBe Stat.Kind.SYMLINK
        g.read(target) shouldBe "real".toByteArray()
        g.delete(moved)
        g.read(target) shouldBe "real".toByteArray()
    }
}
