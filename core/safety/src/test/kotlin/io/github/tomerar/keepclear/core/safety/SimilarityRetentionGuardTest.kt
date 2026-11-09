// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.safety

import io.github.tomerar.keepclear.core.storage.FakeStorageGateway
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.core.storage.StorageException
import io.github.tomerar.keepclear.core.storage.StorageGateway
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * G4b (GAL-8, GAL-3, AC-G6, E-G07/E-G09/E-G19, C227/C228): the similar-photos keep-one guard at the choke point.
 * Similar is never equal: the evidence is checked against the live files, never against a hash, and it can never make
 * an op permanent, a witness or a backup.
 */
class SimilarityRetentionGuardTest {
    private val fake = FakeStorageGateway(caseInsensitive = true)
    private val handler = RecordingHandler(moveToTrash = true)
    private var kept = emptySet<String>()

    private fun executor(protectedPaths: ProtectedPaths = ProtectedPaths(), dryRun: Boolean = false) = ActionExecutor(
        fake,
        SafetyGuard(protectedPaths),
        protectedPaths,
        handler,
        SESSION,
        dryRun = dryRun,
        keptNow = { it in kept },
    )

    private val dir = "$ROOT/Pictures/Saved"
    private val a = "$dir/a.jpg"
    private val b = "$dir/b.jpg"
    private val c = "$dir/c.jpg"
    private val d = "$dir/d.jpg"

    init {
        fake.addFile(a, size = 1000, mtime = 10)
        fake.addFile(b, size = 2000, mtime = 20)
        fake.addFile(c, size = 3000, mtime = 30)
        fake.addFile(d, size = 4000, mtime = 40)
    }

    private suspend fun member(path: String, gateway: StorageGateway = fake): RetainedMember {
        val s = gateway.stat(KPath.Local(path))!!
        return RetainedMember(s.path, path, s.size, s.mtimeMillis, s.identity)
    }

    private suspend fun group(id: String, vararg keep: String) =
        VisualRetention(id, "v-$id", keep.map { member(it) })

    /** A REVIEW photo moved to the trash from one or more similar-photos groups. */
    private suspend fun photo(path: String, vararg groups: VisualRetention) =
        fake.op(path).copy(ruleId = "ai_images", visualGroups = groups.toList())

    private fun ran(report: ExecutionReport) = report as ExecutionReport.Ran

    private fun rejected(report: ExecutionReport): List<String> {
        report.shouldBeInstanceOf<ExecutionReport.Rejected>()
        handler.performed.shouldBeEmpty()
        listOf(a, b, c, d).forEach { fake.exists(it) shouldBe true }
        return report.reasons
    }

    private fun refused(report: ExecutionReport, vararg paths: String) {
        val r = ran(report)
        r.skipped.map { it.op.displayPath to it.reason } shouldContainExactly
            paths.map { it to SkipReason.RETAINED_MEMBER_GONE }
        paths.forEach { p -> handler.performed.none { it.displayPath == p } shouldBe true }
    }

    // ------------------------------------------------------------------ the retained photo holds

    @Test
    fun `a photo is moved to the trash while the photo kept from its group is unchanged`(): Unit = runBlocking {
        val g = group("g1", b)
        val report = ran(executor().run(plan(photo(a, g))))
        report.done.map { it.op.displayPath } shouldContainExactly listOf(a)
        handler.performed.single().kind shouldBe OpKind.TRASH
        fake.exists(a) shouldBe false
        fake.exists(b) shouldBe true
    }

    @Test
    fun `test mode checks the kept photo too, and changes nothing (SAFETY-9)`(): Unit = runBlocking {
        val g = group("g1", b)
        ran(executor(dryRun = true).run(plan(photo(a, g)))).results.single().shouldBeInstanceOf<OpResult.Simulated>()
        fake.modify(b, FakeStorageGateway.Content.Virtual(1, 2001), mtime = 21)
        refused(executor(dryRun = true).run(plan(photo(a, g))), a)
        handler.performed.shouldBeEmpty()
        fake.exists(a) shouldBe true
    }

