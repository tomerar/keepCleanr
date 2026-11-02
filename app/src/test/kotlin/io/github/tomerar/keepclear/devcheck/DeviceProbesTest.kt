// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.devcheck

import io.github.tomerar.keepclear.ClonedApps
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.engine.classify.ClassificationSummary
import io.github.tomerar.keepclear.engine.classify.Classifier
import io.github.tomerar.keepclear.engine.scan.Scanner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The device check's read-only probes, on an in-memory tree that fails any write. */
class DeviceProbesTest {
    private val root = "/storage/emulated/0"

    @Test
    fun `recycle bins are found ignoring case, counted once, and absent ones say so`(): Unit = runBlocking {
        val g = ReadOnlyTreeGateway()
            .file("$root/.filemanagerrecycler/a.jpg", 100)
            .file("$root/.filemanagerrecycler/sub/b.jpg", 50)
            .file("$root/.trash/c.mp4", 7)
            .file("$root/MIUI/Gallery/cloud/.trashBin/d.jpg", 3)
            .link("$root/.FilesByGoogleTrash")
        val bins = OemBins.check(g, "internal", root).associateBy { it.bin }
        assertEquals(
            BinFacts("internal", "OnePlus and OPPO file manager bin", BinState.PRESENT, Count(2, 150)),
            bins.getValue("OnePlus and OPPO file manager bin"),
        )
        assertEquals(Count(1, 7), bins.getValue("Generic bin (.Trash)").count)
        assertEquals(Count(1, 3), bins.getValue("Xiaomi Gallery bin").count)
        // A link is never followed (SCAN-6): the Files by Google bin here is a link, so it isn't a bin.
        assertEquals(BinState.ABSENT, bins.getValue("Files by Google bin").state)
        assertEquals(BinState.ABSENT, bins.getValue("Samsung My Files and Gallery bin (unverified)").state)
        assertTrue(g.writes.isEmpty())
    }

    @Test
    fun `a bin found under two spellings is counted once`(): Unit = runBlocking {
        val g = ReadOnlyTreeGateway().file("$root/.Trash/a", 10)
        val specs = listOf(BinSpec("first", listOf(".Trash")), BinSpec("second", listOf(".TRASH")))
        val bins = OemBins.check(g, "internal", root, specs)
        assertEquals(listOf(BinState.PRESENT, BinState.ABSENT), bins.map { it.state })
    }

    @Test
    fun `an unreadable bin is reported, not counted`(): Unit = runBlocking {
        val g = ReadOnlyTreeGateway().file("$root/.FileManagerRecycler/a", 10).deny("$root/.FileManagerRecycler")
        val bin = OemBins.check(g, "internal", root).first()
        assertEquals(BinState.UNREADABLE, bin.state)
        assertEquals(Count(), bin.count)
    }

    @Test
    fun `counting stops at the cap and skips links`(): Unit = runBlocking {
        val g = ReadOnlyTreeGateway().link("$root/x/link")
        repeat(10) { g.file("$root/x/f$it", 1) }
        val capped = Tree.count(g, KPath.Local("$root/x"), cap = 4)
        assertTrue(capped.capped)
        assertEquals(4L, capped.count.files)
        assertEquals(Count(10, 10), Tree.count(g, KPath.Local("$root/x")).count)
    }

    @Test
    fun `whatsapp folders are measured by label`(): Unit = runBlocking {
        val g = ReadOnlyTreeGateway()
            .file("$root/Android/media/com.whatsapp/WhatsApp/Media/a.jpg", 10)
            .file("$root/Android/media/com.whatsapp/WhatsApp/Media/b.opus", 5)
            .file("$root/WhatsApp/Media/old.jpg", 1)
        assertEquals(
            mapOf("WhatsApp" to Count(2, 15), "WhatsApp (old folder)" to Count(1, 1)),
            WhatsAppFolders.measure(g, root),
        )
    }

    @Test
    fun `cloned users agree with ClonedApps for 999 and list other numeric users`(): Unit = runBlocking {
        val g = ReadOnlyTreeGateway()
            .dir("/storage/emulated/0")
            .dir("/storage/emulated/11")
            .file("/storage/emulated/999/Android/media/com.whatsapp/WhatsApp/Media/a.jpg", 4)
        val present = setOf("/storage/emulated/999", "/storage/emulated/11", "/storage/emulated/95")
        val readable = setOf("/storage/emulated/999", "/storage/emulated/11")
        val (listed, users) = CloneProbe.users(g, true, { it in present }, { it in readable })
        assertTrue(listed)
        val byId = users.associateBy { it.id }
        assertEquals(listOf(10, 11, 95, 150, 999), users.map { it.id })
        assertEquals(CloneState.NOT_VISIBLE, byId.getValue(10).state)
        assertEquals(CloneState.READABLE, byId.getValue(11).state)
        assertFalse("Home only scans 999", byId.getValue(11).scannedByHome)
        assertEquals(CloneState.HIDDEN, byId.getValue(95).state)
        val clone = byId.getValue(999)
        assertEquals(CloneState.READABLE, clone.state)
        assertTrue(clone.scannedByHome)
        assertEquals(mapOf("WhatsApp" to Count(1, 4)), clone.whatsApp)
        // Same answer as Home's probe.
        val home = ClonedApps.probe(true, { it in present }, { it in readable })
        assertEquals(home.readable.contains(ClonedApps.root(999)), clone.scannedByHome)
    }

