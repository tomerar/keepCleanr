// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.storage

import java.io.IOException

/** Every gateway failure maps to one of these, so engines handle errors without knowing the backend. */
sealed class StorageException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    class NotFound(val path: KPath) : StorageException("Not found: $path")

    class AlreadyExists(val path: KPath) : StorageException("Already exists: $path")

    class NotEmpty(val path: KPath) : StorageException("Directory not empty: $path")

    class NotADirectory(val path: KPath) : StorageException("Not a directory: $path")

    class IsADirectory(val path: KPath) : StorageException("Is a directory: $path")

    /** ENOSPC: the volume is full. */
    class NoSpace(val path: KPath) : StorageException("No space left: $path")

    /** Volume or provider went away (OTG unplugged, SD card removed, provider died). */
    class Disconnected(val path: KPath, cause: Throwable? = null) : StorageException("Disconnected: $path", cause)

    /** EACCES / SecurityException / revoked grant. */
    class PermissionDenied(val path: KPath, cause: Throwable? = null) :
        StorageException("Permission denied: $path", cause)

    /** Operation not supported here (e.g. cross-volume rename, symlink target). */
    class Unsupported(val path: KPath, reason: String) : StorageException("Unsupported on $path: $reason")

    /** Anything else. */
    class Io(val path: KPath, cause: Throwable? = null, message: String = "I/O error") :
        StorageException("$message: $path", cause)
}
