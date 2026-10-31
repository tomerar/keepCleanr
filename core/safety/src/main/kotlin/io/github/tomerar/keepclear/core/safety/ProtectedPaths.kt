// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.safety

/**
 * PROTECTED paths (SAFETY-3, MSG-2, SEC-6). Enforced by [SafetyGuard] and again by [ActionExecutor]
 * for every file inside a directory op. Over-protecting is acceptable; under-protecting is a bug.
 *
 * All matching runs on [CanonicalPath] output, so case, NFD/NFC, zero-width/bidi characters and
 * storage aliases can't dodge it, on any volume or user root (`/storage/emulated/<n>`, SD cards).
 */
class ProtectedPaths(
    /** Extra protected roots (canonicalised): active backup target, user exclusions. */
    extraRoots: Collection<String> = emptyList(),
    /** KeepClear's own package: its `Android/data|media|obb/<pkg>` folders are PROTECTED (SAFETY-3). */
    ownPackage: String = "io.github.tomerar.keepclear",
) {
    private val ownPackage = ownPackage.lowercase()
    private val extra: List<String> = extraRoots.mapNotNull { CanonicalPath.of(it) }

    /** True when [rawPath] itself must never be deleted, moved or overwritten. */
    fun isProtected(rawPath: String): Boolean {
        val path = CanonicalPath.of(rawPath) ?: return true // unparseable → refuse
        val segments = path.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return true
        val name = segments.last()

        // Chat databases and backups of every WhatsApp variant, scoped or legacy (MSG-2).
        val waIndex = segments.indexOfFirst { it == "whatsapp" || it.startsWith("whatsapp ") }
        if (waIndex >= 0 && segments.drop(waIndex + 1).any { it == "databases" || it == "backups" }) return true

        // Chat database files wherever they are.
        if (".crypt" in name) return true
        if (name.startsWith("msgstore")) return true
        if (name.startsWith("wa.db") || name.startsWith("axolotl.db")) return true
        if (name.startsWith("chatsettings") && ".db" in name) return true

        // KeepClear's own trash is only touched by the trash module (PURGE/RESTORE).
        if (TRASH_DIR in segments) return true

        // KeepClear's own app folders.
        val rel = relativeToVolume(segments)
        if (rel.size >= 3 && rel[0] == "android" && rel[1] in setOf("data", "media", "obb") &&
            rel[2] == ownPackage
        ) {
            return true
        }

        if (extra.any { path == it || path.startsWith("$it/") }) return true
        return false
    }

    /**
     * True when a **directory** op on [rawPath] would be dangerous even if the directory itself isn't
     * protected: storage roots, `Android/` and its standard children, any messenger root.
     */
    fun isProtectedContainer(rawPath: String): Boolean {
        if (isProtected(rawPath)) return true
        val path = CanonicalPath.of(rawPath) ?: return true
        val segments = path.split('/').filter { it.isNotEmpty() }
        if (segments.size <= 3) return true // "/", "/storage", "/storage/emulated", "/storage/XXXX-XXXX"
        val relative = relativeToVolume(segments)
        if (relative.isEmpty()) return true
        if (relative.first() == "android" && relative.size <= 3) return true // Android/, Android/media/<pkg>
        if (relative.any { it == "whatsapp" || it.startsWith("whatsapp ") }) {
            // Only the media sub-folders of a WhatsApp tree can be directory targets.
            val i = relative.indexOfFirst { it == "whatsapp" || it.startsWith("whatsapp ") }
            if (relative.size <= i + 2) return true // WhatsApp/, WhatsApp/Media
        }
        if (extra.any { it.startsWith("$path/") }) return true // contains an excluded path
        return false
    }

    private fun relativeToVolume(segments: List<String>): List<String> = when {
        segments.size >= 3 && segments[0] == "storage" && segments[1] == "emulated" -> segments.drop(3)
        segments.size >= 2 && segments[0] == "storage" -> segments.drop(2)
        else -> segments
    }

    companion object {
        /** Trash folder name, lowercase (canonical). */
        const val TRASH_DIR = ".keepcleartrash"

        /** Trash folder name as created on disk. */
        const val TRASH_FOLDER_NAME = ".KeepClearTrash"
    }
}