    // ------------------------------------------------------------------ the retained photo no longer holds

    @Test
    fun `a kept photo that changed size refuses the group (E-G07)`(): Unit = runBlocking {
        val g = group("g1", b)
        fake.modify(b, FakeStorageGateway.Content.Virtual(1, 2001), mtime = 20)
        refused(executor().run(plan(photo(a, g))), a)
        fake.exists(a) shouldBe true
    }

    @Test
    fun `a kept photo with the same size but a new time refuses the group (E-G07)`(): Unit = runBlocking {
        val g = group("g1", b)
        fake.setMtime(b, 21)
        refused(executor().run(plan(photo(a, g))), a)
    }

    @Test
    fun `a kept photo replaced at the same path, size and time refuses the group (identity, T-G5)`(): Unit =
        runBlocking {
            val g = group("g1", b)
            fake.delete(KPath.Local(b))
            fake.addFile(b, size = 2000, mtime = 20)
            refused(executor().run(plan(photo(a, g))), a)
        }

    @Test
    fun `a kept photo that is missing, a link or a folder refuses the group (E-G19)`(): Unit = runBlocking {
        val g = group("g1", b)
        fake.delete(KPath.Local(b))
        refused(executor().run(plan(photo(a, g))), a)
        fake.addSymlink(b, c, mtime = 20)
        refused(executor().run(plan(photo(a, g))), a)
        fake.delete(KPath.Local(b))
        fake.mkdir(KPath.Local(dir), "b.jpg")
        refused(executor().run(plan(photo(a, g))), a)
    }

    @Test
    fun `a kept photo now a link or a folder with the same facts refuses the group`(): Unit = runBlocking {
        val g = group("g1", b)
        for (kind in listOf(
            io.github.tomerar.keepclear.core.storage.Stat.Kind.SYMLINK,
            io.github.tomerar.keepclear.core.storage.Stat.Kind.DIRECTORY,
        )) {
            val swapped = object : StorageGateway by fake {
                override suspend fun stat(path: KPath) = fake.stat(path)?.let {
                    if (path == KPath.Local(b)) it.copy(kind = kind) else it
                }
            }
            val exec = ActionExecutor(swapped, SafetyGuard(ProtectedPaths()), ProtectedPaths(), handler, SESSION)
            ran(exec.run(plan(photo(a, g)))).skipped.single().reason shouldBe SkipReason.RETAINED_MEMBER_GONE
        }
        fake.exists(a) shouldBe true
    }

    @Test
    fun `a kept MediaStore row without an identity is refused before anything runs`(): Unit = runBlocking {
        val row = VisualRetention(
            "g1",
            "v",
            listOf(RetainedMember(KPath.Media("external_primary", 9), b, 2000, 20, identity = null)),
        )
        rejected(executor().run(plan(photo(a, row)))).any { "without an identity" in it } shouldBe true
    }

    @Test
    fun `a kept photo moved to KeepClear's trash refuses the group`(): Unit = runBlocking {
        val g = group("g1", b)
        fake.mkdir(KPath.Local(ROOT), ".KeepClearTrash")
        fake.rename(KPath.Local(b), KPath.Local("$ROOT/.KeepClearTrash"), "b.jpg")
        refused(executor().run(plan(photo(a, g))), a)
    }

    @Test
    fun `a kept photo whose identity says it is in Android's trash refuses the group`(): Unit = runBlocking {
        // A MediaStore row keeps its size and time when Android trashes it; only its identity says so.
        val row = KPath.Media("external_primary", 9)
        val media = object : StorageGateway by fake {
            var trashed = false

            override suspend fun stat(path: KPath) = if (path == row) {
                io.github.tomerar.keepclear.core.storage.Stat(
                    row,
                    "b.jpg",
                    io.github.tomerar.keepclear.core.storage.Stat.Kind.FILE,
                    2000,
                    20,
                    "gen:5;trashed:${if (trashed) 1 else 0}",
                )
            } else {
                fake.stat(path)
            }
        }
        val g = VisualRetention("g1", "v", listOf(RetainedMember(row, b, 2000, 20, "gen:5;trashed:0")))
        val exec = ActionExecutor(media, SafetyGuard(ProtectedPaths()), ProtectedPaths(), handler, SESSION)
        ran(exec.run(plan(photo(c, g)))).done.size shouldBe 1
        media.trashed = true
        val r = ran(exec.run(plan(photo(d, g))))
        r.skipped.single().reason shouldBe SkipReason.RETAINED_MEMBER_GONE
        fake.exists(d) shouldBe true
    }

