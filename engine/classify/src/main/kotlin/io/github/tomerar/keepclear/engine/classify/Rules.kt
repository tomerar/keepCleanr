// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.classify

import io.github.tomerar.keepclear.core.model.Category
import io.github.tomerar.keepclear.core.safety.CanonicalPath
import io.github.tomerar.keepclear.engine.scan.ScanItem
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

/** Everything a rule may look at. [rel] is the canonical (lowercase) path relative to its volume root. */
class RuleInput(val item: ScanItem, val canonical: String, val rel: List<String>, val facts: Set<Fact>, val now: Long) {
    val name: String get() = rel.lastOrNull().orEmpty()
    val extension: String get() = name.substringAfterLast('.', "")
    val ageMillis: Long get() = now - item.mtimeMillis

    fun relStartsWith(vararg segments: String): Boolean =
        rel.size > segments.size && segments.indices.all { rel[it] == segments[it] }

    fun hasSegment(segment: String): Boolean = rel.dropLast(1).any { it == segment }

    /** Index of a messenger root segment ("whatsapp", "whatsapp business"), or -1. */
    val whatsAppRoot: Int get() = rel.indexOfFirst { it == "whatsapp" || it == "whatsapp business" }

    fun month(): String = Instant.ofEpochMilli(item.mtimeMillis).atZone(ZoneOffset.UTC).let {
        // Locale.ROOT: bucket keys are ISO "yyyy-MM" with ASCII digits on every device locale.
        "%04d-%02d".format(Locale.ROOT, it.year, it.monthValue)
    }

    fun year(): String = Instant.ofEpochMilli(item.mtimeMillis).atZone(ZoneOffset.UTC).year.toString()
}

/**
 * One classification rule (data, not scattered `if`s: CLS-1). First matching rule wins, in the order
 * of [Rules.all]. [bucket] groups items for presentation (e.g. by month).
 */
data class Rule(
    val id: String,
    val category: Category,
    val risk: RiskChip,
    val bucket: (RuleInput) -> String? = { null },
    /**
     * Owner decision B1 (CLS-5): regenerable items (thumbnails, temp files, WhatsApp statuses older than
     * 24 h, OS junk) stay SAFE however recent they are. Favourites and lossy paths still downgrade them.
     */
    val regenerable: Boolean = false,
    val matches: (RuleInput) -> Boolean,
)

/**
 * The rule table (design.md §3.2, requirements CLS-*, MSG-*). Versioned per MIG-3: bump
 * [RULES_VERSION] on any change and update the classification snapshot test; a change that moves
 * anything toward SAFE needs a CHANGELOG entry.
 *
 * PROTECTED is not a rule here: [Classifier] asks `core:safety` first, so the rule table can never
 * classify a protected path as anything else.
 */
object Rules {
    const val RULES_VERSION = 3
    const val SCHEMA_VERSION = 1

    private const val HOUR = 3_600_000L
    private const val DAY = 24 * HOUR
    private const val BIG_VIDEO_BYTES = 100_000_000L

    private val waMediaReceived = setOf(
        "whatsapp images",
        "whatsapp video",
        "whatsapp voice notes",
        "whatsapp video notes",
        "whatsapp business images",
        "whatsapp business video",
        "whatsapp business voice notes",
        "whatsapp business video notes",
    )
    private val osJunkNames = setOf(".ds_store", "thumbs.db", "desktop.ini", ".directory")
    private val videoExtensions = setOf("mp4", "mkv", "mov", "3gp", "webm", "avi")

    private fun RuleInput.waMediaFolder(): String? {
        val root = whatsAppRoot
        if (root < 0 || rel.getOrNull(root + 1) != "media") return null
        return rel.getOrNull(root + 2)
    }

    private fun RuleInput.isLegacyWhatsApp(): Boolean = whatsAppRoot == 0

    private fun RuleInput.isVideo(): Boolean = item.mime?.startsWith("video/") == true || extension in videoExtensions

    private val imageExtensions = setOf("jpg", "jpeg", "png", "heic", "heif", "webp", "gif", "dng", "raw")

