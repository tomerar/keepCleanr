// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.app.Application
import io.github.tomerar.keepclear.core.database.KeepClearDatabase
import io.github.tomerar.keepclear.core.database.ScanIndex
import io.github.tomerar.keepclear.core.storage.file.FileGateway
import io.github.tomerar.keepclear.core.storage.media.MediaStoreSource
import java.util.UUID

/**
 * Manual dependency graph. Hilt is deferred until the graph is big enough to need it
 * (tasks.md M3 note); everything here is created lazily on first use.
 */
class KeepClearApplication : Application() {
    /** Identifies this process for plan confirmation (SAFETY-1, MIG-5). */
    val sessionId: String = UUID.randomUUID().toString()

    val database by lazy { KeepClearDatabase.build(this) }
    val scanIndex by lazy { ScanIndex(database) }
    val fileGateway by lazy { FileGateway() }
    val mediaSource by lazy { MediaStoreSource(contentResolver) }
}
