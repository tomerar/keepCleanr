// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.scan

import io.github.tomerar.keepclear.core.storage.FakeStorageGateway
import io.github.tomerar.keepclear.core.storage.FakeStorageGateway.Op
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.core.storage.StorageException
import io.github.tomerar.keepclear.testing.fixtures.OwnerDevice
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class ScannerTest {
    private val root = KPath.Local(OwnerDevice.ROOT)
    private val clone = KPath.Local(OwnerDevice.CLONE_ROOT)

    private suspend fun Scanner.items(vararg roots: KPath.Local) =
        scan(roots.toList()).filterIsInstance<ScanEvent.Batch>().toList().flatMap { it.items }

    private suspend fun Scanner.done(vararg roots: KPath.Local) =
        scan(roots.toList()).toList().last() as ScanEvent.Done

    @Test
    fun `scans every file of the owner fixture exactly once`(): Unit = runBlocking {
        val f = OwnerDevice.build()
        val items = Scanner(f.gateway).items(root, clone)
        items.map { it.path }.sorted() shouldBe f.gateway.files()
        items.sumOf { it.size } shouldBe f.gateway.files().sumOf { f.gateway.contentOf(it)!!.size }
        Scanner(f.gateway).done(root, clone).complete shouldBe true
    }

    @Test
    fun `symlink loop is not followed and is counted`(): Unit = runBlocking {
        val f = OwnerDevice.build()
        val done = Scanner(f.gateway).done(root)
        done.skippedSymlinks shouldBe 1
    }

    @Test
    fun `Android data and obb are skipped without Shizuku`(): Unit = runBlocking {
        val f = OwnerDevice.build()
        f.gateway.addFile("${OwnerDevice.ROOT}/Android/data/org.telegram.messenger/files/cache.bin", size = 10)
        f.gateway.addFile("${OwnerDevice.ROOT}/Android/obb/com.game/main.obb", size = 10)
        val scanner = Scanner(f.gateway)
        scanner.items(root).none { "/Android/data/" in it.path || "/Android/obb/" in it.path } shouldBe true
        scanner.done(root).skippedRestricted shouldContainAll
            listOf("${OwnerDevice.ROOT}/Android/data", "${OwnerDevice.ROOT}/Android/obb")
        Scanner(f.gateway, readRestrictedAppDirs = true).items(root).any { "/Android/data/" in it.path } shouldBe true
    }

    @Test
    fun `hebrew, NFD and RLM names come through unchanged`(): Unit = runBlocking {
        val f = OwnerDevice.build()
        Scanner(f.gateway).items(root).map { it.path } shouldContainAll f.paths(OwnerDevice.Tag.UNICODE_NAME)
    }

    @Test
    fun `100k-entry directory streams in bounded batches`(): Unit = runBlocking {
        val f = OwnerDevice.build(hugeDirEntries = 100_000)
        val batches = Scanner(
            f.gateway,
            batchSize = 500,
        ).scan(listOf(root)).filterIsInstance<ScanEvent.Batch>().toList()
        batches.sumOf { it.items.size } shouldBe f.gateway.files(OwnerDevice.ROOT).size
        batches.maxOf { it.items.size } shouldBeLessThanOrEqual 500
    }

    @Test
    fun `scanning only reads, and cancelling leaves nothing behind (SCAN-3)`(): Unit = runBlocking {
        val f = OwnerDevice.build()
        val ops = mutableSetOf<Op>()
        f.gateway.beforeOp = { op, _ -> ops += op }
        val before = f.gateway.files()
        Scanner(f.gateway, batchSize = 10).scan(listOf(root)).take(3).toList()
        Scanner(f.gateway).items(root)
        ops shouldBe setOf(Op.LIST)
        f.gateway.files() shouldBe before
    }

    @Test
    fun `unreadable directory is reported and the scan continues (E-STO-6)`(): Unit = runBlocking {
        val f = OwnerDevice.build()
        f.gateway.fail(Op.LIST, matches = { it.toString().endsWith("/MIUI/ShareMe") })
        val events = Scanner(f.gateway).scan(listOf(root)).toList()
        events.filterIsInstance<ScanEvent.Unreadable>().single().path shouldBe "${OwnerDevice.ROOT}/MIUI/ShareMe"
        (events.last() as ScanEvent.Done).complete shouldBe true
        events.filterIsInstance<ScanEvent.Batch>().flatMap { it.items }.none { "/MIUI/ShareMe/" in it.path } shouldBe
            true
    }

    @Test
    fun `permission revoked mid-scan ends it as incomplete (E-PERM-1)`(): Unit = runBlocking {
        val f = OwnerDevice.build()
        f.gateway.fail(Op.LIST, matches = {
            it.toString().endsWith("/DCIM/Camera")
        }) { StorageException.PermissionDenied(it) }
        val done = Scanner(f.gateway).scan(listOf(root)).toList().last()
        done.shouldBeInstanceOf<ScanEvent.Done>().complete shouldBe false
    }

    @Test
    fun `unreadable root makes the scan incomplete but other roots are scanned`(): Unit = runBlocking {
        val f = OwnerDevice.build()
        f.gateway.revoke(OwnerDevice.CLONE_ROOT)
        val events = Scanner(f.gateway).scan(listOf(clone, root)).toList()
        (events.last() as ScanEvent.Done).complete shouldBe false
        events.filterIsInstance<ScanEvent.Batch>().flatMap { it.items }.map { it.path } shouldContainAll
            f.paths(OwnerDevice.Tag.SCREENSHOT)
    }

    @Test
    fun `lost volume is reported and other roots are still scanned (E-STO-1)`(): Unit = runBlocking {
        val f = OwnerDevice.build()
        f.gateway.disconnect(OwnerDevice.CLONE_ROOT)
        val events = Scanner(f.gateway).scan(listOf(clone, root)).toList()
        events.filterIsInstance<ScanEvent.VolumeLost>().single().root shouldBe OwnerDevice.CLONE_ROOT
        events.filterIsInstance<ScanEvent.Batch>().flatMap { it.items }.map { it.path } shouldContainAll
            f.paths(OwnerDevice.Tag.SCREENSHOT)
    }

    @Test
    fun `MediaStore rows enrich walked files`(): Unit = runBlocking {
        val f = OwnerDevice.build()
        val photo = f.paths(OwnerDevice.Tag.CAMERA).first()
        val media = MediaSource { listOf(MediaRow(7, "external_primary", photo, 1, 1, "image/jpeg", true, false, 42)) }
        val items = Scanner(f.gateway, media).items(root)
        val item = items.single { it.path == photo }
        item.mediaId shouldBe 7
        item.isFavorite shouldBe true
        item.mime shouldBe "image/jpeg"
        Scanner(f.gateway, media).done(root).maxGeneration shouldBe 42
    }

    @Test
    fun `MediaStore-only mode yields media items (no file access)`(): Unit = runBlocking {
        val fake = FakeStorageGateway()
        val rows = listOf(
            MediaRow(
                1,
                "external_primary",
                "${OwnerDevice.ROOT}/DCIM/Camera/a.jpg",
                10,
                1,
                "image/jpeg",
                false,
                false,
                5,
            ),
            MediaRow(
                2,
                "external_primary",
                "${OwnerDevice.ROOT}/DCIM/.trashed-1700000000-b.jpg",
                20,
                1,
                "image/jpeg",
                false,
                true,
                6,
            ),
        )
        val items = Scanner(fake, MediaSource { rows }).items()
        items.map { it.ref } shouldBe listOf(KPath.Media("external_primary", 1), KPath.Media("external_primary", 2))
        items.map { it.source }.toSet() shouldBe setOf(Source.MEDIA_STORE)
        items.single { it.mediaId == 2L }.isTrashed shouldBe true
    }
}

