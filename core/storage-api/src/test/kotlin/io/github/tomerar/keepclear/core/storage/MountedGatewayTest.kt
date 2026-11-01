// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.storage

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/** M12b: paths inside a granted folder go to its gateway; nothing moves across the boundary. */
class MountedGatewayTest {
    private val phone = FakeStorageGateway()
    private val whatsapp = FakeStorageGateway()
    private val point = "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp"
    private var mounts = mapOf<String, StorageGateway>(point to whatsapp)
    private val gateway = MountedGateway(phone) { mounts }

    @Test
    fun `paths inside the folder use its gateway, others the base`(): Unit = runBlocking {
        whatsapp.addFile("$point/Media/a.jpg", ByteArray(3))
        phone.addFile("/storage/emulated/0/DCIM/b.jpg", ByteArray(4))
        gateway.stat(KPath.Local("$point/Media/a.jpg"))!!.size shouldBe 3
        gateway.stat(KPath.Local("/storage/emulated/0/DCIM/b.jpg"))!!.size shouldBe 4
        // A sibling whose name only starts like the mount point is not inside it.
        gateway.stat(KPath.Local("${point}X/c.jpg")) shouldBe null
    }

    @Test
    fun `nothing is moved into or out of a granted folder`(): Unit = runBlocking {
        whatsapp.addFile("$point/Media/a.jpg", ByteArray(3))
        phone.addFile("/storage/emulated/0/DCIM/b.jpg", ByteArray(4))
        shouldThrow<StorageException.Unsupported> {
            gateway.rename(KPath.Local("$point/Media/a.jpg"), KPath.Local("/storage/emulated/0/DCIM"), "a")
        }
        gateway.stat(KPath.Local("$point/Media/a.jpg")) shouldNotBe null
    }

    @Test
    fun `losing the grant takes effect at once`(): Unit = runBlocking {
        whatsapp.addFile("$point/Media/a.jpg", ByteArray(3))
        mounts = emptyMap()
        gateway.stat(KPath.Local("$point/Media/a.jpg")) shouldBe null
    }
}
