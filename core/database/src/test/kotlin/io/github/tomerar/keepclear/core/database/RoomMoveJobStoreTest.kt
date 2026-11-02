// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.tomerar.keepclear.core.database.RoomMoveJobStore.Companion.decode
import io.github.tomerar.keepclear.core.database.RoomMoveJobStore.Companion.encode
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.engine.move.FailReason
import io.github.tomerar.keepclear.engine.move.JobState
import io.github.tomerar.keepclear.engine.move.MoveFile
import io.github.tomerar.keepclear.engine.move.MoveFileState
import io.github.tomerar.keepclear.engine.move.MoveJob
import io.github.tomerar.keepclear.engine.move.PauseReason
import io.github.tomerar.keepclear.engine.move.VerifyMethod
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** MOVE-11 / MIG-4: job state survives in Room with stable names; unknown names fall back safely. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RoomMoveJobStoreTest {
    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        KeepClearDatabase::class.java,
    )
        .allowMainThreadQueries()
        .build()
    private val store = RoomMoveJobStore(db.moveDao())

    @After
    fun close() = db.close()

    private val file = MoveFile(
        jobId = "j1",
        index = 0,
        source = KPath.Local("/storage/emulated/0/DCIM/Camera/תמונה 1.jpg"),
        sourcePath = "/storage/emulated/0/DCIM/Camera/תמונה 1.jpg",
        sourceSize = 10,
        sourceMtime = 20,
        sourceIdentity = "inode:1",
        relTarget = "KeepClear/OnePlus 10T/Camera/2021/01/תמונה 1.jpg",
        state = MoveFileState.VERIFIED,
        sourceSha256 = "ab".repeat(32),
        attempts = 1,
        failReason = null,
        verifiedAt = 99,
        nameChanged = true,
        reusedExisting = true,
        confirmedAt = 120,
    )

    @Test
    fun `jobs and files round-trip`(): Unit = runBlocking {
        val job = MoveJob(
            "j1",
            "tree:1|marker:2",
            "SanDisk",
            "KeepClear/OnePlus 10T",
            5,
            JobState.PAUSED,
            PauseReason.TARGET_DISCONNECTED,
            "content://x/tree/y",
            verifyBootId = "7",
        )
        store.putJob(job)
        val failed = file.copy(
            index = 1,
            state = MoveFileState.FAILED,
            failReason = FailReason.TOO_LARGE_FOR_TARGET,
            verifiedAt = null,
            confirmedAt = null,
        )
        store.putFile(file)
        store.putFile(failed)
        store.job("j1") shouldBe job
        store.files("j1") shouldBe listOf(file, failed)
        store.putFile(file.copy(attempts = 2))
        store.files("j1").first().attempts shouldBe 2
        // M17b: the verification method round-trips; an unknown stored value is "not recorded", never guessed.
        store.putFile(file.copy(verifiedBy = VerifyMethod.SERVER_DIGEST))
        store.files("j1").first().verifiedBy shouldBe VerifyMethod.SERVER_DIGEST
        db.openHelper.writableDatabase.execSQL("UPDATE move_file SET verifiedBy = 'FROM_THE_FUTURE'")
        store.files("j1").first().verifiedBy shouldBe null
    }

    @Test
    fun `an unknown stored state never reads back as verified`(): Unit = runBlocking {
        store.putFile(file)
        db.openHelper.writableDatabase.execSQL("UPDATE move_file SET state = 'FROM_THE_FUTURE'")
        val read = store.files("j1").single()
        read.state shouldBe MoveFileState.PENDING
        read.verifiedAt shouldBe null
    }

    @Test
    fun `source paths encode and decode losslessly`() {
        listOf(
            KPath.Local("/storage/emulated/0/a:b|c.jpg"),
            KPath.Media("external_primary", 42),
            KPath.Saf("content://com.android.externalstorage.documents/tree/1234-5678%3A", "1234-5678:DCIM/a:b"),
        ).forEach { decode(encode(it)) shouldBe it }
    }
}
