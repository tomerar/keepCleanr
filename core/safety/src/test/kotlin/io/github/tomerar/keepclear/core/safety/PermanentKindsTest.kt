// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.safety

import io.github.tomerar.keepclear.core.storage.KPath
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Owner decision 2026-11-02: table-driven positive and negative cases for what is deleted for good (CLAUDE.md: rules
 * are data with positive and negative tests). Everything not listed as permanent goes to the 30-day trash.
 */
class PermanentKindsTest {
    private val now = 10L * 24 * 3_600_000
    private val old = now - 2L * 24 * 3_600_000
    private val fresh = now - 3_600_000
    private val wa = "$ROOT/Android/media/com.whatsapp/WhatsApp/Media"

    private fun op(
        path: String,
        rule: String?,
        safe: Boolean = true,
        kind: OpKind = OpKind.TRASH,
        mtime: Long = old,
        dir: Boolean = false,
        group: String? = null,
        witnesses: List<Witness> = emptyList(),
        ref: KPath = KPath.Local(path),
    ) = PlannedOp(
        ref,
        path,
        kind,
        dir,
        10,
        mtime,
        ruleId = rule,
        classifiedSafe = safe,
        duplicateGroup = group,
        witnesses = witnesses,
    )

    private val rebuilds = PermanentReason.REBUILDS_ITSELF
    private val inBin = PermanentReason.ALREADY_IN_A_BIN

    @Test
    fun `the two kinds the owner named are deleted for good`() {
        val cases = listOf(
            op("$ROOT/DCIM/.thumbnails/1234.jpg", "thumbnails") to rebuilds,
            op("$ROOT/Pictures/.Thumbnails/a.png", "thumbnails") to rebuilds,
            op("$ROOT/Download/setup.tmp", "temp_files") to rebuilds,
            op("$ROOT/Download/x.TEMP", "temp_files") to rebuilds,
            op("$ROOT/Download/.DS_Store", "os_junk") to rebuilds,
            op("$ROOT/Music/Thumbs.db", "os_junk") to rebuilds,
            op("$ROOT/Music/._song.mp3", "os_junk") to rebuilds,
            op("$wa/.Statuses/abc.jpg", "wa_status_old") to rebuilds,
            op("$ROOT/WhatsApp Business/Media/.Statuses/v.mp4", "wa_status_old") to rebuilds,
            op("$ROOT/DCIM/Camera/.trashed-1700000000-IMG_1.jpg", "other_app_bin") to inBin,
            op("/storage/1234-ABCD/DCIM/.trashed-1-a b.jpg", "other_app_bin") to inBin,
            op("$ROOT/.FileManagerRecycler/IMG_2.jpg", "other_app_bin") to inBin,
            op("$ROOT/.FileManagerRecycler/x/y.pdf", "other_app_bin") to inBin,
            op("$ROOT/DCIM/a.jpg", "other_app_bin", ref = KPath.Media("external_primary", 7)) to inBin,
        )
        cases.forEach { (o, reason) -> PermanentKinds.reasonFor(o, now) shouldBe reason }
    }

