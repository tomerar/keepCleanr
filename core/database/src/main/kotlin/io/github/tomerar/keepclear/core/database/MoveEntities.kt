// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.engine.move.FailReason
import io.github.tomerar.keepclear.engine.move.JobState
import io.github.tomerar.keepclear.engine.move.MoveFile
import io.github.tomerar.keepclear.engine.move.MoveFileState
import io.github.tomerar.keepclear.engine.move.MoveJob
import io.github.tomerar.keepclear.engine.move.MoveJobStore
import io.github.tomerar.keepclear.engine.move.PauseReason
import kotlinx.coroutines.flow.Flow

/**
 * MOVE job state (MOVE-11). Enum values are stored by name and must never be renamed (MIG-2); unknown
 * names read back as the safest state ([MoveFileState.PENDING]: copy again and verify).
 */
@Entity(tableName = "move_job", primaryKeys = ["id"])
data class MoveJobEntity(
    val id: String,
    val targetId: String,
    val targetLabel: String,
    val rootRel: String,
    val createdAt: Long,
    val state: String,
    val pauseReason: String?,
    val targetRef: String?,
)

@Entity(tableName = "move_file", primaryKeys = ["jobId", "fileIndex"])
data class MoveFileEntity(
    val jobId: String,
    val fileIndex: Int,
    /** Encoded [KPath]: "local:<absolute>", "media:<id>:<volume>" or "saf:<len>:<tree><docId>". */
    val source: String,
    val sourcePath: String,
    val sourceSize: Long,
    val sourceMtime: Long,
    val sourceIdentity: String?,
    val relTarget: String,
    val state: String,
    val sourceSha256: String?,
    val attempts: Int,
    val failReason: String?,
    val verifiedAt: Long?,
    val nameChanged: Boolean,
    val reusedExisting: Boolean,
)

@Dao
interface MoveDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putJob(job: MoveJobEntity)

    @Query("SELECT * FROM move_job WHERE id = :id")
    suspend fun job(id: String): MoveJobEntity?

    @Query("SELECT * FROM move_job ORDER BY createdAt DESC")
    fun jobs(): Flow<List<MoveJobEntity>>

    @Query("SELECT * FROM move_job ORDER BY createdAt DESC")
    suspend fun allJobs(): List<MoveJobEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putFile(file: MoveFileEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putFiles(files: List<MoveFileEntity>)

    @Query("SELECT * FROM move_file WHERE jobId = :jobId ORDER BY fileIndex")
    suspend fun files(jobId: String): List<MoveFileEntity>

    @Query("SELECT * FROM move_file WHERE jobId = :jobId ORDER BY fileIndex")
    fun filesFlow(jobId: String): Flow<List<MoveFileEntity>>
}

class RoomMoveJobStore(private val dao: MoveDao) : MoveJobStore {
    override suspend fun putJob(job: MoveJob) = dao.putJob(job.toEntity())

    override suspend fun job(id: String) = dao.job(id)?.toJob()

    override suspend fun putFile(file: MoveFile) = dao.putFile(file.toEntity())

    override suspend fun files(jobId: String) = dao.files(jobId).map { it.toFile() }

    suspend fun putFiles(files: List<MoveFile>) = dao.putFiles(files.map { it.toEntity() })

    suspend fun jobs(): List<MoveJob> = dao.allJobs().map { it.toJob() }

    companion object {
        fun MoveJob.toEntity() =
            MoveJobEntity(id, targetId, targetLabel, rootRel, createdAt, state.name, pauseReason?.name, targetRef)

        fun MoveJobEntity.toJob() = MoveJob(
            id = id,
            targetId = targetId,
            targetLabel = targetLabel,
            rootRel = rootRel,
            createdAt = createdAt,
            // An unknown state never claims the job is done.
            state = enumOrNull<JobState>(state) ?: JobState.READY,
            pauseReason = pauseReason?.let { enumOrNull<PauseReason>(it) },
            targetRef = targetRef,
        )

        fun MoveFile.toEntity() = MoveFileEntity(
            jobId = jobId,
            fileIndex = index,
            source = encode(source),
            sourcePath = sourcePath,
            sourceSize = sourceSize,
            sourceMtime = sourceMtime,
            sourceIdentity = sourceIdentity,
            relTarget = relTarget,
            state = state.name,
            sourceSha256 = sourceSha256,
            attempts = attempts,
            failReason = failReason?.name,
            verifiedAt = verifiedAt,
            nameChanged = nameChanged,
            reusedExisting = reusedExisting,
        )

        fun MoveFileEntity.toFile(): MoveFile {
            val known = enumOrNull<MoveFileState>(state)
            return MoveFile(
                jobId = jobId,
                index = fileIndex,
                source = decode(source),
                sourcePath = sourcePath,
                sourceSize = sourceSize,
                sourceMtime = sourceMtime,
                sourceIdentity = sourceIdentity,
                relTarget = relTarget,
                // Unknown state → copy and verify again; a VERIFIED claim is never invented.
                state = known ?: MoveFileState.PENDING,
                sourceSha256 = sourceSha256,
                attempts = attempts,
                failReason = failReason?.let { enumOrNull<FailReason>(it) },
                verifiedAt = if (known == MoveFileState.VERIFIED) verifiedAt else null,
                nameChanged = nameChanged,
                reusedExisting = reusedExisting,
            )
        }

        fun encode(path: KPath): String = when (path) {
            is KPath.Local -> "local:${path.absolute}"
            is KPath.Media -> "media:${path.id}:${path.volume}"
            is KPath.Saf -> "saf:${path.treeUri.length}:${path.treeUri}${path.documentId}"
        }

        fun decode(value: String): KPath {
            val kind = value.substringBefore(':')
            val rest = value.substringAfter(':')
            return when (kind) {
                "local" -> {
                    KPath.Local(rest)
                }

                "media" -> {
                    KPath.Media(rest.substringAfter(':'), rest.substringBefore(':').toLong())
                }

                "saf" -> {
                    val len = rest.substringBefore(':').toInt()
                    val body = rest.substringAfter(':')
                    KPath.Saf(body.substring(0, len), body.substring(len))
                }

                else -> {
                    error("Unsupported stored source kind: $kind")
                }
            }
        }

        private inline fun <reified E : Enum<E>> enumOrNull(name: String): E? =
            enumValues<E>().firstOrNull { it.name == name }
    }
}
