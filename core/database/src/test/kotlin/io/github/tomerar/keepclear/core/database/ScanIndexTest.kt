// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.engine.scan.ScanItem
import io.github.tomerar.keepclear.engine.scan.Source
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ScanIndexTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), KeepClearDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val index = ScanIndex(db) { 1000L }

    @After
    fun close() = db.close()

    private fun item(path: String, size: Long = 1) = ScanItem(KPath.Local(path), path, size, 5, "i")

    @Test
    fun `round-trips local, media and saf refs`(): Unit = runBlocking {
        val run = index.startRun()
        val items = listOf(
            item("/storage/emulated/0/DCIM/תמונה.jpg"),
            ScanItem(KPath.Media("external_primary", 42), "/storage/emulated/0/DCIM/b.jpg", 2, 3, mediaId = 42, source = Source.MEDIA_STORE),
            ScanItem(KPath.Saf("content://tree/x", "primary:a:b"), "/storage/emulated/0/a/b", 3, 4),
        )
        index.write(run, items)
        index.finish(run, complete = true, files = 3, bytes = 6, generation = 9)
        index.previous().values.sortedBy { it.path } shouldBe items.sortedBy { it.path }
        index.lastGeneration() shouldBe 9
    }

    @Test
    fun `complete scan removes vanished items, incomplete scan keeps them`(): Unit = runBlocking {
        val first = index.startRun()
        index.write(first, listOf(item("/a"), item("/b")))
        index.finish(first, true, 2, 2, null)

        val partial = index.startRun()
        index.write(partial, listOf(item("/a", size = 9)))
        index.finish(partial, complete = false, files = 1, bytes = 9, generation = null)
        index.previous().keys shouldBe setOf("/a", "/b")

        val full = index.startRun()
        index.write(full, listOf(item("/a")))
        index.finish(full, complete = true, files = 1, bytes = 1, generation = null)
        index.previous().keys shouldBe setOf("/a")
    }
}