    @Test
    fun `everything else goes to the trash`() {
        val cases = listOf(
            // Duplicates, extracted archives, REVIEW, MOVE, user junk, LOST.DIR.
            op("$wa/WhatsApp Images/Sent/a.jpg", "wa_sent_copy", group = "dup:1"),
            op("$ROOT/Download/a.zip", "zip_extracted", witnesses = listOf(Witness(KPath.Local("$ROOT/x"), "x", 1))),
            op("$ROOT/Pictures/Screenshots/s.png", "screenshots", safe = false),
            op("$ROOT/DCIM/Camera/IMG_1.jpg", "camera", kind = OpKind.MOVE_DELETE_ORIGINAL),
            op("$ROOT/Download/Old/x.tmp", "user_junk"),
            op("$ROOT/LOST.DIR/1", "lost_dir"),
            op("$ROOT/Download/x.tmp", null),
            // A permanent rule moved to REVIEW by a keep signal (favourite, recent) is a REVIEW deletion.
            op("$ROOT/DCIM/.thumbnails/1.jpg", "thumbnails", safe = false),
            op("$ROOT/DCIM/Camera/.trashed-1700000000-IMG_1.jpg", "other_app_bin", safe = false),
            // A permanent kind that is also someone's duplicate or witness stays recoverable.
            op("$ROOT/Download/a.tmp", "temp_files", group = "dup:2"),
            op("$ROOT/Download/a.tmp", "temp_files", witnesses = listOf(Witness(KPath.Local("$ROOT/y"), "y", 1))),
            // Folders never.
            op("$ROOT/DCIM/.thumbnails", "thumbnails", dir = true),
            // The rule and the path must agree.
            op("$ROOT/DCIM/Camera/IMG_1.jpg", "thumbnails"),
            op("$ROOT/DCIM/.thumbnails", "thumbnails"),
            op("$ROOT/Download/tmp", "temp_files"),
            op("$ROOT/Download/.tmp", "temp_files"),
            op("$ROOT/Download/a.tmp.jpg", "temp_files"),
            op("$ROOT/Download/._", "os_junk"),
            op("$ROOT/Download/DS_Store", "os_junk"),
            op("$ROOT/DCIM/Camera/IMG_1.jpg", "wa_status_old"),
            op("$wa/WhatsApp Images/a.jpg", "wa_status_old"),
            op("$wa/.Statuses/abc.jpg", "wa_status_old", mtime = fresh),
            op("$ROOT/DCIM/Camera/IMG_1.jpg", "other_app_bin"),
            op("$ROOT/DCIM/.FileManagerRecycler/a.jpg", "other_app_bin"),
            // Other kinds are never turned into a permanent delete.
            op("$ROOT/DCIM/.thumbnails/1.jpg", "thumbnails", kind = OpKind.PURGE),
            op("$ROOT/DCIM/.thumbnails/1.jpg", "thumbnails", kind = OpKind.RESTORE),
            // Outside a volume, aliases and invisible characters: not lossless, so not permanent.
            op("/sdcard/DCIM/.thumbnails/1.jpg", "thumbnails"),
            op("/data/local/.thumbnails/1.jpg", "thumbnails"),
            op("$ROOT/DCIM/.thumb​nails/1.jpg", "thumbnails"),
            op("$ROOT/Download/a.t​mp", "temp_files"),
            op("$ROOT/DCIM/⁧.thumbnails⁩/1.jpg", "thumbnails"),
        )
        cases.forEach { o -> (o.displayPath to PermanentKinds.reasonFor(o, now)) shouldBe (o.displayPath to null) }
    }

    @Test
    fun `trashed names are parsed strictly (SEC-10)`() {
        PermanentKinds.trashedOriginalName(".trashed-1700000000-IMG_1.jpg") shouldBe "IMG_1.jpg"
        PermanentKinds.trashedOriginalName(".trashed-1-a-b-c.jpg") shouldBe "a-b-c.jpg"
        PermanentKinds.trashedOriginalName(".trashed-1700000000-צילום.jpg") shouldBe "צילום.jpg"
        listOf(
            ".trashed-",
            ".trashed--a.jpg",
            ".trashed-abc-a.jpg",
            ".trashed-17000x0000-a.jpg",
            ".trashed-1700000000-",
            ".trashed-1700000000",
            "trashed-1700000000-a.jpg",
            ".Trashed-1700000000-a.jpg",
            ".trashed-12345678901234567890-a.jpg",
            ".trashed-1700000000-a​.jpg",
            ".trashed-1700000000-‮gpj.exe",
            ".trashed-١٧٠٠-a.jpg",
            " .trashed-1-a.jpg",
        ).forEach { (it to PermanentKinds.trashedOriginalName(it)) shouldBe (it to null) }
    }

    @Test
    fun `a plan can't ask for a permanent delete itself`() {
        val guard = SafetyGuard(ProtectedPaths())
        val path = "$ROOT/Download/a.tmp"
        val direct = op(path, "temp_files", kind = OpKind.DELETE_FOR_GOOD)
        val flagged = op(path, "temp_files").copy(permanentReason = PermanentReason.REBUILDS_ITSELF)
        for (o in listOf(direct, flagged)) {
            val verdict = guard.check(ActionPlan("p", listOf(o), "t").confirm(1, SESSION), SESSION)
            (verdict is SafetyGuard.Verdict.Rejected) shouldBe true
        }
    }
}
