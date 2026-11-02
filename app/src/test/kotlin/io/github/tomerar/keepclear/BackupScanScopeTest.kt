// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.test.core.app.ApplicationProvider
import io.github.tomerar.keepclear.core.model.Category
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.engine.move.MoveJob
import io.github.tomerar.keepclear.engine.scan.ScanItem
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * D5, through KeepClear's real dependency graph: once a backup went to a drive, Home's scan leaves that drive out
 * and Home's classifier protects its backup folder exactly like the executor does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BackupScanScopeTest {
    private val app = KeepClearApplication().apply {
        val attach = android.content.ContextWrapper::class.java
            .getDeclaredMethod("attachBaseContext", android.content.Context::class.java)
        attach.isAccessible = true
        attach.invoke(this, ApplicationProvider.getApplicationContext<android.app.Application>())
    }

    @After
    fun close() = app.database.close()

    private val tree = "content://com.android.externalstorage.documents/tree/1234-ABCD%3ABackups"
    private val inBackup = "/storage/1234-ABCD/Backups/KeepClear/Phone/Camera/2021/01/IMG_1.jpg"

    @Test
    fun `Home's classifier and scan scope know the backup drive (D5)`(): Unit = runBlocking {
        val item = ScanItem(KPath.Local(inBackup), inBackup, 1, 1)
        // Before any backup is known: just a photo on a card, offered for review.
        assertEquals(Category.REVIEW, homeClassifier(app).classify(item, now = 2).category)
        app.moveController.store.putJob(MoveJob("a", "t", "SanDisk", "KeepClear/Phone", 1, targetRef = tree))
        assertEquals(Category.PROTECTED, homeClassifier(app).classify(item, now = 2).category)
        assertEquals(listOf("/storage/1234-ABCD/Backups", "/storage/1234-ABCD"), backupScanExclusions(app))
        val scanner = backupAwareScanner(app, backupScanExclusions(app))
        assertEquals(true, scanner.isExcluded(inBackup))
        assertEquals(false, scanner.isExcluded("/storage/emulated/0/DCIM/Camera/IMG_1.jpg"))
    }
}
