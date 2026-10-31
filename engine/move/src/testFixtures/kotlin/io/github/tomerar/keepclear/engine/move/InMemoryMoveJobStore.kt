// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.move

class InMemoryMoveJobStore : MoveJobStore {
    val jobs = linkedMapOf<String, MoveJob>()
    val files = linkedMapOf<Pair<String, Int>, MoveFile>()

    override suspend fun putJob(job: MoveJob) {
        jobs[job.id] = job
    }

    override suspend fun job(id: String) = jobs[id]

    override suspend fun putFile(file: MoveFile) {
        files[file.jobId to file.index] = file
    }

    override suspend fun files(jobId: String) = files.values.filter { it.jobId == jobId }.sortedBy { it.index }
}
