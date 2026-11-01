// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.enrich

import io.github.tomerar.keepclear.core.model.Category
import io.github.tomerar.keepclear.core.safety.ProtectedPaths
import io.github.tomerar.keepclear.core.safety.SafetyGuard
import io.github.tomerar.keepclear.core.storage.FakeStorageGateway
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.engine.classify.Classifier
import io.github.tomerar.keepclear.engine.scan.ScanEvent
import io.github.tomerar.keepclear.engine.scan.Scanner
import io.github.tomerar.keepclear.testing.fixtures.OwnerDevice
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/** H2: the "Clean now" plan carries duplicate groups and witnesses, so SAFETY-8 is enforced in the guard. */
class CleanPlannerTest {
    private val root = OwnerDevice.ROOT
    private val sent = "$root/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/Sent/IMG_7.jpg"
    private val camera = "$root/DCIM/Camera/IMG_7.jpg"

    private suspend fun setup(): Pair<List<io.github.tomerar.keepclear.engine.classify.ClassifiedItem>, Enrichment> {
        val g = FakeStorageGateway(caseInsensitive = true)
        val bytes = ByteArray(3_000) { 6 }
        g.addFile(sent, bytes)
        g.addFile(camera, bytes)
        val items = Scanner(g).scan(listOf(KPath.Local(root))).filterIsInstance<ScanEvent.Batch>().toList()
            .flatMap { it.items }
        val e = FactEnricher(g).enrich(items)
        return items.map { Classifier().classify(it, e.facts[it.path].orEmpty(), OwnerDevice.NOW) } to e
    }

    @Test
    fun `a twin op carries its group, its witness and the group size`(): Unit = runBlocking {
        val (classified, e) = setup()
        val safe = classified.filter { it.category == Category.SAFE }
        safe.map { it.item.path } shouldBe listOf(sent)
        val plan = CleanPlanner.plan("p", safe, e.evidence, "clean")
        val op = plan.ops.single()
        op.duplicateGroup shouldBe e.evidence.getValue(sent).group
        op.witnesses.map { it.path } shouldBe listOf(camera)
        plan.duplicateGroupSizes shouldBe mapOf(op.duplicateGroup!! to 2)
        SafetyGuard(ProtectedPaths()).check(plan.confirm(1, "s"), "s") shouldBe SafetyGuard.Verdict.Accepted
    }

    @Test
    fun `selecting the witness too is rejected - the last copy is never planned away (SAFETY-8)`(): Unit =
        runBlocking {
            val (classified, e) = setup()
            val plan = CleanPlanner.plan("p", classified, e.evidence, "clean")
            plan.ops.map { it.duplicateGroup }.toSet().size shouldBe 1
            SafetyGuard(ProtectedPaths()).check(plan.confirm(1, "s"), "s")
                .shouldBeInstanceOf<SafetyGuard.Verdict.Rejected>()
        }

    @Test
    fun `two Sent copies of one camera photo can both go, the camera photo stays`(): Unit = runBlocking {
        val g = FakeStorageGateway(caseInsensitive = true)
        val bytes = ByteArray(3_000) { 6 }
        val sent2 = "$root/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/Sent/IMG_7 (1).jpg"
        listOf(sent, sent2, camera).forEach { g.addFile(it, bytes) }
        val items = Scanner(g).scan(listOf(KPath.Local(root))).filterIsInstance<ScanEvent.Batch>().toList()
            .flatMap { it.items }
        val e = FactEnricher(g).enrich(items)
        val classified = items.map { Classifier().classify(it, e.facts[it.path].orEmpty(), OwnerDevice.NOW) }
        val safe = classified.filter { it.category == Category.SAFE }
        safe.size shouldBe 2
        val plan = CleanPlanner.plan("p", safe, e.evidence, "clean")
        plan.duplicateGroupSizes.values.single() shouldBe 3
        SafetyGuard(ProtectedPaths()).check(plan.confirm(1, "s"), "s") shouldBe SafetyGuard.Verdict.Accepted
        SafetyGuard(ProtectedPaths()).check(CleanPlanner.plan("q", classified, e.evidence, "c").confirm(1, "s"), "s")
            .shouldBeInstanceOf<SafetyGuard.Verdict.Rejected>()
    }
}
