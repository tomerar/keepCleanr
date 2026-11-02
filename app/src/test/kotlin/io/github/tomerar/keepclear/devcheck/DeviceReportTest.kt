// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.devcheck

import io.github.tomerar.keepclear.classifyRun
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.engine.classify.Classifier
import io.github.tomerar.keepclear.engine.scan.Scanner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The device-check report: every section, and never a path or a file name, whatever the facts hold. */
class DeviceReportTest {
    private val root = "/storage/emulated/0"

    /** Real-looking private names: none of them may ever reach the report. */
    private val privateNames = listOf(
        "divorce papers.pdf",
        "IMG_20240101_120000.jpg",
        "תמונה של אמא.jpg",
        "msgstore.db.crypt14",
        "Camera",
        "DCIM",
        "Secret Folder",
        "Download",
    )

    private fun tree() = ReadOnlyTreeGateway()
        .file("$root/DCIM/Camera/IMG_20240101_120000.jpg", 3_000_000)
        .file("$root/DCIM/Camera/VID_big.mp4", 5_000_000_000)
        .file("$root/Download/divorce papers.pdf", 120_000)
        .file("$root/Download/Secret Folder/a.tmp", 10)
        .file("$root/Pictures/תמונה של אמא.jpg", 2_000_000)
        .file("$root/Android/media/com.whatsapp/WhatsApp/Databases/msgstore.db.crypt14", 50_000)
        .file("$root/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/IMG-1.jpg", 80_000)
        .file("$root/.FileManagerRecycler/deleted_divorce papers.pdf", 4_000)
        .file("$root/.trashed-1700000000-IMG_9.jpg", 1_000)
        .file("$root/Android/data/com.example/files/x.bin", 99)
        .file("/storage/emulated/999/Android/media/com.whatsapp/WhatsApp/Media/a.jpg", 7)
        .link("$root/DCIM/link-to-elsewhere")
        .dir("/storage/emulated/10")

    /** Facts made from the probes over [tree], plus system strings that look like paths. */
    private fun realLookingFacts(): DeviceFacts = runBlocking {
        val gateway = tree()
        var tick = 0L
        val scan = ScanCheck.run(
            scanner = Scanner(gateway),
            roots = listOf(KPath.Local(root), KPath.Local("/storage/emulated/999")),
            primaryRoot = root,
            classify = { classifyRun(gateway, Classifier(), it, 1_800_000_000_000).first },
            clock = { tick.also { tick += 21_000 } },
            fat32Max = 4L * 1024 * 1024 * 1024 - 1,
        )
        val (listed, users) = CloneProbe.users(
            gateway,
            allFiles = true,
            exists = { it.endsWith("/999") || it.endsWith("/10") },
            canList = { it.endsWith("/999") },
        )
        DeviceFacts(
            takenAtMillis = 1_793_000_000_000,
            zone = "Asia/Jerusalem",
            app = AppFacts("0.0.1/debug", 1, "foss", "debug"),
            device = DeviceInfo(
                manufacturer = "OnePlus\u0000",
                model = "CPH2413/sdcard\u202e",
                osBuild = "CPH2413_14.0.0.700(EX01) /data/system",
                release = "15",
                sdk = 35,
                securityPatch = "2026-10-01",
                locale = "iw-IL",
                rtl = true,
            ),
            display = DisplayInfo(2.0f, 450, 360, 780, 360, night = true),
            access = AccessInfo(
                allFiles = true,
                media = "PARTIAL",
                notifications = false,
                usageAccess = true,
                grantedFolders = 1,
            ),
            scan = scan,
            volumes = listOf(
                VolumeFacts(
                    "internal",
                    true,
                    256_000_000_000,
                    16_800_000_000,
                    17_000_000_000,
                    256_000_000_000,
                    17_100_000_000,
                ),
                VolumeFacts("SD card 1", false),
                VolumeFacts("USB drive 1", true, 64_000_000_000, 60_000_000_000),
            ),
            clones = CloneFacts(
                myUser = 0,
                profiles = 2,
                listedUsers = listed,
                mainWhatsApp = WhatsAppFolders.measure(gateway, root),
                users = users,
            ),
            bins = OemBins.check(gateway, "internal", root),
            drives = listOf(DriveFacts("USB drive 1", "EXFAT", "exfat", null, 64_000_000_000, 60_000_000_000)),
            own = OwnState(
                testMode = true,
                trash = mapOf("ACTIVE" to Count(3, 9_000)),
                jobs = mapOf("DONE" to 1, "PAUSED" to 1),
                pauseReasons = mapOf("TARGET_DISCONNECTED" to 1),
                kept = 2,
                junkFolders = 1,
                networkDrive = true,
                reminder = false,
            ),
            failures = listOf(Failure("Storage", "java.io/IOException")),
        )
    }

