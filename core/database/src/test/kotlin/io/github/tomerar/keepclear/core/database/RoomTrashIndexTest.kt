// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.tomerar.keepclear.engine.trash.TrashEntry
import io.github.tomerar.keepclear.engine.trash.TrashState
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RoomTrashIndexTest {
    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        KeepClearDatabase::class.java,
    )
        .allowMainThreadQueries()
        .build()
    private val index = RoomTrashIndex(db.trashDao())

    @After
    fun close() = db.close()

    @Test
    fun `round-trips entries and looks them up by trashed path`(): Unit = runBlocking {
        val e = TrashEntry(
            id = "id-1",
            originalPath = "/storage/emulated/0/DCIM/תמונה.jpg",
            trashedPath = "/storage/emulated/0/.KeepClearTrash/20261031/id-1/תמונה.jpg",
            volumeRoot = "/storage/emulated/0",
            size = 10,
            mtimeMillis = 1,
            sha256 = null,
            ruleId = "camera",
            reason = "TRASH",
            deletedAt = 5,
            expiresAt = 6,
            deletedAtElapsed = 7,
            bootId = "b",
            state = TrashState.PENDING,
            trusted = false,
            verifiedCopy = "drive:x",
        )
        index.put(e)
        index.get("id-1") shouldBe e
        index.byTrashedPath(e.trashedPath) shouldBe e
        index.put(e.copy(state = TrashState.ACTIVE))
        index.all().single().state shouldBe TrashState.ACTIVE
        index.remove("id-1")
        index.all() shouldBe emptyList()
    }
}