    @Test
    fun `a hidden 999 is what makes Home show the cloned apps card`(): Unit = runBlocking {
        val g = ReadOnlyTreeGateway().dir("/storage/emulated/0").deny("/storage/emulated/999")
        val (_, users) = CloneProbe.users(g, true, { it.endsWith("/999") }, { false })
        assertEquals(CloneState.HIDDEN, users.single { it.id == 999 }.state)
        assertTrue(ClonedApps.probe(true, { it.endsWith("/999") }, { false }).hidden)
    }

    @Test
    fun `without all files access nothing is claimed about clones`(): Unit = runBlocking {
        val (listed, users) = CloneProbe.users(ReadOnlyTreeGateway(), false, { true }, { true })
        assertFalse(listed)
        assertTrue(users.all { it.state == CloneState.NOT_CHECKED })
    }

    @Test
    fun `the scan check times the scan, sorts like Home and splits the totals`(): Unit = runBlocking {
        val g = ReadOnlyTreeGateway()
            .file("$root/DCIM/.thumbnails/1.jpg", 10)
            .file("$root/DCIM/Camera/IMG_1.jpg", 1_000)
            .file("$root/Download/x.tmp", 5)
            .file("$root/.trashed-1-a.jpg", 2)
            .file("$root/Android/data/com.x/cache/big", 9_999)
            .file("/storage/9C33-6BBD/DCIM/v.mp4", 5_000_000_000)
        val clock = generateSequence(0L) { it + 500 }.iterator()
        val classifier = Classifier()
        val facts = ScanCheck.run(
            Scanner(g),
            listOf(KPath.Local(root), KPath.Local("/storage/9C33-6BBD")),
            root,
            { items -> ClassificationSummary.of(items.map { classifier.classify(it, now = 1_800_000_000_000) }) },
            { clock.next() },
            4L * 1024 * 1024 * 1024 - 1,
        )
        assertEquals(500L, facts.scanMillis)
        assertEquals(500L, facts.classifyMillis)
        assertEquals(Count(5, 5_000_001_017), facts.total)
        assertEquals("the walk skips other apps' private folders", 1, facts.skippedRestricted)
        assertEquals(Count(4, 1_017), facts.primary)
        assertEquals(Count(1, 2), facts.trashedNamed)
        assertEquals(Count(1, 5_000_000_000), facts.over4GiB)
        assertEquals(facts.total, facts.categories.values.fold(Count()) { a, b -> a + b })
        assertTrue(facts.categories.getValue("SAFE").files >= 2)
        assertEquals(0, facts.unreadable)
        assertTrue(facts.complete)
        assertTrue(g.writes.isEmpty())
    }

    @Test
    fun `an unreadable root makes the scan incomplete`(): Unit = runBlocking {
        val g = ReadOnlyTreeGateway().file("$root/a.jpg", 1).deny("/storage/AAAA-BBBB").dir("/storage/AAAA-BBBB")
        val facts = ScanCheck.run(
            Scanner(g),
            listOf(KPath.Local(root), KPath.Local("/storage/AAAA-BBBB")),
            root,
            { ClassificationSummary.of(emptyList()) },
            { 0L },
            Long.MAX_VALUE,
        )
        assertEquals(1, facts.unreadable)
        assertFalse(facts.complete)
        assertEquals(Count(1, 1), facts.total)
    }

    @Test
    fun `the mount type is read only for the drive's own line`() {
        val mounts = """
            /dev/block/vold/public:8,1 /mnt/media_rw/9C33-6BBD exfat rw,nosuid 0 0
            /mnt/media_rw/9C33-6BBD /storage/9C33-6BBD fuse rw 0 0
            /dev/block/vold/public:8,2 /mnt/media_rw/1111-2222 sdfat rw 0 0
        """.trimIndent()
        assertEquals("exfat", MountTable.typeOf("9C33-6BBD", mounts))
        assertEquals("sdfat", MountTable.typeOf("1111-2222", mounts))
        assertEquals(null, MountTable.typeOf("FFFF-0000", mounts))
        assertEquals(null, MountTable.typeOf("../x", mounts))
        assertEquals(null, MountTable.typeOf("9C33-6BBD", null))
    }

    @Test
    fun `volumes get fixed labels`() {
        val labels = VolumeLabels()
        assertEquals("internal", labels.next(primary = true, removable = false, usbHint = false))
        assertEquals("SD card 1", labels.next(primary = false, removable = true, usbHint = false))
        assertEquals("USB drive 1", labels.next(primary = false, removable = true, usbHint = true))
        assertEquals("USB drive 2", labels.next(primary = false, removable = true, usbHint = true))
        assertEquals("other volume 1", labels.next(primary = false, removable = false, usbHint = false))
    }
}
