// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.move

import io.github.tomerar.keepclear.core.storage.FakeStorageGateway.Op
import io.github.tomerar.keepclear.core.storage.StorageException
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/** Owner brief H1 item 10: README.html next to the manifest; never user data. */
class BackupReadmeTest {
    private val en = BackupReadme.Texts("en", "ltr", "KeepClear backup", "What", "Open", "Restore")
    private val he = BackupReadme.Texts("he", "rtl", "גיבוי של KeepClear", "מה", "לפתוח", "לשחזר")
    private val readmeRel = "KeepClear/OnePlus 10T/README.html"

    private fun Rig.readmeEngine() = MoveEngine(
        fake,
        target,
        store,
        clock = { now },
        bootId = { boot },
        attachGeneration = { attach.generation("USB-1") },
        readme = listOf(en, he),
    )

    @Test
    fun `written next to the manifest in both languages, self-contained`(): Unit = runBlocking {
        val rig = Rig()
        rig.readmeEngine().run(rig.newJob()).state shouldBe JobState.DONE
        val html = rig.fake.readAllBytes("$USB/$readmeRel").toString(Charsets.UTF_8)
        html shouldContain "KeepClear backup"
        html shouldContain "גיבוי של KeepClear"
        html shouldContain "dir=\"rtl\""
        html shouldContain "OnePlus 10T"
        html.lowercase().let {
            it shouldNotContain "<script"
            it shouldNotContain "http"
            it shouldNotContain "src="
            it shouldNotContain "<link"
            it shouldNotContain "<style"
        }
    }

    @Test
    fun `never a backup file - not in the manifest, the checksums or the receipt`(): Unit = runBlocking {
        val rig = Rig()
        rig.readmeEngine().run(rig.newJob())
        val dir = "$USB/KeepClear/OnePlus 10T"
        rig.fake.readAllBytes("$dir/${Manifest.JSONL}").toString(Charsets.UTF_8) shouldNotContain "README"
        rig.fake.readAllBytes("$dir/${Manifest.SUMS}").toString(Charsets.UTF_8) shouldNotContain "README"
        rig.store.files.values.none { it.relTarget.endsWith("README.html") } shouldBe true
        rig.readmeEngine().receipt("job1").verified shouldBe 6
        // The second check doesn't read it as a copy either.
        rig.reattach()
        rig.readmeEngine().confirm("job1", FreshRead.REATTACHED).ok shouldBe 6
    }

    @Test
    fun `overwritten on the next backup, nothing else of ours is deleted`(): Unit = runBlocking {
        val rig = Rig()
        rig.readmeEngine().run(rig.newJob("job1", rig.items.take(3)))
        rig.fake.modify(
            "$USB/$readmeRel",
            io.github.tomerar.keepclear.core.storage.FakeStorageGateway.Content.Bytes("old".toByteArray()),
        )
        val deleted = mutableListOf<String>()
        rig.fake.beforeOp = { op, p -> if (op == Op.DELETE) deleted += p.toString() }
        rig.readmeEngine().run(rig.newJob("job2", rig.items.drop(3)))
        rig.fake.readAllBytes("$USB/$readmeRel").toString(Charsets.UTF_8) shouldContain "KeepClear backup"
        // Only our own temp/old versions of the manifest, checksums and README are ever deleted.
        deleted.all { it.endsWith(".kc-old") || it.endsWith(".kc-tmp") } shouldBe true
        rig.fake.files(USB).count { "/Camera/" in it } shouldBe 6
    }

    @Test
    fun `a failure writing it never pauses or fails the backup`(): Unit = runBlocking {
        val rig = Rig()
        rig.fake.beforeOp = { op, p ->
            if (op == Op.RENAME && p.toString().contains("README")) {
                throw StorageException.Disconnected(p)
            }
        }
        rig.readmeEngine().run(rig.newJob()).state shouldBe JobState.DONE
        rig.fake.exists("$USB/$readmeRel") shouldBe false
    }

    @Test
    fun `no readme texts, no file`(): Unit = runBlocking {
        val rig = Rig()
        rig.engine().run(rig.newJob())
        rig.fake.exists("$USB/$readmeRel") shouldBe false
    }

    @Test
    fun `every text is escaped`() {
        val evil = BackupReadme.Texts("en\"><script>", "ltr", "<b>t</b>", "a & b", "'x'", "\"y\"")
        val html = BackupReadme.html("<script>alert(1)</script>\u0000", listOf(evil))
        html shouldNotContain "<script"
        html shouldNotContain "<b>"
        html shouldContain "&lt;script&gt;"
        html shouldContain "a &amp; b"
        html shouldContain "&#39;x&#39;"
        html shouldContain "&quot;y&quot;"
        html shouldNotContain "\u0000"
    }

    @Test
    fun `reserved names are never a backup file in a manifest`() {
        val sha = "a".repeat(64)

        fun line(rel: String) = """{"rel":"$rel","src":"/x","size":1,"mtime":1,"sha256":"$sha",""" +
            """"verifiedAt":1,"nameChanged":false}"""
        val root = "KeepClear/P"
        // Negative: reserved names (any case) at the device folder's top.
        listOf("README.html", "readme.HTML", "README.html.kc-old", "SHA256SUMS", Manifest.JSONL).forEach {
            Manifest.parseLine(line(it), root) shouldBe null
        }
        // Positive: a user's file of the same name inside a source folder is still a backup file.
        Manifest.parseLine(line("Camera/README.html"), root)?.rel shouldBe "Camera/README.html"
        Manifest.parseLine(line("Camera/2021/a.jpg"), root)?.rel shouldBe "Camera/2021/a.jpg"
    }
}
