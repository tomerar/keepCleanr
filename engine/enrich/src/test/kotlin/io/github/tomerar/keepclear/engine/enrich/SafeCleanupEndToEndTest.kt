// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.enrich

import io.github.tomerar.keepclear.core.model.Category
import io.github.tomerar.keepclear.core.safety.ActionExecutor
import io.github.tomerar.keepclear.core.safety.ActionPlan
import io.github.tomerar.keepclear.core.safety.ExecutionReport
import io.github.tomerar.keepclear.core.safety.OpKind
import io.github.tomerar.keepclear.core.safety.PlannedOp
import io.github.tomerar.keepclear.core.safety.ProtectedPaths
import io.github.tomerar.keepclear.core.safety.SafetyGuard
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.engine.classify.Classifier
import io.github.tomerar.keepclear.engine.scan.ScanEvent
import io.github.tomerar.keepclear.engine.scan.Scanner
import io.github.tomerar.keepclear.engine.trash.FakeClock
import io.github.tomerar.keepclear.engine.trash.FakeSigner
import io.github.tomerar.keepclear.engine.trash.InMemoryTrashIndex
import io.github.tomerar.keepclear.engine.trash.TrashManager
import io.github.tomerar.keepclear.testing.fixtures.OwnerDevice
import io.github.tomerar.keepclear.testing.fixtures.OwnerDevice.Tag
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * M7 DoD on the owner-shaped tree: scan → facts → classify → "Clean now" plan of every SAFE item →
 * guard + executor → trash. Exactly the SAFE items move to trash; PROTECTED and everything else stay;
 * Undo (restore) brings every file back byte-exact.
 */
class SafeCleanupEndToEndTest {
    @Test
    fun `clean now trashes exactly the SAFE items and undo restores them`(): Unit = runBlocking {
        val f = OwnerDevice.build()
        val g = f.gateway
        val before = g.files().associateWith { g.contentOf(it) }
        val items = Scanner(g).scan(listOf(KPath.Local(OwnerDevice.ROOT), KPath.Local(OwnerDevice.CLONE_ROOT)))
            .filterIsInstance<ScanEvent.Batch>().toList().flatMap { it.items }
        val facts = FactEnricher(g).facts(items)
        val classified = items.map { Classifier().classify(it, facts[it.path].orEmpty(), OwnerDevice.NOW) }
        val safe = classified.filter { it.category == Category.SAFE }
        check(safe.isNotEmpty())

        val protectedPaths = ProtectedPaths()
        val trash =
            TrashManager(
                g,
                InMemoryTrashIndex(),
                FakeSigner(),
                FakeClock(),
                protectedPaths,
                listOf(OwnerDevice.ROOT, OwnerDevice.CLONE_ROOT),
            )
        val executor = ActionExecutor(g, SafetyGuard(protectedPaths), protectedPaths, trash, "s")
        val ops = safe.map { c ->
            val s = g.stat(c.item.ref)!!
            PlannedOp(s.path, c.item.path, OpKind.TRASH, false, s.size, s.mtimeMillis, s.identity, ruleId = c.ruleId)
        }
        val report = executor.run(ActionPlan("p", ops, "clean").confirm(1, "s")) as ExecutionReport.Ran
        report.done.size shouldBe safe.size

        val gone = safe.map { it.item.path }.toSet()
        for ((path, content) in before) {
            when {
                path in gone -> g.exists(path) shouldBe false
                else -> g.contentOf(path) shouldBe content
            }
        }
        f.protectedPaths.forEach { g.exists(it) shouldBe true }
        f.paths(Tag.CAMERA).forEach { g.exists(it) shouldBe true }
        f.paths(Tag.WA_SENT_UNIQUE).forEach { g.exists(it) shouldBe true }

        // Undo: restore everything that was trashed.
        val restoreOps = trash.entries().map {
            PlannedOp(KPath.Local(it.trashedPath), it.trashedPath, OpKind.RESTORE, false, it.size, it.mtimeMillis)
        }
        (executor.run(ActionPlan("u", restoreOps, "undo").confirm(2, "s")) as ExecutionReport.Ran).done.size shouldBe
            safe.size
        for ((path, content) in before) g.contentOf(path) shouldBe content
    }
}
