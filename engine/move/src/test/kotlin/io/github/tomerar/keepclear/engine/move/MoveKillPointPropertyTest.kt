// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.move

import io.github.tomerar.keepclear.core.hash.Sha256
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.Random

/**
 * No-loss invariant for MOVE (edge-cases.md): kill the job at any storage operation, resume until done.
 * Sources never change; every VERIFIED copy matches its source; no temp files remain; SHA256SUMS lists
 * exactly the verified files.
 */
class MoveKillPointPropertyTest {
    private class Killed : RuntimeException()

    @Test
    fun `resume after 500 random kills always ends with correct copies`(): Unit = runBlocking {
        val random = Random(11)
        repeat(500) { iteration ->
            val rig = Rig(files = 4)
            val before = rig.sourceSnapshot()
            val jobId = rig.newJob()
            var kills = 0
            while (true) {
                var budget = random.nextInt(60)
                rig.fake.beforeOp = { _, _ -> if (budget-- == 0) throw Killed() }
                val done = try {
                    rig.engine().run(jobId).state == JobState.DONE
                } catch (e: Killed) {
                    kills++
                    false
                }
                rig.fake.beforeOp = null
                if (done) break
                check(kills < 200) { "iteration $iteration never finished" }
            }
            check(rig.sourceSnapshot() == before) { "iteration $iteration: a source changed" }
            val verified = rig.store.files.values.filter { it.state == MoveFileState.VERIFIED }
            check(verified.size == 4) { "iteration $iteration: ${rig.store.files.values.map { it.state }}" }
            for (f in verified) {
                check(
                    Sha256.of(rig.fake.readAllBytes("$USB/${f.relTarget}")) ==
                        Sha256.of(rig.fake.readAllBytes(f.sourcePath)),
                ) {
                    "iteration $iteration: copy differs for ${f.relTarget}"
                }
            }
            check(rig.fake.files(USB).none { ".kc-tmp" in it }) { "iteration $iteration: temp files left" }
            val sums = rig.fake.readAllBytes(
                "$USB/KeepClear/OnePlus 10T/SHA256SUMS",
            ).toString(Charsets.UTF_8).lines().filter {
                it.isNotBlank()
            }
            check(sums.size == 4) { "iteration $iteration: SHA256SUMS has ${sums.size} lines" }
        }
    }
}
