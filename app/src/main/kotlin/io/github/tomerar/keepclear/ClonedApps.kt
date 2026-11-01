// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import io.github.tomerar.keepclear.core.safety.CanonicalPath

/** What KeepClear can do with the cloned-app storage on this phone (MSG-3). */
data class ClonedStorage(val readable: List<String> = emptyList(), val hidden: Boolean = false)

/**
 * MSG-3: "Parallel apps" (OnePlus/OxygenOS) and "Dual apps" (Xiaomi/HyperOS) reportedly run as Android user 999 with
 * their own shared storage at `/storage/emulated/999` (path **unverified**, device check D16a-1). Where it can be
 * read, it is scanned with the same rules (and the same PROTECTED paths, which match any volume); where it exists
 * but cannot be read, Home says so. Nothing here ever deletes.
 */
object ClonedApps {
    val CANDIDATE_USERS = listOf(999)

    fun root(user: Int) = "/storage/emulated/$user"

    /** True for a path inside a cloned-app storage root. */
    fun isCloned(path: String): Boolean {
        val segments = CanonicalPath.of(path)?.split('/')?.filter { it.isNotEmpty() } ?: return false
        return segments.size >= 3 && segments[0] == "storage" && segments[1] == "emulated" &&
            segments[2].toIntOrNull() in CANDIDATE_USERS
    }

    /**
     * [exists] and [canList] are the file system probes (injected for tests). [allFiles] is false when KeepClear
     * could not tell either way, so it claims nothing.
     */
    fun probe(allFiles: Boolean, exists: (String) -> Boolean, canList: (String) -> Boolean): ClonedStorage {
        if (!allFiles) return ClonedStorage()
        val present = CANDIDATE_USERS.map(::root).filter(exists)
        val readable = present.filter(canList)
        return ClonedStorage(readable = readable, hidden = present.size > readable.size)
    }
}
