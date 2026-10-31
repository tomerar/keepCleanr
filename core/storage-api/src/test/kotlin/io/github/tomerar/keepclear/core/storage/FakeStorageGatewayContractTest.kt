// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.storage

class FakeStorageGatewayContractTest : StorageGatewayContract() {
    override fun newGateway(): Pair<StorageGateway, KPath> {
        val fake = FakeStorageGateway()
        return fake to fake.mkdirs("/storage/emulated/0/work")
    }

    override fun createSymlink(gateway: StorageGateway, dir: KPath, name: String, target: String): Boolean {
        (gateway as FakeStorageGateway).addSymlink((dir as KPath.Local).child(name).absolute, target)
        return true
    }
}

class CaseInsensitiveFakeContractTest : StorageGatewayContract() {
    override fun newGateway(): Pair<StorageGateway, KPath> {
        val fake = FakeStorageGateway(caseInsensitive = true)
        return fake to fake.mkdirs("/storage/emulated/0/work")
    }
}
