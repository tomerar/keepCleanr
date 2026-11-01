// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.backup.webdav

import io.github.tomerar.keepclear.backup.BackupTarget
import io.github.tomerar.keepclear.backup.BackupTargetContract
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import java.util.UUID

/** M17: the shared backup target contract against the fake WebDAV server, as a generic WebDAV server. */
class WebDavContractTest : BackupTargetContract() {
    private val server = FakeDavServer()

    @AfterEach
    fun stop() = server.close()

    override fun newTarget(): BackupTarget = runBlocking {
        val folder = "KeepClear/run-${UUID.randomUUID()}"
        WebDavBackupTarget(server.location(folder, nextcloud = false), "dav", "WebDAV", timeoutSeconds = 10)
            .also { it.mkdirs("") }
    }
}

/** M17: the same contract with Nextcloud's server-computed SHA-256 switched on. */
class NextcloudContractTest : BackupTargetContract() {
    private val server = FakeDavServer().apply { nextcloud = true }

    @AfterEach
    fun stop() = server.close()

    override fun newTarget(): BackupTarget = runBlocking {
        val folder = "KeepClear/run-${UUID.randomUUID()}"
        WebDavBackupTarget(server.location(folder, nextcloud = true), "nc", "Nextcloud", timeoutSeconds = 10)
            .also { it.mkdirs("") }
    }
}
