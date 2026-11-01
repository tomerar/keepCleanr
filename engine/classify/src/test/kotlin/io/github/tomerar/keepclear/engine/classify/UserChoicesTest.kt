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
}
