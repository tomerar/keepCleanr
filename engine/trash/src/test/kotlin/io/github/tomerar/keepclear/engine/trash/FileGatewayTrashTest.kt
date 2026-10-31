// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.trash

import io.github.tomerar.keepclear.core.safety.OpKind
import io.github.tomerar.keepclear.core.safety.PlannedOp
import io.github.tomerar.keepclear.core.safety.ProtectedPaths
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.core.storage.file.FileGateway
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The trash on a real file system (Hebrew names, nested folders). */
class FileGatewayTrashTest {
    @Test
    fun `trash and restore on disk`(
        @TempDir dir: File,
    ): Unit = runBlocking {
        val volume = dir.canonicalPath
        val photo = File(volume, "DCIM/תמונה של אמא 🎂.jpg").apply {
            parentFile.mkdirs()
            writeBytes(ByteArray(1234) { 7 })
        }
        val gateway = FileGateway()
        val index = InMemoryTrashIndex()
        val trash = TrashManager(gateway, index, FakeSigner(), FakeClock(), ProtectedPaths(), listOf(volume))
        val s = gateway.stat(KPath.Local(photo.path))!!
        trash.perform(PlannedOp(s.path, photo.path, OpKind.TRASH, false, s.size, s.mtimeMillis, s.identity), gateway)
        photo.exists() shouldBe false
        File(volume, ".KeepClearTrash/.nomedia").exists() shouldBe true
        val entry = index.all().single()
        trash.perform(
            PlannedOp(KPath.Local(entry.trashedPath), entry.trashedPath, OpKind.RESTORE, false, 0, 0),
            gateway,
        )
        photo.readBytes().size shouldBe 1234
        index.all().size shouldBe 0
    }
}
