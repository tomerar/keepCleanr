// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.backup

import io.github.tomerar.keepclear.core.storage.StorageException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * The behaviour every [BackupTarget] must have (M17: the same suite for the fake drive and each network target).
 * Subclass it per implementation; [newTarget] returns a target whose root is an empty, writable folder.
 */
abstract class BackupTargetContract {
    protected abstract fun newTarget(): BackupTarget

    private suspend fun BackupTarget.write(rel: String, bytes: ByteArray) = create(rel).use { it.write(bytes) }

    private suspend fun BackupTarget.read(rel: String): ByteArray = openRead(rel).use { it.readBytes() }

    private fun <T> withTarget(block: suspend (BackupTarget) -> T): T = runBlocking {
        val t = newTarget()
        try {
            block(t)
        } finally {
            t.close()
        }
    }

    @Test
    fun `a copy reads back byte-exact`(): Unit = withTarget { t ->
        val bytes = ByteArray(300_000) { (it * 31).toByte() }
        t.write("a.bin", bytes)
        t.read("a.bin") shouldBe bytes
        val stat = t.stat("a.bin")!!
        stat.size shouldBe bytes.size.toLong()
        stat.isFile shouldBe true
        stat.name shouldBe "a.bin"
    }

    @Test
    fun `an empty file is a file`(): Unit = withTarget { t ->
        t.write("empty", ByteArray(0))
        t.stat("empty")!!.size shouldBe 0L
        t.read("empty").size shouldBe 0
    }

    @Test
    fun `create never overwrites`(): Unit = withTarget { t ->
        t.write("a.txt", "one".toByteArray())
        shouldThrow<StorageException.AlreadyExists> { t.create("a.txt").close() }
        t.read("a.txt") shouldBe "one".toByteArray()
    }

    @Test
    fun `rename never overwrites and moves into a sub folder`(): Unit = withTarget { t ->
        t.write("a", byteArrayOf(1))
        t.write("b", byteArrayOf(2))
        shouldThrow<StorageException.AlreadyExists> { t.rename("a", "b") }
        t.read("b") shouldBe byteArrayOf(2)
        t.mkdirs("sub/deeper")
        t.rename("a", "sub/deeper/c")
        t.stat("a") shouldBe null
        t.read("sub/deeper/c") shouldBe byteArrayOf(1)
    }

    @Test
    fun `mkdirs makes every level, again is fine, never over a file`(): Unit = withTarget { t ->
        t.mkdirs("x/y/z")
        t.mkdirs("x/y/z")
        t.stat("x/y")!!.isDirectory shouldBe true
        t.write("x/f", byteArrayOf(1))
        shouldThrow<StorageException.NotADirectory> { t.mkdirs("x/f/g") }
    }

    @Test
    fun `list shows only direct children with their kind`(): Unit = withTarget { t ->
        t.mkdirs("d/inner")
        t.write("d/f1", byteArrayOf(1, 2, 3))
        t.write("d/inner/deep", byteArrayOf(1))
        val entries = t.list("d")
        entries.map { it.name } shouldContainExactlyInAnyOrder listOf("inner", "f1")
        entries.single { it.name == "inner" }.isDirectory shouldBe true
        entries.single { it.name == "f1" }.size shouldBe 3L
        t.list("d/inner").map { it.name } shouldBe listOf("deep")
        shouldThrow<StorageException.NotFound> { t.list("missing") }
    }

    @Test
    fun `delete removes files and empty folders, never full ones`(): Unit = withTarget { t ->
        assumeTrue(t.caps.canDeleteFolders, "this target never deletes folders")
        t.mkdirs("d")
        t.write("d/f", byteArrayOf(1))
        shouldThrow<StorageException.NotEmpty> { t.delete("d") }
        t.stat("d/f") shouldNotBe null
        t.delete("d/f")
        t.delete("d")
        t.list("").filter { it.name == "d" }.shouldBeEmpty()
        shouldThrow<StorageException.NotFound> { t.delete("d") }
    }

    @Test
    fun `a target that can't delete folders safely refuses every folder delete, empty or not`(): Unit =
        withTarget { t ->
            assumeFalse(t.caps.canDeleteFolders, "this target deletes empty folders")
            t.mkdirs("d/empty")
            t.write("d/f", byteArrayOf(1))
            shouldThrow<StorageException.Unsupported> { t.delete("d/empty") }
            shouldThrow<StorageException.Unsupported> { t.delete("d") }
            t.stat("d/empty")!!.isDirectory shouldBe true
            t.read("d/f") shouldBe byteArrayOf(1)
            t.delete("d/f")
            t.stat("d/f") shouldBe null
            shouldThrow<StorageException.NotFound> { t.delete("d/f") }
        }

    @Test
    fun `reading a missing file or a folder fails with a storage error`(): Unit = withTarget { t ->
        shouldThrow<StorageException.NotFound> { t.openRead("missing").close() }
        t.mkdirs("folder")
        shouldThrow<StorageException.IsADirectory> { t.openRead("folder").close() }
        t.stat("missing") shouldBe null
        t.stat("folder")!!.isDirectory shouldBe true
    }

    @Test
    fun `Hebrew, emoji and URL-special names round-trip`(): Unit = withTarget { t ->
        val names = listOf("תמונה של אמא 🎂.jpg", "a%20b #1?.txt", "50% & more;=+.png")
        t.mkdirs("n")
        names.forEachIndexed { i, name -> t.write("n/$name", byteArrayOf(i.toByte())) }
        t.list("n").map { it.name } shouldContainExactlyInAnyOrder names
        names.forEachIndexed { i, name -> t.read("n/$name") shouldBe byteArrayOf(i.toByte()) }
    }

    @Test
    fun `free space is unknown or not negative`(): Unit = withTarget { t ->
        val free = t.freeBytes()
        if (free != null) (free >= 0) shouldBe true
    }

    @Test
    fun `a server digest is only offered for kinds the target declares`(): Unit = withTarget { t ->
        t.write("f", byteArrayOf(1))
        DigestKind.entries.filter { it !in t.caps.serverDigests }.forEach { t.serverDigest("f", it) shouldBe null }
    }
}
