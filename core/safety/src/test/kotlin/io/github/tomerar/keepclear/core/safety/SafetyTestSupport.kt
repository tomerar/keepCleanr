// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.safety

import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.core.storage.StorageGateway

const val SESSION = "session-1"
const val ROOT = "/storage/emulated/0"

/** Records every op the executor hands over; optionally moves files like a trash would. */
class RecordingHandler(private val moveToTrash: Boolean = false) : ActionHandler {
    val performed = mutableListOf<PlannedOp>()

    override suspend fun perform(op: PlannedOp, gateway: StorageGateway) {
        performed += op
        if (moveToTrash) {
            val local = op.ref as KPath.Local
            val trash = KPath.Local("$ROOT/.KeepClearTrash")
            if (gateway.stat(trash) == null) gateway.mkdir(KPath.Local(ROOT), ".KeepClearTrash")
            gateway.rename(local, trash, "${performed.size}-${local.name}")
        }
    }
}

suspend fun StorageGateway.op(
    path: String,
    kind: OpKind = OpKind.TRASH,
    sha: String? = null,
    group: String? = null,
): PlannedOp {
    val stat = stat(KPath.Local(path)) ?: error("missing $path")
    return PlannedOp(
        ref = stat.path,
        displayPath = path,
        kind = kind,
        isDirectory = stat.isDirectory,
        expectedSize = stat.size,
        expectedMtime = stat.mtimeMillis,
        expectedIdentity = stat.identity,
        expectedSha256 = sha,
        duplicateGroup = group,
    )
}

fun plan(vararg ops: PlannedOp, groups: Map<String, Int> = emptyMap(), session: String = SESSION) =
    ActionPlan(
        id = "p",
        ops = ops.toList(),
        reason = "test",
        duplicateGroupSizes = groups,
    ).confirm(now = 1, session = session)
