// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.storage

/**
 * Stable, serializable identity of a storage location (design.md §3.1).
 *
 * Only [Local] has path arithmetic; SAF and MediaStore locations are opaque handles resolved by
 * their gateway.
 */
sealed interface KPath {
    /** Absolute POSIX path, e.g. `/storage/emulated/0/DCIM/Camera/IMG_1.jpg`. No trailing slash. */
    data class Local(val absolute: String) : KPath {
        init {
            require(absolute.startsWith("/")) { "Local path must be absolute: $absolute" }
            require(absolute == "/" || !absolute.endsWith("/")) { "No trailing slash: $absolute" }
            require(absolute.split('/').none { it == "." || it == ".." }) { "No dot segments: $absolute" }
            require(!absolute.contains("//")) { "No empty segments: $absolute" }
            require(!absolute.contains('\u0000')) { "No NUL: $absolute" }
        }

        val name: String get() = absolute.substringAfterLast('/')

        val parent: Local? get() = when (absolute) {
            "/" -> null
            else -> Local(absolute.substringBeforeLast('/').ifEmpty { "/" })
        }

        /** Child path; [childName] must be a single, valid segment. */
        fun child(childName: String): Local {
            require(isValidName(childName)) { "Invalid name: $childName" }
            return Local(if (absolute == "/") "/$childName" else "$absolute/$childName")
        }

        fun isInside(ancestor: Local): Boolean =
            ancestor.absolute == "/" || absolute.startsWith(ancestor.absolute + "/")

        override fun toString(): String = absolute
    }

    /** A document inside a Storage Access Framework tree grant. */
    data class Saf(val treeUri: String, val documentId: String) : KPath

    /** A MediaStore row. */
    data class Media(val volume: String, val id: Long) : KPath

    companion object {
        /** A single path segment: non-empty, not `.`/`..`, no `/` or NUL, ≤ 255 UTF-8 bytes. */
        fun isValidName(name: String): Boolean =
            name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\u0000' !in name &&
                name.toByteArray(Charsets.UTF_8).size <= 255
    }
}