class ScanDiffTest {
    private fun item(path: String, size: Long = 1, mtime: Long = 1, id: String? = "i") =
        ScanItem(KPath.Local(path), path, size, mtime, id)

    @Test
    fun `added, changed, removed and unchanged`() {
        val previous = listOf(item("/a"), item("/b"), item("/c"), item("/d")).associateBy { it.path }
        val current = listOf(item("/a"), item("/b", size = 2), item("/c", id = "other"), item("/e"))
        val diff = ScanDiff.compute(previous, current)
        diff.added.map { it.path } shouldBe listOf("/e")
        diff.changed.map { it.path } shouldBe listOf("/b", "/c")
        diff.removed shouldBe listOf("/d")
        diff.unchanged shouldBe 1
    }
}

class SpaceModeTest {
    @Test
    fun thresholds() {
        SpaceMode.of(40_000_000) shouldBe SpaceMode.MINIMAL
        SpaceMode.of(99_999_999) shouldBe SpaceMode.MINIMAL
        SpaceMode.of(100_000_000) shouldBe SpaceMode.LIGHT
        SpaceMode.of(499_999_999) shouldBe SpaceMode.LIGHT
        SpaceMode.of(500_000_000) shouldBe SpaceMode.NORMAL
        SpaceMode.MINIMAL.persistScanResults shouldBe false
        SpaceMode.LIGHT.persistScanResults shouldBe false
        SpaceMode.NORMAL.persistScanResults shouldBe true
        SpaceMode.MINIMAL.canStartMove shouldBe false
        SpaceMode.LIGHT.diskThumbnails shouldBe false
        listOf(SpaceMode.NORMAL, SpaceMode.LIGHT) shouldContain SpaceMode.of(1L shl 40)
    }
}
