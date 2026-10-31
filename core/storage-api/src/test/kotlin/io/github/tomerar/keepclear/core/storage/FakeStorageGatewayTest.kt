// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.storage

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class FakeStorageGatewayTest {
    private val root = "/storage/emulated/0"

    @Test
    fun `virtual content is deterministic and sized`() {
        val a = FakeStorageGateway().apply { addFile("$root/a.mp4", size = 1_000_003, seed = 7) }
        val b = FakeStorageGateway().apply { addFile("$root/a.mp4", size = 1_000_003, seed = 7) }
        val c = FakeStorageGateway().apply { addFile("$root/a.mp4", size = 1_000_003, seed = 8) }
        a.readAllBytes("$root/a.mp4").size shouldBe 1_000_003
        a.readAllBytes("$root/a.mp4").contentEquals(b.readAllBytes("$root/a.mp4")) shouldBe true
        a.readAllBytes("$root/a.mp4").contentEquals(c.readAllBytes("$root/a.mp4")) shouldBe false
    }

    @Test
    fun `case-insensitive lookups find the real name`(): Unit = runBlocking {
        val fake = FakeStorageGateway(caseInsensitive = true)
        fake.addFile("$root/WhatsApp/Databases/msgstore.db.crypt14", size = 10)
        val stat = fake.stat(KPath.Local("$root/whatsapp/DATABASES/msgstore.db.crypt14"))!!
        stat.path shouldBe KPath.Local("$root/WhatsApp/Databases/msgstore.db.crypt14")
        shouldThrow<StorageException.AlreadyExists> { fake.create(KPath.Local("$root/WhatsApp"), "DATABASES") }
    }

    @Test
    fun `full volume raises NoSpace and leaves a partial file`(): Unit = runBlocking {
        val fake = FakeStorageGateway()
        fake.setCapacity(root, 100)
        val dir = fake.mkdirs("$root/d")
        shouldThrow<StorageException.NoSpace> { fake.create(dir, "big").use { it.write(ByteArray(150)) } }
        fake.contentOf("$root/d/big")!!.size shouldBe 100
        fake.freeBytes(root) shouldBe 0
    }

    @Test
    fun `read can fail after N bytes (cable unplugged)`(): Unit = runBlocking {
        val fake = FakeStorageGateway().apply { addFile("$root/v.mp4", size = 10_000) }
        fake.failAfterBytes(FakeStorageGateway.Op.READ, 4096)
        val input = fake.openRead(KPath.Local("$root/v.mp4"))
        val buffer = ByteArray(1024)
        var total = 0
        shouldThrow<StorageException.Disconnected> {
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
            }
        }
        total shouldBe 4096
    }

    @Test
    fun `write can fail after N bytes`(): Unit = runBlocking {
        val fake = FakeStorageGateway()
        val dir = fake.mkdirs("$root/d")
        fake.failAfterBytes(FakeStorageGateway.Op.WRITE, 10)
        shouldThrow<StorageException.Disconnected> { fake.create(dir, "f").use { it.write(ByteArray(64)) } }
        fake.contentOf("$root/d/f")!!.size shouldBe 10
    }

    @Test
    fun `disconnect and reconnect a volume`(): Unit = runBlocking {
        val sd = "/storage/1234-ABCD"
        val fake = FakeStorageGateway(volumeRoots = listOf(root, sd)).apply { addFile("$sd/a", size = 1) }
        fake.disconnect(sd)
        shouldThrow<StorageException.Disconnected> { fake.stat(KPath.Local("$sd/a")) }
        fake.stat(KPath.Local("$root")) shouldNotBe null
        fake.reconnect(sd)
        fake.stat(KPath.Local("$sd/a")) shouldNotBe null
    }

    @Test
    fun `revoked prefix raises PermissionDenied`(): Unit = runBlocking {
        val fake = FakeStorageGateway().apply { addFile("$root/DCIM/a.jpg", size = 1) }
        fake.revoke("$root/DCIM")
        shouldThrow<StorageException.PermissionDenied> { fake.openRead(KPath.Local("$root/DCIM/a.jpg")) }
        fake.restore("$root/DCIM")
        fake.openRead(KPath.Local("$root/DCIM/a.jpg")).close()
    }

    @Test
    fun `injected op failure fires the requested number of times`(): Unit = runBlocking {
        val fake = FakeStorageGateway().apply { addFile("$root/a", size = 1) }
        fake.fail(FakeStorageGateway.Op.DELETE, times = 2)
        repeat(2) { shouldThrow<StorageException.Io> { fake.delete(KPath.Local("$root/a")) } }
        fake.delete(KPath.Local("$root/a"))
        fake.exists("$root/a") shouldBe false
    }

    @Test
    fun `beforeOp can simulate a kill point`(): Unit = runBlocking {
        class Killed : RuntimeException()
        val fake = FakeStorageGateway().apply { addFile("$root/a", size = 1) }
        fake.beforeOp = { op, _ -> if (op == FakeStorageGateway.Op.RENAME) throw Killed() }
        shouldThrow<Killed> { fake.rename(KPath.Local("$root/a"), KPath.Local(root), "b") }
        fake.exists("$root/a") shouldBe true
        fake.exists("$root/b") shouldBe false
    }

    @Test
    fun `a file can change while it is being read`(): Unit = runBlocking {
        val fake = FakeStorageGateway().apply { addFile("$root/v.mp4", size = 50_000, mtime = 1) }
        fake.onReadProgress = { p, read ->
            if (read >= 8192 && fake.contentOf(p.toString())!!.size == 50_000L) {
                fake.modify(p.toString(), FakeStorageGateway.Content.Virtual(99, 60_000), mtime = 2)
            }
        }
        fake.openRead(KPath.Local("$root/v.mp4")).use { it.readBytes() }
        val after = fake.stat(KPath.Local("$root/v.mp4"))!!
        after.size shouldBe 60_000
        after.mtimeMillis shouldBe 2
    }

    @Test
    fun `moving a directory into itself is refused even with different case`(): Unit = runBlocking {
        val fake = FakeStorageGateway(caseInsensitive = true)
        fake.addFile("$root/A/f", size = 1)
        shouldThrow<StorageException.Unsupported> { fake.rename(KPath.Local("$root/A"), KPath.Local("$root/a"), "nested") }
        fake.exists("$root/A/f") shouldBe true
    }

    @Test
    fun `cross-volume rename is unsupported`(): Unit = runBlocking {
        val sd = "/storage/1234-ABCD"
        val fake = FakeStorageGateway(volumeRoots = listOf(root, sd)).apply { addFile("$root/a", size = 1) }
        shouldThrow<StorageException.Unsupported> { fake.rename(KPath.Local("$root/a"), KPath.Local(sd), "a") }
        fake.exists("$root/a") shouldBe true
    }

    @Test
    fun `list of a large directory streams every entry`(): Unit = runBlocking {
        val fake = FakeStorageGateway()
        repeat(100_000) { fake.addFile("$root/.thumbnails/t$it.jpg", size = 1) }
        fake.list(KPath.Local("$root/.thumbnails")).toList().size shouldBe 100_000
    }
}
