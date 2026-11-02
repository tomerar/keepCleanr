// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.classify

import io.github.tomerar.keepclear.core.model.Category
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.engine.scan.ScanItem
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** CLS-7: the user's own "keep" and "always junk" choices, and their limits. */
class UserChoicesTest {
    private val root = "/storage/emulated/0"
    private val now = 1_800_000_000_000L
    private val old = now - 400L * 24 * 3_600_000

    private fun item(rel: String, mtime: Long = old) =
        ScanItem(KPath.Local("$root/$rel"), "$root/$rel", 1000, mtime)

    @Test
    fun `kept files and folders are never offered, whatever they look like`() {
        val c = Classifier(keptByUser = listOf("$root/Download/Tax", "$root/DCIM/.thumbnails/keep.jpg"))
        c.classify(item("Download/Tax/2023.zip"), now = now).category shouldBe Category.PROTECTED
        c.classify(item("Download/Tax/2023.zip"), now = now).ruleId shouldBe Rules.USER_KEPT
        // A thumbnail is normally SAFE junk; kept by the user it is not.
        c.classify(item("DCIM/.thumbnails/keep.jpg"), now = now).category shouldBe Category.PROTECTED
        c.classify(item("DCIM/.thumbnails/other.jpg"), now = now).category shouldBe Category.SAFE
        // A sibling whose name only starts like the kept folder is not kept.
        c.classify(item("Download/Taxes/x.zip"), now = now).ruleId shouldBe
            Classifier().classify(item("Download/Taxes/x.zip"), now = now).ruleId
    }

    @Test
    fun `always-junk folders are SAFE, but never override the built-in PROTECTED list`() {
        val wa = "Android/media/com.whatsapp/WhatsApp"
        val c = Classifier(junkFolders = listOf("$root/Download/Memes", "$root/$wa"))
        c.classify(item("Download/Memes/a.jpg"), now = now).category shouldBe Category.SAFE
        c.classify(item("Download/Memes/a.jpg"), now = now).ruleId shouldBe Rules.USER_JUNK
        // WhatsApp's chat database stays PROTECTED even inside an "always junk" folder.
        c.classify(item("$wa/Databases/msgstore.db.crypt14"), now = now).category shouldBe Category.PROTECTED
        // Recent files still need a look (B1).
        c.classify(item("Download/Memes/new.jpg", mtime = now - 1000), now = now).category shouldBe Category.REVIEW
    }

    @Test
    fun `keeping beats always-junk`() {
        val c =
            Classifier(
                keptByUser = listOf("$root/Download/Memes/best.jpg"),
                junkFolders = listOf("$root/Download/Memes"),
            )
        c.classify(item("Download/Memes/best.jpg"), now = now).category shouldBe Category.PROTECTED
    }

    @Test
    fun `always-junk never overrides MOVE, might-be-a-memory or chats (D6)`() {
        val c = Classifier(junkFolders = listOf("$root/Download/Old"))
        // A memory to back up stays MOVE, even inside an "always junk" folder.
        val waReceived = item("Download/Old/WhatsApp/Media/WhatsApp Images/IMG-1.jpg")
        c.classify(waReceived, now = now).category shouldBe Category.MOVE
        c.classify(waReceived, now = now).ruleId shouldBe "wa_received"
        // "Might be a memory" stays REVIEW: call recordings, AI images, big videos.
        c.classify(item("Download/Old/Call recordings/a.m4a"), now = now).ruleId shouldBe "call_recordings"
        c.classify(item("Download/Old/ChatGPT Image 1.png"), now = now).ruleId shouldBe "ai_images"
        val bigVideo = ScanItem(KPath.Local("$root/Download/Old/v.mp4"), "$root/Download/Old/v.mp4", 200_000_000, old)
        c.classify(bigVideo, now = now).category shouldBe Category.REVIEW
        // Chats stay PROTECTED.
        c.classify(item("Download/Old/WhatsApp/Databases/msgstore.db.crypt14"), now = now).category shouldBe
            Category.PROTECTED
        // Everything else in the folder is junk as asked.
        c.classify(item("Download/Old/notes.txt"), now = now).ruleId shouldBe Rules.USER_JUNK
    }

    @Test
    fun `messenger folders are never junk under any user, even when stored by an older version (D6)`() {
        val stored = listOf(
            "$root/Telegram/Telegram Images",
            "/storage/emulated/999/WhatsApp/Media",
            "$root/WhatsApp Business/Media",
        )
        val c = Classifier(junkFolders = stored)
        c.classify(item("Telegram/Telegram Images/a.jpg"), now = now).ruleId shouldBe "telegram_legacy"
        val clone = "/storage/emulated/999/WhatsApp/Media/WhatsApp Documents/a.pdf"
        c.classify(ScanItem(KPath.Local(clone), clone, 1000, old), now = now).category shouldBe Category.REVIEW
        c.classify(item("WhatsApp Business/Media/x/a.pdf"), now = now).category shouldBe Category.REVIEW
    }

    @Test
    fun `which folders may be always-junk (C12, D6)`() {
        listOf(
            "$root/Download/Memes",
            "/storage/emulated/999/Download/Memes",
            "/storage/1234-ABCD/Old/Junk",
        ).forEach { JunkFolders.canBeJunk(it) shouldBe true }
        listOf(
            "$root/Download",
            "$root/DCIM/Camera",
            "$root/Pictures/Screenshots",
            "$root/Android/media/com.whatsapp",
            "$root/WhatsApp/Media",
            "/storage/emulated/999/WhatsApp/Media",
            "$root/WhatsApp Business/Media",
            "/storage/emulated/10/WhatsApp Business/Media",
            "$root/Telegram/Telegram Images",
            "/storage/emulated/999/Telegram/Telegram Video",
            "$root/Download/Telegram",
            "$root/Download/Old/WhatsApp",
            "$root/KeepClear/Restored-changed",
            "$root/Download/KeepClear Restored",
            "$root/.KeepClearTrash/x",
            "/storage/emulated",
            "/data/data/x/y",
            "relative/path",
        ).forEach { JunkFolders.canBeJunk(it) shouldBe false }
    }
}
