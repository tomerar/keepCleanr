// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.storage

/**
 * Metadata of one entry, **without following symlinks** (lstat semantics).
 *
 * [identity] is a gateway-specific stable identity (inode, document id + generation) used to detect
 * swaps between plan and action (SEC-4). Null when the gateway can't provide one.
 */
data class Stat(
    val path: KPath,
    val name: String,
    val kind: Kind,
    val size: Long,
    val mtimeMillis: Long,
    val identity: String? = null,
) {
    enum class Kind { FILE, DIRECTORY, SYMLINK }

    val isFile: Boolean get() = kind == Kind.FILE
    val isDirectory: Boolean get() = kind == Kind.DIRECTORY
    val isSymlink: Boolean get() = kind == Kind.SYMLINK
}

/** What a gateway can do. Features check these instead of the gateway type. */
enum class Capability {
    LIST,
    READ,
    WRITE,
    RENAME_SAME_VOLUME,
    DELETE,
    STAT_MTIME,
    STAT_IDENTITY,
}
