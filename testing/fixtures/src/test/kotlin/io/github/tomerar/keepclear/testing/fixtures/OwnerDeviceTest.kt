// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.testing.fixtures

import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.core.storage.Stat
import io.github.tomerar.keepclear.testing.fixtures.OwnerDevice.Tag
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.zip.ZipInputStream

class OwnerDeviceTest {
    private fun snapshot(f: OwnerDevice.Fixture) = f.gateway.files().map { p ->
        val c = f.gateway.contentOf(p)!!
        Triple(p, c.size, c)
    }

    @Test
    fun `same seed gives an identical tree`() {
        snapshot(OwnerDevice.build(seed = 7)) shouldBe snapshot(OwnerDevice.build(seed = 7))
    }

    @Test
    fun `different seed gives a different tree`() {
        (snapshot(OwnerDevice.build(seed = 7)) == snapshot(OwnerDevice.build(seed = 8))) shouldBe false
    }

    @Test
    fun `every area is present`() {
        val f = OwnerDevice.build()
        Tag.entries.filter { it != Tag.HUGE_DIR_ENTRY }.forEach { f.paths(it).shouldNotBeEmpty() }
    }

    @Test
    fun `protected chat databases exist in every WhatsApp variant`() {
        val f = OwnerDevice.build()
        listOf(Tag.WA_DB, Tag.WA_BACKUP, Tag.W4B_DB, Tag.CLONE_DB, Tag.LEGACY_WA_DB).forEach { tag ->
            f.paths(tag).forEach { f.gateway.exists(it) shouldBe true }
        }
        f.protectedPaths.any { it.endsWith("msgstore.db.crypt14") } shouldBe true
    }

    @Test
    fun `sent duplicates are byte-identical to a camera original`() {
        val f = OwnerDevice.build()
        val cameraContents = f.paths(Tag.CAMERA).map { f.gateway.contentOf(it) }.toSet()
        f.paths(Tag.WA_SENT_DUPLICATE).forEach { (f.gateway.contentOf(it) in cameraContents) shouldBe true }
    }

    @Test
    fun `full scale matches the owner's measured counts`() {
        val f = OwnerDevice.build(scale = 1.0)
        f.paths(Tag.SCREENSHOT).size shouldBe 2_324
        f.paths(Tag.AI_IMAGE).size shouldBe 526
        f.paths(Tag.CAMERA_BIG_VIDEO).size shouldBe 81
        f.paths(Tag.CAMERA_BIG_VIDEO).forEach { f.gateway.contentOf(it)!!.size shouldNotBe 0L }
    }

    @Test
    fun `zips are real archives`() {
        val f = OwnerDevice.build()
        val names = ZipInputStream(f.gateway.readAllBytes(f.paths(Tag.ZIP_FULLY_EXTRACTED).single()).inputStream())
            .use { zip -> generateSequence { zip.nextEntry }.map { it.name }.toList() }
        names.size shouldBe 5
    }

    @Test
    fun `symlink loop is a symlink, not a directory`(): Unit = runBlocking {
        val f = OwnerDevice.build()
        f.gateway.stat(KPath.Local(f.paths(Tag.SYMLINK).single()))!!.kind shouldBe Stat.Kind.SYMLINK
    }

    @Test
    fun `huge directory can be requested`(): Unit = runBlocking {
        val f = OwnerDevice.build(hugeDirEntries = 100_000)
        f.gateway.list(KPath.Local("${OwnerDevice.ROOT}/Pictures/.thumbnails")).toList().size shouldBe 100_000
    }

    @Test
    fun `case-insensitive like Android emulated storage`(): Unit = runBlocking {
        val f = OwnerDevice.build()
        f.gateway.stat(KPath.Local("${OwnerDevice.WA.lowercase()}/databases/msgstore.db.crypt14")) shouldNotBe null
    }
}
