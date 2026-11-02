// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.move

import io.github.tomerar.keepclear.backup.BackupTarget
import io.github.tomerar.keepclear.core.storage.FakeStorageGateway
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.core.storage.StorageException
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.InputStream

/** D6: "Release now" first re-checks that the backup copy is still there, with the same size and SHA-256. */
class ReleaseCheckTest {
    private val rig = Rig()

    /** Backs everything up and marks it confirmed by the second check (H1). */
    private suspend fun backedUp(): List<MoveFile> {
        val id = rig.newJob()
        rig.engine().run(id).state shouldBe JobState.DONE
        rig.store.files(id).forEach { rig.store.putFile(it.copy(confirmedAt = rig.now)) }
        return rig.store.files(id)
    }

    private suspend fun check(target: BackupTarget? = rig.target, paths: List<String>): Set<String> {
        val jobs = listOfNotNull(rig.store.job("job1"))
        return ReleaseCheck(rig.store, jobs) { target }.intact(paths)
    }

    @Test
    fun `intact copies may be released`(): Unit = runBlocking {
        val files = backedUp()
        check(paths = files.map { it.sourcePath }) shouldBe files.map { it.sourcePath }.toSet()
    }

    @Test
    fun `a copy that is gone, or changed with the same size, keeps its original in the trash`(): Unit = runBlocking {
        val files = backedUp()
        rig.fake.delete(KPath.Local("$USB/${files[0].relTarget}"))
        val damaged = "$USB/${files[1].relTarget}"
        rig.fake.modify(damaged, FakeStorageGateway.Content.Bytes(ByteArray(files[1].sourceSize.toInt()) { 7 }))
        check(paths = files.map { it.sourcePath }) shouldBe files.drop(2).map { it.sourcePath }.toSet()
    }

    @Test
    fun `a copy confirmed only by the first check is never released (H1)`(): Unit = runBlocking {
        val files = backedUp()
        rig.store.putFile(files[0].copy(confirmedAt = null))
        check(paths = listOf(files[0].sourcePath)).shouldBeEmpty()
    }

    @Test
    fun `an unavailable or different backup place releases nothing`(): Unit = runBlocking {
        val files = backedUp()
        check(target = null, paths = files.map { it.sourcePath }).shouldBeEmpty()
        val other = object : BackupTarget by rig.target {
            override val id = "another-drive"
        }
        check(target = other, paths = files.map { it.sourcePath }).shouldBeEmpty()
    }

    @Test
    fun `a read that fails or returns other bytes counts as not intact`(): Unit = runBlocking {
        val files = backedUp()
        val failing = object : BackupTarget by rig.target {
            override suspend fun openRead(rel: String): InputStream =
                throw StorageException.Disconnected(KPath.Local("$USB/$rel"))
        }
        check(target = failing, paths = files.map { it.sourcePath }).shouldBeEmpty()
        rig.corruptReads = 1
        check(paths = listOf(files[0].sourcePath)).shouldBeEmpty()
    }
}
