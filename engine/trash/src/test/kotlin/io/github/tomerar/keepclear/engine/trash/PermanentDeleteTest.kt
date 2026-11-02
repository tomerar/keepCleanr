// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.trash

import io.github.tomerar.keepclear.core.safety.ActionExecutor
import io.github.tomerar.keepclear.core.safety.ActionPlan
import io.github.tomerar.keepclear.core.safety.ExecutionReport
import io.github.tomerar.keepclear.core.safety.OpKind
import io.github.tomerar.keepclear.core.safety.OpResult
import io.github.tomerar.keepclear.core.safety.PermanentReason
import io.github.tomerar.keepclear.core.safety.PlannedOp
import io.github.tomerar.keepclear.core.safety.ProtectedPaths
import io.github.tomerar.keepclear.core.safety.SafetyGuard
import io.github.tomerar.keepclear.core.safety.SkipReason
import io.github.tomerar.keepclear.core.safety.Witness
import io.github.tomerar.keepclear.core.storage.FakeStorageGateway
import io.github.tomerar.keepclear.core.storage.KPath
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Owner decision 2026-11-02 at the choke point, with the real [TrashManager] on [FakeStorageGateway]: items that
 * rebuild themselves or are already in another app's bin are deleted for good; every other kind goes to the trash,
 * and the old guarantees (PROTECTED, re-check, test mode, confirmation in this session) hold for both.
 */
class PermanentDeleteTest {
    private val root = "/storage/emulated/0"
    private val now = 1_800_000_000_000L
    private val old = now - 3L * 24 * 3_600_000
    private val fake = FakeStorageGateway(caseInsensitive = true, volumeRoots = listOf(root))
    private val index = InMemoryTrashIndex()
    private val protectedPaths = ProtectedPaths()
    private var ids = 0
    private val trash = TrashManager(
        fake,
        index,
        FakeSigner(),
        FakeClock(wall = now),
        protectedPaths,
        listOf(root),
        newId = { "00000000-0000-4000-8000-%012d".format(++ids) },
    )
    private val wa = "$root/Android/media/com.whatsapp/WhatsApp/Media"

    /** Path → classification rule, for every kind deleted for good. */
    private val permanent = mapOf(
        "$root/DCIM/.thumbnails/1234.jpg" to "thumbnails",
        "$root/Download/setup.tmp" to "temp_files",
        "$root/Download/.DS_Store" to "os_junk",
        "$wa/.Statuses/abc.jpg" to "wa_status_old",
        "$root/DCIM/Camera/.trashed-1700000000-IMG_9.jpg" to "other_app_bin",
        "$root/.FileManagerRecycler/IMG_8.jpg" to "other_app_bin",
    )

    private val camera = "$root/DCIM/Camera/IMG_1.jpg"
    private val zipDir = "$root/Download/a"

    /** Path → op, for every kind that must still go to the trash. */
    private fun trashed(): Map<String, PlannedOp> = mapOf(
        "$wa/WhatsApp Images/Sent/IMG_1.jpg" to op(
            "$wa/WhatsApp Images/Sent/IMG_1.jpg",
            "wa_sent_copy",
            group = "dup:x",
            witnesses = listOf(Witness(KPath.Local(camera), camera, 100, old)),
        ),
        "$root/Download/a.zip" to op(
            "$root/Download/a.zip",
            "zip_extracted",
            witnesses = listOf(Witness(KPath.Local("$zipDir/x.txt"), "$zipDir/x.txt", 100, old)),
        ),
        "$root/Pictures/Screenshots/s.png" to op("$root/Pictures/Screenshots/s.png", "screenshots", safe = false),
        "$root/DCIM/.thumbnails/fav.jpg" to op("$root/DCIM/.thumbnails/fav.jpg", "thumbnails", safe = false),
        "$root/Download/Old/x.tmp" to op("$root/Download/Old/x.tmp", "user_junk"),
        "$root/LOST.DIR/1" to op("$root/LOST.DIR/1", "lost_dir"),
        "$root/DCIM/Camera/IMG_2.jpg" to op("$root/DCIM/Camera/IMG_2.jpg", "thumbnails"),
        "$root/DCIM/Camera/.trashed-x-IMG_3.jpg" to op("$root/DCIM/Camera/.trashed-x-IMG_3.jpg", "other_app_bin"),
    )