    @Test
    fun `a kept photo whose identity can no longer be read refuses the group`(): Unit = runBlocking {
        val g = group("g1", b)
        val noIdentity = object : StorageGateway by fake {
            override suspend fun stat(path: KPath) = fake.stat(path)?.let {
                if (path == KPath.Local(b)) it.copy(identity = null) else it
            }
        }
        val exec = ActionExecutor(noIdentity, SafetyGuard(ProtectedPaths()), ProtectedPaths(), handler, SESSION)
        ran(exec.run(plan(photo(a, g)))).skipped.single().reason shouldBe SkipReason.RETAINED_MEMBER_GONE
    }

    @Test
    fun `a kept photo that can't be read refuses the group, the run goes on`(): Unit = runBlocking {
        val g = group("g1", b)
        fake.fail(FakeStorageGateway.Op.STAT, times = 10, matches = { it == KPath.Local(b) }) {
            StorageException.PermissionDenied(it)
        }
        val other = fake.op(d).copy(ruleId = "ai_images")
        val r = ran(executor().run(plan(photo(a, g), other)))
        r.skipped.single().op.displayPath shouldBe a
        r.done.single().op.displayPath shouldBe d
    }

    @Test
    fun `a kept photo the user kept since, or now protected, refuses the group (E-G09)`(): Unit = runBlocking {
        val g = group("g1", b)
        kept = setOf(b)
        refused(executor().run(plan(photo(a, g))), a)
        kept = emptySet()
        refused(executor(ProtectedPaths(extraRoots = listOf(dir.lowercase() + "/b.jpg"))).run(plan(photo(a, g))), a)
        fake.exists(a) shouldBe true
    }

    @Test
    fun `one changed kept photo refuses the group even when another kept photo is fine`(): Unit = runBlocking {
        val g = group("g1", b, c)
        fake.setMtime(c, 31)
        refused(executor().run(plan(photo(a, g))), a)
    }

    // ------------------------------------------------------------------ every member, overlap, in the plan

    @Test
    fun `choosing every member of a group is refused before anything runs (GAL-8)`(): Unit = runBlocking {
        val empty = VisualRetention("g1", "v", emptyList())
        rejected(executor().run(plan(photo(a, empty), photo(b, empty))))
            .any { "lose every photo" in it } shouldBe true
    }

    @Test
    fun `a plan that acts on the photo its group keeps is refused before anything runs`(): Unit = runBlocking {
        // Each op names the other as kept: both members of one group would go.
        rejected(executor().run(plan(photo(a, group("g1", b)), photo(b, group("g1", a)))))
        // The same group, consistent evidence, the kept photo also planned (any op of the plan counts).
        val g = group("g1", b)
        rejected(executor().run(plan(photo(a, g), fake.op(b).copy(ruleId = "ai_images"))))
            .any { "would lose a photo it keeps" in it } shouldBe true
    }

    @Test
    fun `a differently spelled path to the kept photo is still the kept photo`(): Unit = runBlocking {
        val g = group("g1", b)
        // Another ref (a MediaStore row) whose shown path is b in other letters.
        val upper = PlannedOp(
            KPath.Media("external_primary", 77),
            ROOT + b.removePrefix(ROOT).uppercase(),
            OpKind.TRASH,
            false,
            2000,
            20,
        )
        rejected(executor().run(plan(photo(a, g), upper))).any { "would lose a photo it keeps" in it } shouldBe true
    }

