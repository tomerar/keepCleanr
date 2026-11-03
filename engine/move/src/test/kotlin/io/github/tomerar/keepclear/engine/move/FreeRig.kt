// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.move

import io.github.tomerar.keepclear.core.safety.ActionExecutor
import io.github.tomerar.keepclear.core.safety.ActionPlan
import io.github.tomerar.keepclear.core.safety.Confirmation
import io.github.tomerar.keepclear.core.safety.OpResult
import io.github.tomerar.keepclear.core.safety.ProtectedPaths
import io.github.tomerar.keepclear.core.safety.SafetyGuard
import io.github.tomerar.keepclear.engine.trash.FakeClock
import io.github.tomerar.keepclear.engine.trash.FakeSigner
import io.github.tomerar.keepclear.engine.trash.InMemoryTrashIndex
import io.github.tomerar.keepclear.engine.trash.TrashManager

/**
 * Owner decision P1: a backup on [Rig] plus the real choke point (ActionExecutor + TrashManager on the same
 * FakeStorageGateway), wired the way the app wires "Free up space".
 */
class FreeRig(files: Int = 4) {
    val rig = Rig(files = files)
    val fake get() = rig.fake
    val index = InMemoryTrashIndex()
    private var ids = 0
    var trash = newTrash()

    /** Test mode (SAFETY-9). */
    var dryRun = false

    /** False = the user's "keep the original in the trash too" setting is on. */
    var forGood = true

    /** The session this process runs in; a plan confirmed in another one is refused (SAFETY-1). */
    var session = "s"
    var protectedPaths = ProtectedPaths()

    fun newTrash() = TrashManager(
        rig.fake,
        index,
        FakeSigner(),
        FakeClock(wall = rig.now),
        ProtectedPaths(),
        listOf(PHONE),
        newId = { "00000000-0000-4000-8000-%012d".format(++ids) },
    )

    fun executor(onResult: (OpResult) -> Unit = {}) = ActionExecutor(
        rig.fake,
        SafetyGuard(protectedPaths),
        protectedPaths,
        trash,
        session,
        isDryRun = { dryRun },
        onResult = onResult,
        clock = { rig.now },
        freeOriginalsForGood = { forGood },
    )

    /** Copies every file; with [secondCheck] the drive is unplugged and plugged back in and "Check backup" runs. */
    suspend fun backup(secondCheck: Boolean = true, id: String = "job1"): String {
        rig.newJob(id)
        rig.engine().run(id).state shouldBeDone id
        if (secondCheck) {
            rig.reattach()
            rig.engine().confirm(id, FreshRead.REATTACHED)
        }
        return id
    }

    private infix fun JobState.shouldBeDone(id: String) = check(this == JobState.DONE) { "job $id ended $this" }

    /** "Free up space" with the user's tap on [shown] (default: every original), confirmed in [confirmedIn]. */
    fun freer(
        shown: Collection<String> = rig.items.map { it.path },
        confirmedIn: String = session,
        batchFiles: Int = 2,
    ) = FreeOriginals(
        rig.engine(),
        run = { ops, onResult ->
            executor(onResult).run(ActionPlan("p", ops, "move").confirm(Confirmation.of(shown, 1), confirmedIn))
        },
        batchFiles = batchFiles,
    )

    fun copyOf(f: MoveFile) = "$USB/${f.relTarget}"

    fun files() = rig.store.files.values.sortedBy { it.index }
}
