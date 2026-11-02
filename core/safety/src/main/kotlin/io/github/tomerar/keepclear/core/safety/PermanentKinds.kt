// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.safety

import io.github.tomerar.keepclear.core.storage.KPath
import java.util.Locale

/** Why an item is deleted for good instead of going to the 30-day trash (owner decision 2026-11-02). Stored by name. */
enum class PermanentReason {
    /** Thumbnails, temp files, OS junk, WhatsApp statuses older than 24 h: apps or Android make them again. */
    REBUILDS_ITSELF,

    /** Already in another app's bin (Android's "Recently deleted", `.trashed-*`, a file manager's bin). */
    ALREADY_IN_A_BIN,
}

/**
 * Owner decision 2026-11-02 (requirements Q2 amended, SAFETY-2, ADR 0006 amendment): the **only** items that are
 * deleted for good right after the confirmation instead of going to KeepClear's 30-day trash. Everything else
 * (duplicates, extracted archives, REVIEW deletions, MOVE originals, "Always treat as junk", LOST.DIR) still goes to
 * the trash.
 *
 * The [ActionExecutor] decides this per op from data, never from a UI flag: the op must be a plain [OpKind.TRASH]
 * of one file, classified SAFE (not moved to REVIEW by a keep signal), not part of a duplicate group, with no
 * witnesses, and **both** its classification rule and its path must say the same kind. The path test is strict and
 * lossless: a name that only looks like junk after removing invisible or bidi characters is not junk here (SEC-6),
 * and a `.trashed-` name must have the exact form Android gives it (`.trashed-<digits>-<name>`, SEC-10).
 */
object PermanentKinds {
    /** Classification rules (`engine:classify` `Rules`) of things that rebuild themselves (owner decision B1). */
    const val THUMBNAILS = "thumbnails"
    const val TEMP_FILES = "temp_files"
    const val OS_JUNK = "os_junk"
    const val WA_STATUS_OLD = "wa_status_old"

    /** Classification rule of items already in another app's bin. */
    const val OTHER_APP_BIN = "other_app_bin"

    val rebuildRules: Set<String> = setOf(THUMBNAILS, TEMP_FILES, OS_JUNK, WA_STATUS_OLD)

    /** Every rule that can ever be deleted for good. */
    val rules: Set<String> = rebuildRules + OTHER_APP_BIN

    private const val DAY = 24L * 3_600_000L
    private val osJunkNames = setOf(".ds_store", "thumbs.db", "desktop.ini", ".directory")
    private val tempExtensions = setOf("tmp", "temp")
    private val whatsAppRoots = setOf("whatsapp", "whatsapp business")
    private const val OEM_BIN = ".filemanagerrecycler"

    /** `.trashed-<seconds>-<name>`: Android 11+ renames a file it moves to its trash like this. */
    private val trashedName = Regex("""^\.trashed-([0-9]{1,19})-(.+)$""")

    /**
     * The reason [op] is deleted for good, or null when it goes to the trash (or isn't a removal at all). [now] is
     * the wall clock, for the 24 h age of a WhatsApp status.
     */
    fun reasonFor(op: PlannedOp, now: Long): PermanentReason? {
        if (op.kind != OpKind.TRASH || !op.classifiedSafe || op.isDirectory) return null
        if (op.duplicateGroup != null || op.witnesses.isNotEmpty() || op.lastCopyConfirmed) return null
        val rule = op.ruleId ?: return null
        if (rule !in rules) return null
        // A MediaStore row Android already has in its trash: the media handler re-reads IS_TRASHED right before it
        // asks Android to delete it, and refuses a row that isn't trashed any more.
        if (rule == OTHER_APP_BIN && op.ref is KPath.Media) return PermanentReason.ALREADY_IN_A_BIN
        val rel = losslessRelative(op.displayPath) ?: return null
        val name = rel.lastOrNull() ?: return null
        val folders = rel.dropLast(1)
        return when (rule) {
            THUMBNAILS -> PermanentReason.REBUILDS_ITSELF.takeIf { ".thumbnails" in folders }

            TEMP_FILES -> PermanentReason.REBUILDS_ITSELF.takeIf {
                name.substringAfterLast('.', "") in tempExtensions && name.lastIndexOf('.') > 0
            }

            OS_JUNK -> PermanentReason.REBUILDS_ITSELF.takeIf {
                name in osJunkNames || (name.startsWith("._") && name.length > 2)
            }

            WA_STATUS_OLD -> PermanentReason.REBUILDS_ITSELF.takeIf {
                isWhatsAppStatus(folders) && now - op.expectedMtime > DAY
            }

            OTHER_APP_BIN -> PermanentReason.ALREADY_IN_A_BIN.takeIf {
                trashedOriginalName(name) != null || folders.firstOrNull() == OEM_BIN
            }

            else -> null
        }
    }

    /**
     * The original name of a file in Android's trash (`.trashed-1700000000-IMG_1.jpg` → `IMG_1.jpg`), or null when
     * [name] doesn't have exactly that form. Strict: digits only between the dashes, a non-empty name after them,
     * and no bidi or invisible characters anywhere (they could make two different names look alike, SEC-10).
     */
    fun trashedOriginalName(name: String): String? {
        if (name.any { isInvisible(it) }) return null
        val match = trashedName.matchEntire(name) ?: return null
        val original = match.groupValues[2]
        return original.takeIf { KPath.isValidName(it) && it != "." && it != ".." }
    }

    private fun isWhatsAppStatus(folders: List<String>): Boolean {
        val root = folders.indexOfFirst { it in whatsAppRoots }
        return root >= 0 && folders.getOrNull(root + 1) == "media" && folders.getOrNull(root + 2) == ".statuses"
    }

    /**
     * Lowercase segments below the volume root, only when canonicalising changed nothing but the case (no aliases,
     * invisible or bidi characters, Unicode forms): otherwise null, and nothing is deleted for good.
     */
    private fun losslessRelative(path: String): List<String>? {
        val canonical = CanonicalPath.of(path) ?: return null
        if (canonical != path.lowercase(Locale.ROOT)) return null
        val segments = canonical.split('/').filter { it.isNotEmpty() }
        return when {
            segments.size >= 4 && segments[0] == "storage" && segments[1] == "emulated" -> {
                segments.drop(3)
            }

            segments.size >= 3 && segments[0] == "storage" && segments[1] != "emulated" && segments[1] != "self" -> {
                segments.drop(2)
            }

            else -> {
                null
            }
        }
    }

    private fun isInvisible(c: Char): Boolean = Character.getType(c) == Character.FORMAT.toInt() || c.isISOControl()
}