    private fun RuleInput.isImage(): Boolean = item.mime?.startsWith("image/") == true || extension in imageExtensions

    val all: List<Rule> = listOf(
        // Already deleted in another app (SAFE-8, E-CLS-7).
        Rule("other_app_bin", Category.SAFE, RiskChip.ALREADY_DELETED) {
            it.item.isTrashed || it.name.startsWith(".trashed-") || it.relStartsWith(".filemanagerrecycler")
        },
        // WhatsApp, all variants (CLS-4, MSG-1/3).
        Rule("wa_status_old", Category.SAFE, RiskChip.REGENERATES, regenerable = true) {
            it.waMediaFolder() == ".statuses" && it.ageMillis > DAY
        },
        Rule("wa_status_fresh", Category.REVIEW, RiskChip.MIGHT_BE_MEMORY) { it.waMediaFolder() == ".statuses" },
        Rule("legacy_wa", Category.REVIEW, RiskChip.MIGHT_BE_MEMORY, bucket = { it.year() }) { it.isLegacyWhatsApp() },
        Rule("wa_sent_copy", Category.SAFE, RiskChip.COPY_EXISTS) {
            it.waMediaFolder() != null && it.hasSegment("sent") && Fact.HAS_TWIN_OUTSIDE_MESSENGER in it.facts
        },
        Rule("wa_sent", Category.REVIEW, RiskChip.MIGHT_BE_MEMORY) {
            it.waMediaFolder() != null && it.hasSegment("sent")
        },
        Rule("wa_private", Category.REVIEW, RiskChip.MIGHT_BE_MEMORY) {
            it.waMediaFolder() != null &&
                it.hasSegment("private")
        },
        Rule("wa_received", Category.MOVE, RiskChip.MIGHT_BE_MEMORY, bucket = { it.month() }) {
            it.waMediaFolder() in waMediaReceived
        },
        Rule("wa_other", Category.REVIEW, RiskChip.UNKNOWN) { it.waMediaFolder() != null },
        // Regenerable junk (SAFE-2).
        Rule("thumbnails", Category.SAFE, RiskChip.REGENERATES, regenerable = true) { it.hasSegment(".thumbnails") },
        Rule("lost_dir", Category.SAFE, RiskChip.REGENERATES) { it.relStartsWith("lost.dir") },
        Rule("temp_files", Category.SAFE, RiskChip.REGENERATES, regenerable = true) {
            it.extension == "tmp" ||
                it.extension == "temp"
        },
        Rule("os_junk", Category.SAFE, RiskChip.REGENERATES, regenerable = true) {
            it.name in osJunkNames ||
                it.name.startsWith("._")
        },
        // Archives next to their extracted folder (SAFE-3), facts from M7.
        Rule("zip_extracted", Category.SAFE, RiskChip.COPY_EXISTS) { Fact.ARCHIVE_FULLY_EXTRACTED in it.facts },
        Rule("zip_partial", Category.REVIEW, RiskChip.COPY_EXISTS) { Fact.ARCHIVE_PARTIALLY_EXTRACTED in it.facts },
        // REVIEW buckets (REV-1).
        Rule("screenshots", Category.REVIEW, RiskChip.MIGHT_BE_MEMORY, bucket = { it.month() }) {
            it.relStartsWith("pictures", "screenshots") || it.relStartsWith("dcim", "screenshots")
        },
        Rule("ai_images", Category.REVIEW, RiskChip.MIGHT_BE_MEMORY, bucket = { it.month() }) {
            it.relStartsWith("pictures", "chatgpt") || it.name.startsWith("chatgpt image") ||
                it.name.startsWith("dall·e") || it.name.startsWith("dall-e")
        },
        Rule("call_recordings", Category.REVIEW, RiskChip.MIGHT_BE_MEMORY, bucket = { it.year() }) {
            it.hasSegment("call_rec") || it.hasSegment("call recordings") || it.relStartsWith("recordings", "call")
        },
        Rule("old_phone", Category.REVIEW, RiskChip.UNKNOWN) { it.relStartsWith("miui") || it.hasSegment("shareme") },
        // Telegram (MSG-4).
        Rule("telegram_saved", Category.MOVE, RiskChip.MIGHT_BE_MEMORY, bucket = { it.month() }) {
            it.relStartsWith("pictures", "telegram") || it.relStartsWith("movies", "telegram")
        },
        Rule("telegram_docs", Category.REVIEW, RiskChip.UNKNOWN) {
            it.relStartsWith("download", "telegram") || it.relStartsWith("music", "telegram")
        },
        Rule("telegram_legacy", Category.REVIEW, RiskChip.MIGHT_BE_MEMORY) { it.relStartsWith("telegram") },
        // SAFE-9: another app's own media folder (Spotify downloads, Telegram media…): shown per app with its
        // size and cleared inside that app. Never selectable for deletion in KeepClear.
        Rule("app_content", Category.REVIEW, RiskChip.UNKNOWN) {
            it.rel.size >= 4 && it.rel[0] == "android" && it.rel[1] == "media"
        },
        // Memories (CLS-3).
        Rule("camera", Category.MOVE, RiskChip.MIGHT_BE_MEMORY, bucket = { it.month() }) { it.relStartsWith("dcim") },
        // CLS-3: photos and videos in Pictures/ albums (screenshots and AI images matched earlier).
        Rule("pictures_media", Category.MOVE, RiskChip.MIGHT_BE_MEMORY, bucket = { it.month() }) {
            it.relStartsWith("pictures") && (it.isVideo() || it.isImage())
        },
        Rule("big_video", Category.REVIEW, RiskChip.MIGHT_BE_MEMORY) { it.isVideo() && it.item.size > BIG_VIDEO_BYTES },
        Rule("apk_files", Category.REVIEW, RiskChip.UNKNOWN) { it.extension == "apk" || it.extension == "apks" },
        Rule("old_download", Category.REVIEW, RiskChip.UNKNOWN) {
            it.relStartsWith("download") &&
                it.ageMillis > 90 * DAY
        },
    )

