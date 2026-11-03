// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.move

import io.github.tomerar.keepclear.core.hash.Sha256
import io.github.tomerar.keepclear.core.storage.FakeStorageGateway
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.Random

/**
 * The no-loss invariant after owner decision P1 (2026-11-03): random backups (with or without the second check), random
 * damage to copies and originals afterwards, random test mode and "keep in trash" settings, and "Free up space" killed
 * at a random storage operation (the process dies: an [Error] no layer catches), then a restart (new trash manager,
 * recovery) and freeing again. After every attempt, every original is either at its path, byte-identical to what the
 * user had, or **verified twice** on the target: the second check passed and its copy on the target hashes to exactly
 * that content now. Never neither.
 */
class FreeKillPointPropertyTest {
    private class Killed : Error()

    @Test
    fun `no original is lost across 600 random free plans and kill points`(): Unit = runBlocking {
        val random = Random(23)
        var deleted = 0
        var kills = 0
        repeat(600) { iteration ->
            val r = FreeRig(files = 1 + random.nextInt(6))
            val id = r.backup(secondCheck = random.nextInt(5) != 0)
            val files = r.files()
            // Damage after the backup: a copy changed on the drive, an original edited (same size, maybe same date).
            for (f in files) {
                when (random.nextInt(8)) {
                    0 -> r.fake.modify(r.copyOf(f), FakeStorageGateway.Content.Bytes(ByteArray(f.sourceSize.toInt())))

                    1 -> r.fake.modify(
                        f.sourcePath,
                        FakeStorageGateway.Content.Bytes(ByteArray(f.sourceSize.toInt()) { 9 }),
                        mtime = if (random.nextBoolean()) f.sourceMtime else f.sourceMtime + 1,
                    )

                    else -> Unit
                }
            }
            // What the user has now: the content each original must keep, unless a matching copy is verified twice.
            val content = files.associate { it.sourcePath to Sha256.of(r.fake.readAllBytes(it.sourcePath)) }
            r.dryRun = random.nextInt(4) == 0
            r.forGood = random.nextInt(4) != 0
            val chosen = files.filter { random.nextInt(5) != 0 }.map { it.sourcePath }.toSet()
            repeat(1 + random.nextInt(3)) { attempt ->
                var budget = random.nextInt(80)
                r.fake.beforeOp = { _, _ -> if (budget-- == 0) throw Killed() }
                try {
                    r.freer(shown = chosen, confirmedIn = r.session, batchFiles = 1 + random.nextInt(3))
                        .free(id, chosen)
                } catch (e: Killed) {
                    kills++
                }
                r.fake.beforeOp = null
                // Restart: a new process (session), a new trash manager that recovers its journal.
                r.session = "s-$attempt"
                r.trash = r.newTrash()
                r.trash.recover()
                assertNoLoss(r, content, "iteration $iteration attempt $attempt")
            }
            deleted += files.count { !r.fake.exists(it.sourcePath) }
        }
        // The property must have been exercised: originals were really freed, and processes really died.
        check(deleted > 300) { "only $deleted originals freed" }
        check(kills > 100) { "only $kills kills" }
    }

    private fun assertNoLoss(r: FreeRig, content: Map<String, String>, where: String) {
        val records = r.files().associateBy { it.sourcePath }
        for ((path, sha) in content) {
            val atPath = r.fake.exists(path) && Sha256.of(r.fake.readAllBytes(path)) == sha
            if (atPath) continue
            val f = records.getValue(path)
            val copy = r.copyOf(f)
            val twice = f.state == MoveFileState.VERIFIED && f.confirmedAt != null && f.sourceSha256 == sha &&
                r.fake.exists(copy) && Sha256.of(r.fake.readAllBytes(copy)) == sha
            check(twice) { "$where: $path is neither on the phone nor verified twice on the drive" }
            // Never both gone from its path and still half-there: a copy left in the trash must be the same file.
            r.index.entries.values.filter { it.originalPath == path }.forEach {
                check(!r.fake.exists(it.trashedPath) || Sha256.of(r.fake.readAllBytes(it.trashedPath)) == sha) {
                    "$where: the trash holds other content for $path"
                }
            }
        }
    }
}