    @Test
    fun `overlapping groups can't lose their last photo in one plan (GAL-8)`(): Unit = runBlocking {
        // g1 = {a, b}, g2 = {b, c}: moving a (g1 keeps b) and b (g2 keeps c, g1 keeps a) together would empty g1.
        val g1ForA = group("g1", b)
        val g1ForB = group("g1", a)
        val g2ForB = group("g2", c)
        rejected(executor().run(plan(photo(a, g1ForA), photo(b, g1ForB, g2ForB))))
        // The app's other spelling: b names only g2; a still keeps b, which the plan acts on.
        rejected(executor().run(plan(photo(a, g1ForA), photo(b, g2ForB))))
    }

    @Test
    fun `overlapping groups can't lose their last photo across plans (sequential sessions)`(): Unit = runBlocking {
        // g1 = {a, b}, g2 = {b, c}. Plan 1 moves c (g2 keeps b). Plan 2, from a stale screen, moves b: g1 keeps a, and
        // g2 still keeps c, which is gone: refused, so g2 keeps b.
        ran(executor().run(plan(photo(c, group("g2", b))))).done.size shouldBe 1
        val stale = VisualRetention("g2", "v-g2", listOf(RetainedMember(KPath.Local(c), c, 3000, 30, "x")))
        refused(executor().run(plan(photo(b, group("g1", a), stale))), b)
        fake.exists(b) shouldBe true
    }

    @Test
    fun `two plans in sequence from one stale screen can't empty a group`(): Unit = runBlocking {
        val keepB = group("g1", b)
        val keepA = group("g1", a)
        ran(executor().run(plan(photo(a, keepB)))).done.size shouldBe 1
        // The second session still shows a and acts on b, keeping a, which is now in the trash.
        refused(executor().run(plan(photo(b, keepA))), b)
        fake.exists(b) shouldBe true
    }

    // ------------------------------------------------------------------ races between the plan and the act

    @Test
    fun `a kept photo edited between the plan and the act refuses the group (source race)`(): Unit = runBlocking {
        val g = group("g1", b)
        val p = plan(photo(a, g))
        fake.beforeOp = { op, path ->
            if (op == FakeStorageGateway.Op.STAT && path == KPath.Local(b)) {
                fake.beforeOp = null
                fake.modify(b, FakeStorageGateway.Content.Virtual(3, 2000), mtime = 99)
            }
        }
        refused(executor().run(p), a)
    }

    @Test
    fun `a group refused mid-run stays refused for the rest of the run`(): Unit = runBlocking {
        val g = group("g1", b)
        val p = plan(photo(a, g), photo(c, g), photo(d, g))
        var performed = 0
        val changing = object : ActionHandler {
            override suspend fun perform(op: PlannedOp, gateway: StorageGateway) {
                handler.perform(op, gateway)
                performed++
                // Another app edits b after a was moved; then puts it back as it was.
                if (performed == 1) fake.setMtime(b, 77)
            }
        }
        val exec = ActionExecutor(
            fake,
            SafetyGuard(ProtectedPaths()),
            ProtectedPaths(),
            changing,
            SESSION,
            onResult = { r -> if (r is OpResult.Skipped) fake.setMtime(b, 20) },
        )
        val r = ran(exec.run(p))
        r.done.map { it.op.displayPath } shouldContainExactly listOf(a)
        r.skipped.map { it.op.displayPath } shouldContainExactly listOf(c, d)
        r.skipped.all { it.reason == SkipReason.RETAINED_MEMBER_GONE } shouldBe true
    }

    @Test
    fun `a refused group leaves other groups and ordinary items of the plan alone`(): Unit = runBlocking {
        fake.setMtime(b, 21)
        val stale = VisualRetention("g1", "v", listOf(RetainedMember(KPath.Local(b), b, 2000, 20, null)))
        val fine = group("g2", d)
        val r = ran(executor().run(plan(photo(a, stale), photo(c, fine))))
        r.done.single().op.displayPath shouldBe c
        r.skipped.single().op.displayPath shouldBe a
    }

    // ------------------------------------------------------------------ malformed evidence

    @Test
    fun `different evidence for one group, a blank version, a group named twice are refused`(): Unit = runBlocking {
        rejected(executor().run(plan(photo(a, group("g1", c)), photo(b, group("g1", d)))))
        rejected(executor().run(plan(photo(a, VisualRetention("g1", " ", listOf(member(b)))))))
        rejected(executor().run(plan(photo(a, group("g1", b), group("g1", b)))))
    }