    /** Fallback (CLS-6): we don't know what it is, so the user decides. */
    val unknown = Rule("unknown", Category.REVIEW, RiskChip.UNKNOWN) { true }

    /** PROTECTED explanation keys (`why_protected`, ...). */
    const val PROTECTED_RULE_ID = "protected"

    /** Path relative to its volume root, canonical and split. */
    fun relativeSegments(path: String): Pair<String, List<String>>? {
        val canonical = CanonicalPath.of(path) ?: return null
        val segments = canonical.split('/').filter { it.isNotEmpty() }
        val rel = when {
            segments.size >= 3 && segments[0] == "storage" && segments[1] == "emulated" -> segments.drop(3)
            segments.size >= 2 && segments[0] == "storage" -> segments.drop(2)
            else -> segments
        }
        return canonical to rel
    }
}

/** SAFE-9: size of other apps' own folders, per app, largest first. Read-only. */
object AppContent {
    const val RULE_ID = "app_content"

    /**
     * Package name of an `Android/media/<pkg>/…` or `Android/data/<pkg>/…` path, or null. Taken from the path
     * as written, not the lower-cased canonical form: package names are case-sensitive for PackageManager.
     */
    fun packageOf(path: String): String? {
        val raw = path.split('/').filter { it.isNotEmpty() }
        val i = raw.indices.firstOrNull { idx ->
            raw[idx].equals("android", ignoreCase = true) &&
                raw.getOrNull(idx + 1)?.lowercase() in setOf("media", "data")
        } ?: return null
        return raw.getOrNull(i + 2)?.takeIf { raw.size > i + 3 }
    }

    fun byApp(items: List<ClassifiedItem>): List<Pair<String, Total>> =
        items.filter { it.ruleId == RULE_ID }
            .groupBy { packageOf(it.item.path) ?: "?" }
            .map { (pkg, list) -> pkg to list.fold(Total()) { t, i -> t + i } }
            .sortedByDescending { it.second.bytes }
}