    init {
        (permanent.keys + listOf(camera, "$zipDir/x.txt") + trashedPaths).forEach {
            fake.addFile(it, size = 100, mtime = old, seed = 1)
        }
        fake.addFile("$wa/WhatsApp Images/Sent/IMG_1.jpg", size = 100, mtime = old, seed = 1)
    }

    private val trashedPaths
        get() = listOf(
            "$root/Download/a.zip",
            "$root/Pictures/Screenshots/s.png",
            "$root/DCIM/.thumbnails/fav.jpg",
            "$root/Download/Old/x.tmp",
            "$root/LOST.DIR/1",
            "$root/DCIM/Camera/IMG_2.jpg",
            "$root/DCIM/Camera/.trashed-x-IMG_3.jpg",
        )

    private fun op(
        path: String,
        rule: String,
        safe: Boolean = true,
        group: String? = null,
        witnesses: List<Witness> = emptyList(),
        kind: OpKind = OpKind.TRASH,
    ): PlannedOp = runBlocking {
        val s = fake.stat(KPath.Local(path)) ?: error("missing $path")
        PlannedOp(
            s.path,
            path,
            kind,
            s.isDirectory,
            s.size,
            s.mtimeMillis,
            s.identity,
            ruleId = rule,
            classifiedSafe = safe,
            duplicateGroup = group,
            witnesses = witnesses,
        )
    }

    private fun executor(dryRun: Boolean = false, guard: ProtectedPaths = protectedPaths) =
        ActionExecutor(fake, SafetyGuard(guard), guard, trash, "s", dryRun = dryRun, clock = { now })

    private suspend fun run(ops: List<PlannedOp>, dryRun: Boolean = false, groups: Map<String, Int> = emptyMap()) =
        executor(dryRun).run(ActionPlan("p", ops, "t", groups).confirm(1, "s")) as ExecutionReport.Ran

    @Test
    fun `each permanent kind is deleted for good, with no trash entry`(): Unit = runBlocking {
        val report = run(permanent.map { (path, rule) -> op(path, rule) })
        report.done.size shouldBe permanent.size
        report.deletedForGood.size shouldBe permanent.size
        report.deletedForGood.map { it.op.permanentReason }.toSet() shouldBe PermanentReason.entries.toSet()
        permanent.keys.forEach { fake.exists(it) shouldBe false }
        index.all().shouldBeEmpty()
    }

    @Test
    fun `every other kind goes to the 30-day trash`(): Unit = runBlocking {
        val ops = trashed()
        val report = run(ops.values.toList(), groups = mapOf("dup:x" to 2))
        report.done.size shouldBe ops.size
        report.deletedForGood.shouldBeEmpty()
        val entries = index.all().associateBy { it.originalPath }
        for (path in ops.keys) {
            fake.exists(path) shouldBe false
            fake.exists(entries.getValue(path).trashedPath) shouldBe true
        }
    }

    @Test
    fun `MOVE originals go to the trash`(): Unit = runBlocking {
        val sha = io.github.tomerar.keepclear.core.hash.Sha256.of(fake.readAllBytes(camera))
        val o = op(camera, "camera", kind = OpKind.MOVE_DELETE_ORIGINAL).copy(expectedSha256 = sha)
        run(listOf(o)).deletedForGood.shouldBeEmpty()
        fake.exists(index.all().single().trashedPath) shouldBe true
    }

    @Test
    fun `a mixed plan splits per item`(): Unit = runBlocking {
        val tmp = "$root/Download/setup.tmp"
        val shot = "$root/Pictures/Screenshots/s.png"
        val report = run(listOf(op(tmp, "temp_files"), trashed().getValue(shot)))
        report.deletedForGood.map { it.op.displayPath } shouldBe listOf(tmp)
        index.all().map { it.originalPath } shouldBe listOf(shot)
    }

