// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import io.github.tomerar.keepclear.core.model.Category
import io.github.tomerar.keepclear.core.safety.OpKind
import io.github.tomerar.keepclear.core.safety.PlannedOp
import io.github.tomerar.keepclear.core.safety.SafetyGuard
import io.github.tomerar.keepclear.core.safety.SkipReason
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.engine.classify.Classifier
import io.github.tomerar.keepclear.engine.move.MoveJob
import io.github.tomerar.keepclear.engine.scan.ScanItem
import org.junit.Assert.assertEquals
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
    fun `a clean plan touching the backup folder is refused by the guard, per item (D5)`() {
        val jobs = listOf(MoveJob("a", "t", "SanDisk", "KeepClear/Phone", 1, targetRef = tree))
        val paths = BackupRoots.protectedPaths(jobs)
        val inBackup = "/storage/1234-ABCD/Backups/KeepClear/Phone/Camera/2021/01/IMG_1.jpg"
        val op = PlannedOp(KPath.Local(inBackup), inBackup, OpKind.TRASH, false, 1, 1)
        assertEquals(SkipReason.PROTECTED, SafetyGuard(paths).skipReason(op))
    }

    @Test
    fun `the backup drive is never scanned, the phone and other drives are (D5)`() {
        val jobs = listOf(MoveJob("a", "t", "SanDisk", "KeepClear/Phone", 1, targetRef = tree))
        val excluded = BackupRoots.scanExclusions(BackupRoots.of(jobs))
        assertEquals(listOf("/storage/1234-ABCD/Backups", "/storage/1234-ABCD"), excluded)
        val roots = ScanRoots.of(
            allFiles = true,
            volumes = listOf("/storage/emulated/0", "/storage/1234-abcd", "/storage/5678-EF01"),
            clonedReadable = listOf("/storage/emulated/999"),
            granted = emptyList(),
            excluded = excluded,
        )
        assertEquals(
            listOf("/storage/emulated/0", "/storage/5678-EF01", "/storage/emulated/999"),
            roots.map { it.absolute },
        )
    }

    @Test
    fun `the classifier protects the same backup folders as the executor (D5)`() {
        val jobs = listOf(MoveJob("a", "t", "SanDisk", "KeepClear/Phone", 1, targetRef = tree))
        val classifier = Classifier(protectedPaths = BackupRoots.protectedPaths(jobs))
        val inBackup = "/storage/1234-ABCD/Backups/KeepClear/Phone/Camera/2021/01/IMG_1.jpg"
        val item = ScanItem(KPath.Local(inBackup), inBackup, 1, 1)
        assertEquals(Category.PROTECTED, classifier.classify(item, now = 2).category)
    }
}
