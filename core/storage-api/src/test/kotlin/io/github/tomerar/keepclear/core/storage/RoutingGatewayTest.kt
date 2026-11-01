// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.storage

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class RoutingGatewayTest {
    private val files = FakeStorageGateway().apply { addFile("/storage/emulated/0/a.jpg", size = 3) }

    @Test
    fun `file paths go to the file gateway, other kinds need their own backend`(): Unit = runBlocking {
        val router = RoutingGateway(files)
        router.stat(KPath.Local("/storage/emulated/0/a.jpg"))!!.size shouldBe 3
        shouldThrow<StorageException.Unsupported> { router.stat(KPath.Media("external_primary", 1)) }
        shouldThrow<StorageException.Unsupported> { router.stat(KPath.Saf("content://t", "d")) }
    }

    @Test
    fun `MediaStore rows go to the media backend`(): Unit = runBlocking {
        val row = KPath.Media("external_primary", 1)
        val media = object : StorageGateway by FakeStorageGateway() {
            override suspend fun stat(path: KPath) = Stat(path, "IMG.jpg", Stat.Kind.FILE, 7, 1)
        }
        val router = RoutingGateway(files, media = media)
        router.stat(row)!!.size shouldBe 7
        shouldThrow<StorageException.Unsupported> {
            router.rename(row, KPath.Local("/storage/emulated/0"), "x")
        }
    }
}
