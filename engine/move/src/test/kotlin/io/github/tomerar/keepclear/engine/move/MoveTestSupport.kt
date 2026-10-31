// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.move

import io.github.tomerar.keepclear.backup.BackupTarget
import io.github.tomerar.keepclear.backup.GatewayBackupTarget
import io.github.tomerar.keepclear.backup.TargetCaps
import io.github.tomerar.keepclear.core.storage.FakeStorageGateway
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.engine.scan.ScanItem
import java.io.InputStream

const val PHONE = "/storage/emulated/0"
const val USB = "/storage/USB-1"

class Rig(
    caps: TargetCaps = TargetCaps(canReadBack = true),
    files: Int = 6,
    targetCapacity: Long = Long.MAX_VALUE,
    /** False: the target refuses every read (a cloud provider that only uploads). */
    readable: Boolean = true,
) {
    val fake = FakeStorageGateway(caseInsensitive = true, volumeRoots = listOf(PHONE, USB)).apply {
        mkdirs(USB)
        setCapacity(USB, targetCapacity)
    }
    var corruptReads = 0

    /** Clean target reads to let through before [corruptReads] starts flipping bytes. */
    var skipReads = 0
    val target: BackupTarget = object : BackupTarget by GatewayBackupTarget(
        fake,
        KPath.Local(USB),
        "usb-1",
        "SanDisk",
        caps,
    ) {
        override suspend fun openRead(rel: String): InputStream {
            if (!readable) {
                throw io.github.tomerar.keepclear.core.storage.StorageException.Unsupported(
                    KPath.Local("$USB/$rel"),
                    "no reads",
                )
            }
            val real = fake.openRead(KPath.Local("$USB/$rel"))
            if (corruptReads <= 0 || rel.endsWith("SHA256SUMS") || rel.endsWith(".jsonl")) return real
            if (skipReads > 0) {
                skipReads--
                return real
            }
            corruptReads--
            val bytes = real.readBytes().also { if (it.isNotEmpty()) it[0] = (it[0] + 1).toByte() }
            return bytes.inputStream()
        }
    }
    val store = InMemoryMoveJobStore()
    val items: List<ScanItem> = (0 until files).map { i ->
        val path = "$PHONE/DCIM/Camera/IMG_2021010${i}_120000.jpg"
        fake.addFile(
            path,
            ByteArray(10_000 + i) { (it * (i + 1)).toByte() },
            mtime =
                1_610_000_000_000L + i * 86_400_000L,
        )
        ScanItem(KPath.Local(path), path, (10_000 + i).toLong(), 1_610_000_000_000L + i * 86_400_000L)
    }
    val now = 1_800_000_000_000L

    suspend fun newJob(id: String = "job1", selection: List<ScanItem> = items): String {
        store.putJob(MoveJob(id, target.id, target.label, MovePlanner.rootRel("OnePlus 10T"), now))
        MovePlanner.plan(id, selection, "OnePlus 10T", "Camera", target.caps).forEach { store.putFile(it) }
        return id
    }

    fun engine() = MoveEngine(fake, target, store, clock = { now })

    fun sourceSnapshot() = items.associate { it.path to fake.readAllBytes(it.path).toList() }
}
