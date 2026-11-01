// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.classify

import io.github.tomerar.keepclear.core.model.Category
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.engine.scan.ScanItem
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Table tests (CLS-1): positive and negative paths per rule, incl. E-CLS-1…7. */
class RuleTableTest {
    private val now = 1_800_000_000_000L
    private val old = now - 400L * 24 * 3_600_000
    private val root = "/storage/emulated/0"
    private val classifier = Classifier()

    private fun c(
        rel: String,
        size: Long = 1000,
        mtime: Long = old,
        facts: Set<Fact> = emptySet(),
        favorite: Boolean = false,
        trashed: Boolean = false,
        mime: String? = null,
    ) = classifier.classify(
        ScanItem(
            KPath.Local("$root/$rel"),
            "$root/$rel",
            size,
            mtime,
            isFavorite = favorite,
            isTrashed = trashed,
            mime = mime,
        ),
        facts,
        now,
    )

    @Test
    fun `cloned-app storage (user 999) gets the same rules as the main user (MSG-3)`() {
        val rels = listOf(
            "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/IMG-1.jpg",
            "Android/media/com.whatsapp/WhatsApp/Media/.Statuses/s.jpg",
            "Android/media/com.whatsapp/WhatsApp/Databases/msgstore.db.crypt14",
            "Pictures/Telegram/t.jpg",
            "Download/Telegram/doc.pdf",
            "Telegram/old.jpg",
        )
        for (rel in rels) {
            val main = c(rel)
            val clone = classifier.classify(
                ScanItem(KPath.Local("/storage/emulated/999/$rel"), "/storage/emulated/999/$rel", 1000, old),
                emptySet(),
                now,
            )
            (clone.ruleId to clone.category) shouldBe (main.ruleId to main.category)
        }
    }

    @Test
    fun `positive table`() {
        val table = listOf(
            Triple("DCIM/Camera/IMG_1.jpg", Category.MOVE, "camera"),
            Triple("DCIM/.thumbnails/1.jpg", Category.SAFE, "thumbnails"),
            Triple("Pictures/Screenshots/s.png", Category.REVIEW, "screenshots"),
            Triple("DCIM/Screenshots/s.png", Category.REVIEW, "screenshots"),
            Triple("Pictures/ChatGPT/x.png", Category.REVIEW, "ai_images"),
            Triple("Download/ChatGPT Image Mar 3, 2025.png", Category.REVIEW, "ai_images"),
            Triple("LOST.DIR/123", Category.SAFE, "lost_dir"),
            Triple("Download/upload.tmp", Category.SAFE, "temp_files"),
            Triple("Download/.DS_Store", Category.SAFE, "os_junk"),
            Triple("Download/._IMG_1.jpg", Category.SAFE, "os_junk"),
            Triple(".FileManagerRecycler/x", Category.SAFE, "other_app_bin"),
            Triple("DCIM/.trashed-1700000000-IMG.jpg", Category.SAFE, "other_app_bin"),
            Triple("MIUI/ShareMe/a.jpg", Category.REVIEW, "old_phone"),
            Triple("MIUI/sound_recorder/call_rec/c.mp3", Category.REVIEW, "call_recordings"),
            Triple("Recordings/Call/c.m4a", Category.REVIEW, "call_recordings"),
            Triple("Movies/Telegram/v.mp4", Category.MOVE, "telegram_saved"),
            Triple("Telegram/Telegram Images/1.jpg", Category.REVIEW, "telegram_legacy"),
            Triple("Download/app.apk", Category.REVIEW, "apk_files"),
            Triple("Documents/report.pdf", Category.REVIEW, "unknown"),
            // SAFE-9: other apps' own folders are shown per app, cleared inside that app.
            Triple("Android/media/com.spotify.music/downloads/song.ogg", Category.REVIEW, "app_content"),
            Triple(
                "Android/media/org.telegram.messenger/Telegram/Telegram Video/v.mp4",
                Category.REVIEW,
                "app_content",
            ),
            Triple("Android/data/com.netflix.mediaclient/files/Download/x", Category.PROTECTED, "protected"),
            Triple("WhatsApp/Media/WhatsApp Images/IMG.jpg", Category.REVIEW, "legacy_wa"),
            Triple(
                "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes/202601/PTT.opus",
                Category.MOVE,
                "wa_received",
            ),
            Triple("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Documents/a.pdf", Category.REVIEW, "wa_other"),
            Triple(
                "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/Private/a.jpg",
                Category.REVIEW,
                "wa_private",
            ),
            Triple(
                "Android/media/com.whatsapp/WhatsApp/Databases/msgstore.db.crypt14",
                Category.PROTECTED,
                "protected",
            ),
            Triple(
                "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Video/v.mp4",
                Category.MOVE,
                "wa_received",
            ),
        )
        table.forEach { (rel, cat, rule) ->
            val r = c(rel)
            check(r.category == cat && r.ruleId == rule) { "$rel -> ${r.category}/${r.ruleId}, expected $cat/$rule" }
        }
    }