    @Test
    fun `the report has every section, a header and the footer`() {
        val text = DeviceReport.render(realLookingFacts())
        listOf(
            "KeepClear device check",
            "App: ",
            "Taken: 2026-",
            "Device: OnePlus",
            "== Device (D0-1) ==",
            "== Display (D18-1, D0-1) ==",
            "== Permissions and access (D3-2, D12-1, D13-1) ==",
            "== Scan (D3-1) ==",
            "== Storage (D3-1) ==",
            "== Cloned apps (D3-3, D16a-1) ==",
            "== Other apps' recycle bins (D7-1) ==",
            "== Removable drives (D10-1) ==",
            "== KeepClear's own state ==",
            "== Could not check ==",
            "[PASS] Scan time: 21.0 s",
            "SAFE (ready to clean)",
            "REVIEW (look first)",
            "MOVE (memories, back up first)",
            "PROTECTED (never touched)",
            "Font scale: 2.00",
            "Window size class: compact",
            "Layout direction: right to left",
            "internal, OnePlus and OPPO file manager bin: present, 1 files, 4,000 bytes (4.0 KB)",
            "[PASS] User 999: present and readable (Home scans it)",
            "[WARN] User 10: present but not readable",
            "User 999, WhatsApp: 1 files, 7 bytes",
            "Main user, WhatsApp: 2 files",
            "USB drive 1: format EXFAT (mount type exfat)",
            "No 4 GB limit",
            "SD card 1: present, not mounted",
            "Files over 4 GB (too big for a FAT32 drive): 1 files",
            "In cloned-app storage: 1 files, 7 bytes",
            "Files named .trashed- (Android's trash, seen in folders): 1 files",
            "Backup jobs PAUSED: 1",
            "Trial mode: on",
        ).forEach { assertTrue("missing \"$it\" in:\n$text", it in text) }
        assertTrue(text.trimEnd().endsWith(DeviceReport.FOOTER))
    }

    @Test
    fun `the report never contains a path, a uri or a slash`() {
        val text = DeviceReport.render(realLookingFacts())
        listOf("/storage", "/sdcard", "/mnt", "/data", "/proc", "content://", "media_rw", "1234-ABCD", "emulated")
            .forEach { assertFalse("\"$it\" in:\n$text", it in text) }
        // No '/' at all: the report needs none (sizes, dates and levels are written without one).
        assertFalse(text, '/' in text)
        assertFalse(text, '\\' in text)
        assertTrue(text, text.none { it != '\n' && Character.isISOControl(it) })
        assertTrue(text, text.none { Character.getType(it) == Character.FORMAT.toInt() })
    }

    @Test
    fun `file and folder names from the phone never appear`() {
        val text = DeviceReport.render(realLookingFacts())
        privateNames.forEach { assertFalse("\"$it\" leaked into:\n$text", it in text) }
        assertFalse(text, "deleted_" in text)
        assertFalse(text, "IMG" in text)
    }

    @Test
    fun `system strings are cleaned`() {
        assertEquals("CPH2413 sdcard DCIM", ReportText.clean("CPH2413/sdcard/DCIM\u202e"))
        assertEquals("a b", ReportText.clean("a\u0000\n\tb"))
        assertEquals("unknown", ReportText.clean(" / "))
        assertEquals("unknown", ReportText.clean(null))
        assertEquals(64, ReportText.clean("x".repeat(500)).length)
        assertEquals("exfat", ReportText.token("ex/fat"))
        assertEquals("unknown", ReportText.token("///"))
        assertEquals("a b\nc", ReportText.finalPass("a/b\nc"))
    }

    @Test
    fun `no access, no drive and no bins are reported plainly`() {
        val f = realLookingFacts().copy(
            access = AccessInfo(
                allFiles = null,
                media = "NONE",
                notifications = true,
                usageAccess = false,
                grantedFolders = 0,
            ),
            scan = null,
            bins = emptyList(),
            drives = emptyList(),
            clones = null,
            own = null,
            failures = emptyList(),
        )
        val text = DeviceReport.render(f)
        assertTrue(text, "All files access: not in this build (play)" in text)
        assertTrue(text, "[WARN] Not run: KeepClear has no access" in text)
        assertTrue(text, "[INFO] No drive attached" in text)
        assertTrue(text, "Not checked (needs All files access)" in text)
        assertFalse(text, "Could not check ==" in text)
    }

    @Test
    fun `sizes are exact bytes plus a decimal size`() {
        assertEquals("999 bytes", DeviceReport.bytes(999))
        assertEquals("3,812,345,678 bytes (3.8 GB)", DeviceReport.bytes(3_812_345_678))
        assertEquals("12 files, 1,500 bytes (1.5 KB)", DeviceReport.count(Count(12, 1_500)))
    }

    @Test
    fun `a slow scan is a warning`() {
        val f = realLookingFacts()
        val text = DeviceReport.render(f.copy(scan = f.scan!!.copy(scanMillis = 75_000)))
        assertTrue(text, "[WARN] Scan time: 75.0 s" in text)
    }
}
