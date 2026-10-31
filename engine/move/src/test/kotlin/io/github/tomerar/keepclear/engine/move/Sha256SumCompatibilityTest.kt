// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.move

import io.github.tomerar.keepclear.backup.GatewayBackupTarget
import io.github.tomerar.keepclear.backup.TargetCaps
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.core.storage.file.FileGateway
import io.github.tomerar.keepclear.engine.scan.ScanItem
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** MOVE-8: a PC user can check the backup with `sha256sum -c SHA256SUMS` (Hebrew names included). */
class Sha256SumCompatibilityTest {
    @Test
    fun `sha256sum -c accepts our SHA256SUMS`(
        @TempDir dir: File,
    ): Unit = runBlocking {
        assumeTrue(runCatching { ProcessBuilder("sha256sum", "--version").start().waitFor() == 0 }.getOrDefault(false))
        val phone = File(dir, "phone").apply { mkdirs() }
        val usb = File(dir, "usb").apply { mkdirs() }
        val items = listOf("IMG_1.jpg", "תמונה של אמא 🎂.jpg", "with space.mp4").mapIndexed { i, name ->
            val f = File(phone, "DCIM/$name").apply {
                parentFile.mkdirs()
                writeBytes(ByteArray(3_000 + i) { (it + i).toByte() })
            }
            ScanItem(KPath.Local(f.canonicalPath), f.canonicalPath, f.length(), f.lastModified())
        }
        val gateway = FileGateway()
        val target =
            GatewayBackupTarget(gateway, KPath.Local(usb.canonicalPath), "usb", "USB", TargetCaps(canReadBack = true))
        val store = InMemoryMoveJobStore()
        store.putJob(MoveJob("j", "usb", "USB", MovePlanner.rootRel("Phone"), 0))
        MovePlanner.plan("j", items, "Phone", "Camera", target.caps).forEach { store.putFile(it) }
        MoveEngine(gateway, target, store).run("j").state shouldBe JobState.DONE
        val process = ProcessBuilder("sha256sum", "-c", "SHA256SUMS")
            .directory(File(usb, "KeepClear/Phone"))
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        process.waitFor() shouldBe 0
        output.lines().count { it.endsWith(": OK") } shouldBe 3
    }
}