    @Test
    fun `negative table - junk-looking names in user folders are not SAFE (E-CLS-5)`() {
        listOf(
            "Documents/temp/contract.pdf",
            "Documents/thumbnails list.pdf",
            "Download/lost.dir.notes.txt",
            "Documents/MIUI notes.txt",
            "Pictures/My Screenshots of tax forms/1.png",
        ).forEach { rel -> check(c(rel).category != Category.SAFE) { "$rel must not be SAFE" } }
    }

    @Test
    fun `album photos and videos are MOVE (CLS-3)`() {
        c("Pictures/Family/photo.jpg").run {
            category shouldBe Category.MOVE
            ruleId shouldBe "pictures_media"
        }
        c("Pictures/Family/clip.mp4").category shouldBe Category.MOVE
        c("Pictures/Screenshots/s.png").ruleId shouldBe "screenshots"
        c("Pictures/notes.txt").ruleId shouldBe "unknown"
    }

    @Test
    fun `junk names that only match after removing hidden characters are not SAFE`() {
        c("Documents/.thumb\u200Bnails/photo.jpg").category shouldBe Category.REVIEW
        c("Download/upload\u200B.tmp").category shouldBe Category.REVIEW
        c("DCIM/.thumbnails/1.jpg").category shouldBe Category.SAFE
    }

    @Test
    fun `sent photo without a byte-identical twin is REVIEW (E-CLS-1)`() {
        val rel = "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/Sent/IMG-1.jpg"
        c(rel).category shouldBe Category.REVIEW
        c(rel, facts = setOf(Fact.HAS_TWIN_OUTSIDE_MESSENGER)).category shouldBe Category.SAFE
    }

    @Test
    fun `partially extracted zip is REVIEW (E-CLS-2)`() {
        c("Download/a.zip", facts = setOf(Fact.ARCHIVE_PARTIALLY_EXTRACTED)).category shouldBe Category.REVIEW
        c("Download/a.zip", facts = setOf(Fact.ARCHIVE_FULLY_EXTRACTED)).category shouldBe Category.SAFE
    }

    @Test
    fun `favourite or recent SAFE item is downgraded to REVIEW, never the other way (CLS-5, E-CLS-3)`() {
        c("DCIM/.thumbnails/1.jpg", favorite = true).run {
            category shouldBe Category.REVIEW
            keepSignal shouldBe true
        }
        c("Download/a.zip", facts = setOf(Fact.ARCHIVE_FULLY_EXTRACTED), mtime = now - 3_600_000).run {
            category shouldBe Category.REVIEW
            keepSignal shouldBe true
        }
        c("LOST.DIR/0001", mtime = now - 3_600_000).category shouldBe Category.REVIEW
        c("Pictures/x.jpg", trashed = true, mtime = now - 3_600_000).category shouldBe Category.REVIEW
        c("DCIM/Camera/IMG.jpg", favorite = true).category shouldBe Category.MOVE
        c("Pictures/Screenshots/s.png", mtime = now - 1000).category shouldBe Category.REVIEW
    }

    @Test
    fun `regenerable items stay SAFE however recent (owner decision B1)`() {
        val hourAgo = now - 3_600_000
        listOf(
            "DCIM/.thumbnails/1.jpg" to "thumbnails",
            "Download/upload.tmp" to "temp_files",
            "Pictures/.DS_Store" to "os_junk",
            "Android/media/com.whatsapp/WhatsApp/Media/.Statuses/a.jpg" to "wa_status_old",
        ).forEach { (rel, rule) ->
            val mtime = if (rule == "wa_status_old") now - 2 * 24 * 3_600_000L else hourAgo
            c(rel, mtime = mtime).run {
                ruleId shouldBe rule
                category shouldBe Category.SAFE
                keepSignal shouldBe false
            }
        }
        // Favourites and lossy (hidden-character) paths still never stay SAFE.
        c("DCIM/.thumbnails/1.jpg", mtime = hourAgo, favorite = true).category shouldBe Category.REVIEW
        c("Documents/.thumb\u200Bnails/photo.jpg", mtime = hourAgo).category shouldBe Category.REVIEW
    }

    @Test
    fun `statuses depend on age`() {
        val rel = "Android/media/com.whatsapp/WhatsApp/Media/.Statuses/a.jpg"
        c(rel, mtime = now - 2 * 24 * 3_600_000L).ruleId shouldBe "wa_status_old"
        c(rel, mtime = now - 3_600_000L).ruleId shouldBe "wa_status_fresh"
    }

    @Test
    fun `big non-camera video is REVIEW, camera video is MOVE`() {
        c("Movies/Clip.mp4", size = 200_000_000).ruleId shouldBe "big_video"
        c("DCIM/Camera/VID.mp4", size = 200_000_000).category shouldBe Category.MOVE
        c("Movies/Clip.mp4", size = 50_000_000).ruleId shouldBe "unknown"
    }

    @Test
    fun `MediaStore-trashed rows are already-deleted (E-CLS-7)`() {
        c("Pictures/whatever.jpg", trashed = true).ruleId shouldBe "other_app_bin"
    }

    @Test
    fun `month buckets for grouped rules`() {
        c("Pictures/Screenshots/s.png", mtime = 1_678_000_000_000).group shouldBe GroupKey("screenshots", "2023-03")
    }
}
