// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.move

import io.github.tomerar.keepclear.backup.TargetCaps
import io.github.tomerar.keepclear.backup.TargetNames
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.engine.scan.ScanItem
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class MovePlannerTest {
    private fun item(name: String, size: Long = 10, mtime: Long = 1_678_000_000_000) =
        ScanItem(KPath.Local("$PHONE/DCIM/Camera/$name"), "$PHONE/DCIM/Camera/$name", size, mtime)

    private val caps = TargetCaps(canReadBack = true)

    @Test
    fun `layout is KeepClear, device, source, year, month (MOVE-7)`() {
        val f = MovePlanner.plan("j", listOf(item("IMG_1.jpg")), "OnePlus 10T", "Camera", caps).single()
        f.relTarget shouldBe "KeepClear/OnePlus 10T/Camera/2023/03/IMG_1.jpg"
        f.nameChanged shouldBe false
    }

    @Test
    fun `case-insensitive collisions get a suffix (E-MOVE-9)`() {
        val files = MovePlanner.plan(
            "j",
            listOf(item("IMG.jpg"), item("img.JPG"), item("IMG.jpg", mtime = 1_678_000_000_001)),
            "d",
            "Camera",
            caps,
        )
        files.map { it.relTarget.substringAfterLast('/') } shouldBe listOf("IMG.jpg", "img (2).JPG", "IMG (3).jpg")
        files.drop(1).all { it.nameChanged } shouldBe true
    }

    @Test
    fun `illegal and reserved names are sanitised (E-MOVE-11)`() {
        TargetNames.sanitize("a:b?c*.jpg") shouldBe "a_b_c_.jpg"
        TargetNames.sanitize("CON.txt") shouldBe "_CON.txt"
        TargetNames.sanitize("trailing. ") shouldBe "trailing"
        TargetNames.sanitize("tab\tname") shouldBe "tab_name"
    }

    @Test
    fun `long names are shortened but keep their extension (E-MOVE-12)`() {
        val long = "א".repeat(200) + ".jpg"
        val fitted = TargetNames.sanitize(long)
        (fitted.toByteArray().size <= 255) shouldBe true
        fitted.endsWith(".jpg") shouldBe true
    }

    @Test
    fun `NFD names become NFC on the target (E-MOVE-13)`() {
        TargetNames.sanitize("été.jpg") shouldBe "été.jpg"
    }

    @Test
    fun `preflight finds space, FAT32, same-drive, battery and read-back issues (MOVE-5)`(): Unit = runBlocking {
        val rig = Rig(caps = TargetCaps(canReadBack = false, maxFileSize = 10_002))
        val files = MovePlanner.plan("j", rig.items, "d", "Camera", rig.target.caps)
        val issues = MovePlanner.preflight(
            files,
            object : io.github.tomerar.keepclear.backup.BackupTarget by rig.target {
                override val volumeId = "usb-1"

                override suspend fun freeBytes() = 10L
            },
            sourceVolumeIds = setOf("usb-1"),
            batteryPercent = 10,
            charging = false,
        )
        issues shouldContain MovePlanner.Issue.TargetIsSource
        issues shouldContain MovePlanner.Issue.BatteryLow
        issues shouldContain MovePlanner.Issue.CannotVerify
        issues.filterIsInstance<MovePlanner.Issue.NotEnoughSpace>().single()
        issues.filterIsInstance<MovePlanner.Issue.TooLargeForTarget>().single().files.size shouldBe 3
        MovePlanner.preflight(files, rig.target, emptySet(), 100, false).filter { it.blocking } shouldBe emptyList()
        issues.first { it is MovePlanner.Issue.TargetIsSource }.shouldBeInstanceOf<MovePlanner.Issue>()
    }

    @Test
    fun `a backup folder on the same card as the photos is blocked, from the file paths alone (H3)`(): Unit =
        runBlocking {
            val rig = Rig()
            val onCard = rig.items.map {
                it.copy(path = it.path.replace("/storage/emulated/0", "/storage/1234-ABCD"))
            }
            val files = MovePlanner.plan("j", onCard, "d", "Camera", rig.target.caps)
            val sameCard = object : io.github.tomerar.keepclear.backup.BackupTarget by rig.target {
                override val volumeId = "1234-ABCD"
            }
            MovePlanner.preflight(files, sameCard, emptySet(), 100, true) shouldContain MovePlanner.Issue.TargetIsSource
            val phoneFiles = MovePlanner.plan("j", rig.items, "d", "Camera", rig.target.caps)
            MovePlanner.preflight(phoneFiles, sameCard, emptySet(), 100, true).filter { it.blocking } shouldBe
                emptyList()
            val phoneTarget = object : io.github.tomerar.keepclear.backup.BackupTarget by rig.target {
                override val volumeId = "primary"
            }
            MovePlanner.preflight(phoneFiles, phoneTarget, emptySet(), 100, true) shouldContain
                MovePlanner.Issue.TargetIsSource
        }

    @Test
    fun `target folders use ASCII digits on every device locale`() {
        val saved = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar-EG"))
            val item = ScanItem(
                KPath.Local("/storage/emulated/0/DCIM/Camera/a.jpg"),
                "/storage/emulated/0/DCIM/Camera/a.jpg",
                1,
                1_610_000_000_000L,
            )
            val file = MovePlanner.plan("j", listOf(item), "Phone", "Camera", TargetCaps(canReadBack = true)).single()
            file.relTarget shouldBe "KeepClear/Phone/Camera/2021/01/a.jpg"
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }

    @Test
    fun `collision suffixes keep names within 255 bytes and the path limit`() {
        val long = "א".repeat(125) + ".jpg" // 254 bytes in UTF-8
        (TargetNames.withSuffix(long, 2).toByteArray(Charsets.UTF_8).size <= 255) shouldBe true
        TargetNames.withSuffix(long, 2).endsWith(" (2).jpg") shouldBe true
        val files = MovePlanner.plan(
            "j",
            listOf(item(long, mtime = 1_678_000_000_000), item(long.uppercase(), mtime = 1_678_000_100_000)),
            "Phone",
            "Camera",
            caps,
        )
        files.forEach { (it.relTarget.substringAfterLast('/').toByteArray(Charsets.UTF_8).size <= 255) shouldBe true }
        files.map { it.relTarget }.toSet().size shouldBe 2
        val smb = TargetCaps(canReadBack = true, maxPathChars = 60)
        val short = MovePlanner.plan(
            "j",
            listOf(item("a".repeat(40) + ".jpg"), item("A".repeat(40) + ".jpg", mtime = 1_678_000_100_000)),
            "Phone",
            "Camera",
            smb,
        )
        short.forEach { (it.relTarget.length <= 60) shouldBe true }
    }
}
