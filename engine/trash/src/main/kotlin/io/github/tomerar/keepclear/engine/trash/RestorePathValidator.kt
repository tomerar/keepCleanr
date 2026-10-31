// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.trash

import io.github.tomerar.keepclear.core.safety.CanonicalPath
import io.github.tomerar.keepclear.core.safety.ProtectedPaths
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.core.storage.StorageGateway

/**
 * SEC-1: every restore destination is validated, whether the entry is trusted or not.
 * Returns null when the destination is acceptable, else a reason.
 */
class RestorePathValidator(private val protectedPaths: ProtectedPaths) {
    suspend fun check(destination: String, volumeRoot: String, gateway: StorageGateway): String? {
        val dest = try {
            KPath.Local(destination) // rejects relative, '..', '.', '//', NUL
        } catch (e: IllegalArgumentException) {
            return "invalid path"
        }
        if (!KPath.isValidName(dest.name)) return "invalid name"
        if (dest.name.any { it.isISOControl() || it == '\\' }) return "invalid characters in name"
        val canonical = CanonicalPath.of(destination) ?: return "invalid path"
        val canonicalRoot = CanonicalPath.of(volumeRoot) ?: return "invalid volume"
        if (!canonical.startsWith("$canonicalRoot/")) return "outside the original volume"
        if (canonical.split('/').contains(TrashPolicy.TRASH_DIR.lowercase())) return "inside the trash"
        if (protectedPaths.isProtected(destination)) return "protected location"
        // Every existing ancestor must be a real directory (no symlink tricks).
        var parent = dest.parent
        while (parent != null && parent.absolute.length >= volumeRoot.length) {
            val stat = gateway.stat(parent)
            if (stat != null && !stat.isDirectory) return "an ancestor is not a directory"
            parent = parent.parent
        }
        return null
    }
}