    @Test
    fun `PROTECTED stays refused`(): Unit = runBlocking {
        val db = "$root/Android/media/com.whatsapp/WhatsApp/Databases/.trashed-1700000000-msgstore.db.crypt14"
        val ownTrash = "$root/.KeepClearTrash/20261102/x/a.tmp"
        val backup = "$root/Backups/.thumbnails/1.jpg"
        listOf(db, ownTrash, backup).forEach { fake.addFile(it, size = 10, mtime = old) }
        val guarded = ProtectedPaths(extraRoots = listOf("$root/Backups"))
        val ops = listOf(op(db, "other_app_bin"), op(ownTrash, "temp_files"), op(backup, "thumbnails"))
        val report = executor(guard = guarded).run(ActionPlan("p", ops, "t").confirm(1, "s")) as ExecutionReport.Ran
        report.skipped.map { it.reason }.toSet() shouldBe setOf(SkipReason.PROTECTED)
        listOf(db, ownTrash, backup).forEach { fake.exists(it) shouldBe true }
    }

    @Test
    fun `test mode deletes nothing (SAFETY-9)`(): Unit = runBlocking {
        val report = run(permanent.map { (path, rule) -> op(path, rule) }, dryRun = true)
        report.results.forEach {
            it.shouldBeInstanceOf<OpResult.Simulated>()
            it.op.kind shouldBe OpKind.DELETE_FOR_GOOD
        }
        permanent.keys.forEach { fake.exists(it) shouldBe true }
        index.all().shouldBeEmpty()
    }

    @Test
    fun `a changed item is skipped and kept (SAFETY-6)`(): Unit = runBlocking {
        val ops = permanent.map { (path, rule) -> op(path, rule) }
        permanent.keys.forEachIndexed { i, path ->
            if (i % 2 ==
                0
            ) {
                fake.setMtime(path, old + 1)
            } else {
                fake.modify(path, FakeStorageGateway.Content.Virtual(2, 101))
            }
        }
        val report = run(ops)
        report.skipped.map { it.reason }.toSet() shouldBe setOf(SkipReason.CHANGED)
        permanent.keys.forEach { fake.exists(it) shouldBe true }
    }

    @Test
    fun `a kept item is skipped (CLS-7)`(): Unit = runBlocking {
        val tmp = "$root/Download/setup.tmp"
        val ex = ActionExecutor(fake, SafetyGuard(protectedPaths), protectedPaths, trash, "s", keptNow = { it == tmp })
        val report = ex.run(ActionPlan("p", listOf(op(tmp, "temp_files")), "t").confirm(1, "s")) as ExecutionReport.Ran
        report.skipped.single().reason shouldBe SkipReason.CONTAINS_PROTECTED
        fake.exists(tmp) shouldBe true
    }

    @Test
    fun `without a confirmation in this session nothing is deleted (SAFETY-1)`(): Unit = runBlocking {
        val ops = permanent.map { (path, rule) -> op(path, rule) }
        executor().run(ActionPlan("p", ops, "t")).shouldBeInstanceOf<ExecutionReport.Rejected>()
        executor().run(ActionPlan("p", ops, "t").confirm(1, "other")).shouldBeInstanceOf<ExecutionReport.Rejected>()
        val notShown = ActionPlan("p", ops, "t").confirm(
            io.github.tomerar.keepclear.core.safety.Confirmation.of(listOf(ops.first().displayPath), 1),
            "s",
        )
        executor().run(notShown).shouldBeInstanceOf<ExecutionReport.Rejected>()
        permanent.keys.forEach { fake.exists(it) shouldBe true }
    }

    @Test
    fun `a plan that asks for a permanent delete itself is rejected`(): Unit = runBlocking {
        val path = "$root/DCIM/Camera/IMG_2.jpg"
        val o = op(path, "camera", kind = OpKind.DELETE_FOR_GOOD)
        executor().run(ActionPlan("p", listOf(o), "t").confirm(1, "s")).shouldBeInstanceOf<ExecutionReport.Rejected>()
        fake.exists(path) shouldBe true
    }
}
