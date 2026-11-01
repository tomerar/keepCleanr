// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import io.github.tomerar.keepclear.core.safety.ActionPlan
import io.github.tomerar.keepclear.core.safety.OpKind
import io.github.tomerar.keepclear.core.safety.PlannedOp
import io.github.tomerar.keepclear.core.safety.SafetyGuard
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.engine.move.MoveJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** H3: every backup folder KeepClear writes to is PROTECTED at the choke point (CLAUDE.md safety rule 4). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BackupProtectionTest {
    private val tree = "content://com.android.externalstorage.documents/tree/1234-ABCD%3ABackups"

    @Test
    fun `backup roots come from the jobs' target folders`() {
        val jobs = listOf(
            MoveJob("a", "t", "SanDisk", "KeepClear/Phone", 1, targetRef = tree),
            MoveJob("b", "t", "Drive", "KeepClear/Phone", 1, targetRef = "content://org.example.docs/tree/x"),
            MoveJob("c", "t", "Old", "KeepClear/Phone", 1, targetRef = null),
        )
        assertEquals(listOf("/storage/1234-ABCD/Backups"), BackupRoots.of(jobs))
    }

    @Test
    fun `a clean plan touching the backup folder is rejected by the guard`() {
        val jobs = listOf(MoveJob("a", "t", "SanDisk", "KeepClear/Phone", 1, targetRef = tree))
        val paths = BackupRoots.protectedPaths(jobs)
        val inBackup = "/storage/1234-ABCD/Backups/KeepClear/Phone/Camera/2021/01/IMG_1.jpg"
        val plan = ActionPlan(
            "p",
            listOf(PlannedOp(KPath.Local(inBackup), inBackup, OpKind.TRASH, false, 1, 1)),
            "clean",
        ).confirm(1, "s")
        assertTrue(SafetyGuard(paths).check(plan, "s") is SafetyGuard.Verdict.Rejected)
    }
}