    @Test
    fun `a kept photo whose ref differs from the path shown is refused`(): Unit = runBlocking {
        val lying = VisualRetention("g1", "v", listOf(member(b).copy(ref = KPath.Local(c))))
        rejected(executor().run(plan(photo(a, lying))))
    }

    // ------------------------------------------------------------------ similarity is never equality (GAL-3)

    @Test
    fun `similar-photos evidence can't ride on an op that claims equality, a backup or a folder`(): Unit =
        runBlocking {
            val g = group("g1", b)
            val w = Witness(KPath.Local(b), b, 2000, 20)
            val sha = "a".repeat(64)
            val cases = listOf(
                photo(a, g).copy(duplicateGroup = "dup"),
                photo(a, g).copy(witnesses = listOf(w)),
                photo(a, g).copy(expectedSha256 = sha),
                photo(a, g).copy(lastCopyConfirmed = true),
                photo(a, g).copy(classifiedSafe = true),
                photo(a, g).copy(verifiedCopy = "copy"),
                photo(a, g).copy(kind = OpKind.MOVE_DELETE_ORIGINAL, expectedSha256 = sha),
                photo(a, g).copy(
                    kind = OpKind.MOVE_DELETE_ORIGINAL,
                    expectedSha256 = sha,
                    backupProof = BackupProof("t", "a.jpg", sha, 1, 1),
                ),
                photo(a, g).copy(kind = OpKind.PURGE),
                photo(a, g).copy(isDirectory = true),
            )
            for (op in cases) {
                val groups = if (op.duplicateGroup != null) mapOf("dup" to 2) else emptyMap()
                rejected(executor().run(plan(op, groups = groups)))
            }
        }

    @Test
    fun `identical bytes and a junk rule never make a similar photo permanent`(): Unit = runBlocking {
        // b has exactly a's bytes, and the op carries every label that would otherwise delete it for good.
        val thumb = "$ROOT/DCIM/.thumbnails/1.jpg"
        val twin = "$ROOT/DCIM/.thumbnails/2.jpg"
        val bytes = ByteArray(64) { it.toByte() }
        fake.addFile(thumb, bytes, mtime = 5)
        fake.addFile(twin, bytes, mtime = 5)
        val g = VisualRetention("g1", "v", listOf(member(twin)))
        val op = fake.op(thumb).copy(ruleId = PermanentKinds.THUMBNAILS, visualGroups = listOf(g))
        PermanentKinds.reasonFor(op, 1) shouldBe null
        PermanentKinds.reasonFor(op.copy(classifiedSafe = true), 1) shouldBe null
        // Even with a backup proof that would otherwise hold, a similar photo is never freed for good.
        val sha = "b".repeat(64)
        val proven = op.copy(
            kind = OpKind.MOVE_DELETE_ORIGINAL,
            expectedSha256 = sha,
            backupProof = BackupProof("t", "1.jpg", sha, secondCheckAt = 1, copyCheckedAt = 1),
        )
        PermanentKinds.verifiedBackupReason(proven.copy(visualGroups = emptyList()), 1, strictRehash = true) shouldBe
            PermanentReason.VERIFIED_BACKUP
        PermanentKinds.verifiedBackupReason(proven, 1, strictRehash = true) shouldBe null
        val r = ran(executor().run(plan(op)))
        r.deletedForGood.shouldBeEmpty()
        val handed = handler.performed.single()
        handed.kind shouldBe OpKind.TRASH
        handed.permanentReason shouldBe null
        handed.witnesses.shouldBeEmpty()
        handed.duplicateGroup shouldBe null
        handed.expectedSha256 shouldBe null
        fake.exists(twin) shouldBe true
    }

    @Test
    fun `ordinary plans are unchanged by the new field`(): Unit = runBlocking {
        val r = ran(executor().run(plan(fake.op(a), fake.op(b))))
        r.done.size shouldBe 2
    }
}
