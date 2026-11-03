// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

/**
 * Owner brief H2 item 10: places are shown in plain words, never as raw paths like "USB:KeepClear/Phone/IMG" or
 * "/storage/emulated/0/…". A drive is its label; a folder is its names joined with " › " (as Android's own
 * breadcrumbs and our "Pictures › KeepClear Restored"); a file name is left out. The raw path stays in the data (the
 * trash record, the manifest) and in the developer report.
 */
object HumanLocation {
    const val SEPARATOR = " › "

    private val phoneRoot = Regex("^/storage/emulated/\\d+(/|$)")
    private val sdcardLink = Regex("^/sdcard(/|$)")
    private val otherVolume = Regex("^/storage/(?!emulated/)[^/]+(/|$)")

    /**
     * The drive of a trash entry's checked copy. The record is "<drive label>: <path on the drive>" (MoveEngine);
     * older or hand-made records may have no space after the colon.
     */
    fun driveOf(verifiedCopy: String): String {
        val split = splitCopy(verifiedCopy) ?: return verifiedCopy.trim()
        return split.first
    }

    /** "SanDisk: KeepClear/Pixel 8/Camera/IMG_1.jpg" → "SanDisk › KeepClear › Pixel 8 › Camera" (for Details). */
    fun copyLocation(verifiedCopy: String): String {
        val split = splitCopy(verifiedCopy) ?: return verifiedCopy.trim()
        val folders = segments(split.second).dropLast(1)
        return (listOf(split.first) + folders).joinToString(SEPARATOR)
    }

    /** A folder path relative to a drive or the phone ("KeepClear/Pixel 8") in plain words: "KeepClear › Pixel 8". */
    fun folder(relPath: String): String = segments(relPath).joinToString(SEPARATOR)

    /**
     * The folder a file on the phone goes to, without the storage root and without the file name:
     * "/storage/emulated/0/KeepClear/Restored-changed/DCIM/IMG.jpg" → "KeepClear › Restored-changed › DCIM". On a
     * memory card or another drive the folders follow [cardName] ("Memory card › DCIM"). A file at the root of the
     * phone's storage gives [phoneName].
     */
    fun phoneFolder(path: String, phoneName: String, cardName: String): String {
        val (prefix, rest) = when {
            phoneRoot.containsMatchIn(path) -> null to path.replaceFirst(phoneRoot, "")
            sdcardLink.containsMatchIn(path) -> null to path.replaceFirst(sdcardLink, "")
            otherVolume.containsMatchIn(path) -> cardName to path.replaceFirst(otherVolume, "")
            else -> null to path
        }
        val folders = segments(rest).dropLast(1)
        val parts = listOfNotNull(prefix) + folders
        return if (parts.isEmpty()) phoneName else parts.joinToString(SEPARATOR)
    }

    private fun segments(path: String): List<String> =
        path.split('/', '\\').map { it.trim() }.filter { it.isNotEmpty() && it != "." }

    private fun splitCopy(record: String): Pair<String, String>? {
        val spaced = record.indexOf(": ")
        val at = when {
            spaced > 0 -> spaced
            else -> record.indexOf(':').takeIf { it > 0 && record.indexOf('/', it) > it } ?: return null
        }
        val label = record.substring(0, at).trim()
        val rest = record.substring(at + 1).trim()
        return if (label.isEmpty()) null else label to rest
    }
}
