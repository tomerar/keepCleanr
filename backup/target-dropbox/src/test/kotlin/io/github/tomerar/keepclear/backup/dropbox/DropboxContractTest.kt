// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.backup.dropbox

import io.github.tomerar.keepclear.backup.BackupTarget
import io.github.tomerar.keepclear.backup.BackupTargetContract
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach

/** M17c: the shared backup target contract against the fake Dropbox, in the App folder itself. */
class DropboxContractTest : BackupTargetContract() {
    private val server = FakeDropboxServer()

    @AfterEach
    fun stop() = server.close()

    override fun newTarget(): BackupTarget =
        DropboxBackupTarget(server.auth(), "", "dbx", "Dropbox", server.endpoints, timeoutSeconds = 10)
}

/** M17c: the same contract in a sub folder, with every upload going through upload sessions in small chunks. */
class DropboxSessionContractTest : BackupTargetContract() {
    private val server = FakeDropboxServer()

    @AfterEach
    fun stop() = server.close()

    override fun newTarget(): BackupTarget = runBlocking {
        DropboxBackupTarget(
            server.auth(),
            "KeepClear/Phone",
            "dbx",
            "Dropbox",
            server.endpoints,
            timeoutSeconds = 10,
            singleUploadMax = 0,
            chunkSize = 64 * 1024,
        ).also { it.mkdirs("") }
    }
}
