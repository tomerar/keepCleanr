// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.enrich

import io.github.tomerar.keepclear.core.storage.FakeStorageGateway
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.engine.classify.Fact
import io.github.tomerar.keepclear.engine.scan.ScanEvent
import io.github.tomerar.keepclear.engine.scan.ScanItem
import io.github.tomerar.keepclear.engine.scan.Scanner
import io.github.tomerar.keepclear.testing.fixtures.OwnerDevice
import io.github.tomerar.keepclear.testing.fixtures.OwnerDevice.Tag
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FactEnricherTest {
    private val root = OwnerDevice.ROOT

    private suspend fun scan(gateway: FakeStorageGateway): List<ScanItem> =
        Scanner(gateway).scan(listOf(KPath.Local(root))).filterIsInstance<ScanEvent.Batch>().toList().flatMap {
            it.items
        }

    @Test
    fun `owner fixture - every Sent duplicate and only those get a twin fact`(): Unit = runBlocking {
        val f = OwnerDevice.build()
        val facts = FactEnricher(f.gateway).facts(scan(f.gateway))
        val twins = facts.filterValues { Fact.HAS_TWIN_OUTSIDE_MESSENGER in it }.keys
        twins shouldBe f.paths(Tag.WA_SENT_DUPLICATE).toSet()
    }

    @Test
    fun `owner fixture - full and partial extraction`(): Unit = runBlocking {
        val f = OwnerDevice.build()
        val facts = FactEnricher(f.gateway).facts(scan(f.gateway))
        facts[f.paths(Tag.ZIP_FULLY_EXTRACTED).single()] shouldBe setOf(Fact.ARCHIVE_FULLY_EXTRACTED)
        facts[f.paths(Tag.ZIP_PARTIALLY_EXTRACTED).single()] shouldBe setOf(Fact.ARCHIVE_PARTIALLY_EXTRACTED)
    }

    @Test
    fun `same size but different bytes is not a twin (E-CLS-1)`(): Unit = runBlocking {
        val g = FakeStorageGateway(caseInsensitive = true)
        g.addFile("$root/DCIM/Camera/IMG_1.jpg", ByteArray(10_000) { 1 })
        val bytes = ByteArray(10_000) { 1 }.also { it[5_000] = 2 } // differs only in the middle: prehash equal
        g.addFile("$root/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/Sent/IMG_1.jpg", bytes)
        FactEnricher(g).facts(scan(g)) shouldBe emptyMap()
    }

    @Test
    fun `a twin inside another messenger folder does not count`(): Unit = runBlocking {
        val g = FakeStorageGateway(caseInsensitive = true)
        val bytes = ByteArray(1_000) { 9 }
        g.addFile("$root/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/IMG-received.jpg", bytes)
        g.addFile("$root/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/Sent/IMG-sent.jpg", bytes)
        g.addFile("$root/Pictures/Telegram/copy.jpg", bytes)
        FactEnricher(g).facts(scan(g)) shouldBe emptyMap()
    }

    @Test
    fun `empty files never count as twins`(): Unit = runBlocking {
        val g = FakeStorageGateway(caseInsensitive = true)
        g.addFile("$root/DCIM/empty.jpg", ByteArray(0))
        g.addFile("$root/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/Sent/empty.jpg", ByteArray(0))
        FactEnricher(g).facts(scan(g)) shouldBe emptyMap()
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z ->
            entries.forEach { (name, data) ->
                z.putNextEntry(ZipEntry(name))
                z.write(data)
                z.closeEntry()
            }
        }
    }.toByteArray()

    @Test
    fun `hostile archive names are never SAFE (SEC-2, E-SEC-4)`(): Unit = runBlocking {
        for (bad in listOf("../../DCIM/x.jpg", "/sdcard/x.jpg", "a/../../x", "C:/x", "a/./b", "a//b")) {
            val g = FakeStorageGateway(caseInsensitive = true)
            g.addFile("$root/Download/evil.zip", zip("ok.txt" to "a".toByteArray(), bad to "b".toByteArray()))
            g.addFile("$root/Download/evil/ok.txt", "a".toByteArray())
            g.addFile("$root/DCIM/x.jpg", "b".toByteArray())
            FactEnricher(g).facts(scan(g)) shouldBe emptyMap()
        }
    }

    @Test
    fun `changed extracted file (same size, different CRC) makes it partial`(): Unit = runBlocking {
        val g = FakeStorageGateway(caseInsensitive = true)
        g.addFile("$root/Download/a.zip", zip("x.txt" to "hello".toByteArray(), "y.txt" to "world".toByteArray()))
        g.addFile("$root/Download/a/x.txt", "hello".toByteArray())
        g.addFile("$root/Download/a/y.txt", "WORLD".toByteArray())
        FactEnricher(g).facts(scan(g))["$root/Download/a.zip"] shouldBe setOf(Fact.ARCHIVE_PARTIALLY_EXTRACTED)
    }

    @Test
    fun `renamed sibling folder means no fact`(): Unit = runBlocking {
        val g = FakeStorageGateway(caseInsensitive = true)
        g.addFile("$root/Download/a.zip", zip("x.txt" to "hello".toByteArray()))
        g.addFile("$root/Download/a (1)/x.txt", "hello".toByteArray())
        FactEnricher(g).facts(scan(g)) shouldBe emptyMap()
    }

    @Test
    fun `garbage zip is ignored`(): Unit = runBlocking {
        val g = FakeStorageGateway(caseInsensitive = true)
        g.addFile("$root/Download/a.zip", ByteArray(500) { 7 })
        g.addFile("$root/Download/a/x.txt", "hello".toByteArray())
        FactEnricher(g).facts(scan(g)) shouldBe emptyMap()
    }
}
