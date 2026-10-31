// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.storage.file

import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.core.storage.StorageGateway
import io.github.tomerar.keepclear.core.storage.StorageGatewayContract
import java.nio.file.Files
import java.nio.file.Paths

/** The M1 contract suite against a real file system (tasks.md M1/M3). */
class FileGatewayContractTest : StorageGatewayContract() {
    override fun newGateway(): Pair<StorageGateway, KPath> {
        val dir = Files.createTempDirectory("kc-contract").toRealPath()
        dir.toFile().deleteOnExit()
        return FileGateway() to KPath.Local(dir.toString())
    }

    override fun createSymlink(gateway: StorageGateway, dir: KPath, name: String, target: String): Boolean {
        Files.createSymbolicLink(Paths.get((dir as KPath.Local).absolute, name), Paths.get(target))
        return true
    }
}
