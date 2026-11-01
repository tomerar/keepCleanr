// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.backup

import io.github.tomerar.keepclear.core.storage.FakeStorageGateway
import io.github.tomerar.keepclear.core.storage.KPath

/** The backup target contract on the fake drive (USB/SD through a storage gateway). */
class GatewayBackupTargetContractTest : BackupTargetContract() {
    override fun newTarget(): BackupTarget {
        val fake = FakeStorageGateway(volumeRoots = listOf("/storage/USB-1")).apply { mkdirs("/storage/USB-1/root") }
        return GatewayBackupTarget(
            fake,
            KPath.Local("/storage/USB-1/root"),
            "usb",
            "USB",
            TargetCaps(canReadBack = true),
        )
    }
}
