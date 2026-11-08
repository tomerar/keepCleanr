// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.gallery

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** GAL-24/25, AC-G13/14 (scheduling policy only), E-G29/30/31. No model is loaded in these tests. */
class GalleryRunBudgetTest {
    private val policy = GalleryPolicy()
    private val contract = LearnedContract("a".repeat(64), policy.features, "test-reference-v1")
    private var time = 0L
    private val hidden = mutableSetOf<SourceVersion>()
    private var liveRun = testRun

    private fun budget(count: Int = 100) = GalleryRunBudget(
        testRun,
        count,
        RouterCalibration(policy, "synthetic-test-only"),
        { time },
        { run, source -> run == liveRun && source !in hidden },
    )

    private fun route(vararg sources: SourceVersion) = CandidateRoute(
        testRun,
        policy,
        sources.toList(),
        RouteState.AMBIGUOUS_CANDIDATE,
        RouteReason.CONFLICTING_HASHES,
    )

    private fun finish(budget: GalleryRunBudget, ready: BudgetDecision.Ready) {
        assertTrue(budget.beginStage(ready.ticket))
        ready.missing.forEach {
            assertTrue(budget.beginInference(ready.ticket, it))
            assertTrue(budget.finishInference(ready.ticket, it, true))
        }
        assertTrue(budget.finishGroup(ready.ticket))
    }

    @Test
    fun `resolved no-match unsupported and uncalibrated ambiguity schedule nothing`() {
        val budget = budget()
        val pair = route(source(1), source(2))
        RouteState.entries.filterNot { it == RouteState.AMBIGUOUS_CANDIDATE }.forEach {
            assertEquals(
                BudgetDecision.Deferred(BudgetStop.INVALID_ROUTE),
                budget.reserve(pair.copy(state = it), contract, emptySet()),
            )
        }
        val uncalibrated = GalleryRunBudget(testRun, 100, null, { time }, { _, _ -> true })
        assertEquals(
            BudgetDecision.Deferred(BudgetStop.INVALID_ROUTE),
            uncalibrated.reserve(pair, contract, emptySet()),
        )
        assertEquals(0, budget.snapshot().reservedUnique)
        assertEquals(0, budget.snapshot().startedUnique)
        assertFalse(budget.beginStage(1))
    }

    @Test
    fun `both pair participants must fit and many tiny groups share the run cap`() {
        val budget = budget(100) // five unique participants
        finish(budget, budget.reserve(route(source(1), source(2)), contract, emptySet()) as BudgetDecision.Ready)
        finish(budget, budget.reserve(route(source(3), source(4)), contract, emptySet()) as BudgetDecision.Ready)
        repeat(100) {
            assertEquals(
                BudgetDecision.Deferred(BudgetStop.COUNT),
                budget.reserve(route(source(5), source(6)), contract, emptySet()),
            )
        }
        assertEquals(4, budget.snapshot().startedUnique)
    }

    @Test
    fun `shared participants and compatible cache hits do not consume another slot`() {
        val budget = budget()
        val first = budget.reserve(route(source(1), source(2)), contract, emptySet()) as BudgetDecision.Ready
        finish(budget, first)
        val cached = setOf(LearnedFeatureKey(source(3), contract))
        val second = budget.reserve(route(source(2), source(3)), contract, cached) as BudgetDecision.Ready
        assertTrue(second.missing.isEmpty()) // No reason to initialize a runtime.
        assertTrue(budget.finishGroup(second.ticket))
        assertEquals(2, budget.snapshot().startedUnique)
        assertEquals(2, budget.snapshot().cacheHits)
    }

    @Test
    fun `incompatible cached source preprocessing and model records cannot satisfy a pair`() {
        val budget = budget()
        val a = source(1)
        val b = source(2)
        val cached = setOf(
            LearnedFeatureKey(a.copy(revision = "old"), contract),
            LearnedFeatureKey(b, contract.copy(modelDigest = "b".repeat(64))),
            LearnedFeatureKey(b, contract.copy(features = FeatureVersion("old", "old"))),
        )
        val ready = budget.reserve(route(a, b), contract, cached) as BudgetDecision.Ready
        assertEquals(setOf(a, b), ready.missing.map { it.source }.toSet())
        finish(budget, ready)
        assertEquals(2, budget.snapshot().startedUnique)
    }

    @Test
    fun `initialization consumes all learned time and schedules no inference after deadline`() {
        val budget = budget()
        val ready = budget.reserve(route(source(1), source(2)), contract, emptySet()) as BudgetDecision.Ready
        assertTrue(budget.beginStage(ready.ticket))
        time = 30_001
        assertFalse(budget.beginInference(ready.ticket, ready.missing.first()))
        assertEquals(0, budget.snapshot().startedUnique)
        assertEquals(1, budget.snapshot().overrunMillis)
        assertEquals(BudgetStop.TIME, budget.snapshot().stop)
    }

    @Test
    fun `one bounded in-flight item may overrun but no second item or late result is admitted`() {
        val budget = budget()
        val ready = budget.reserve(route(source(1), source(2)), contract, emptySet()) as BudgetDecision.Ready
        assertTrue(budget.beginStage(ready.ticket))
        assertTrue(budget.beginInference(ready.ticket, ready.missing[0]))
        assertFalse(budget.beginInference(ready.ticket, ready.missing[1]))
        time = 30_500
        assertFalse(budget.finishInference(ready.ticket, ready.missing[0], true))
        assertFalse(budget.beginInference(ready.ticket, ready.missing[1]))
        assertEquals(500, budget.snapshot().overrunMillis)
        assertEquals(1, budget.snapshot().startedUnique)
        assertFalse(budget.finishGroup(ready.ticket))
    }

    @Test
    fun `pause excludes idle time and resume preserves count and accrued stage time`() {
        val budget = budget()
        val ready = budget.reserve(route(source(1), source(2)), contract, emptySet()) as BudgetDecision.Ready
        assertTrue(budget.beginStage(ready.ticket))
        time = 10_000
        assertTrue(budget.pause())
        time = 90_000
        assertEquals(10_000, budget.snapshot().elapsedMillis)
        assertFalse(budget.beginInference(ready.ticket, ready.missing[0]))
        assertTrue(budget.beginStage(ready.ticket))
        time = 110_000
        assertFalse(budget.beginInference(ready.ticket, ready.missing[0]))
        assertEquals(30_000, budget.snapshot().elapsedMillis)
        assertEquals(2, budget.snapshot().reservedUnique)
    }

    @Test
    fun `cancel epoch or source replacement prevents stale reuse and any automatic retry`() {
        for (cause in listOf("cancel", "permission", "source")) {
            liveRun = testRun
            hidden.clear()
            val budget = budget()
            val ready = budget.reserve(route(source(1), source(2)), contract, emptySet()) as BudgetDecision.Ready
            assertTrue(budget.beginStage(ready.ticket))
            assertTrue(budget.beginInference(ready.ticket, ready.missing[0]))
            when (cause) {
                "cancel" -> budget.cancel()
                "permission" -> liveRun = testRun.copy(permissionEpoch = 2)
                "source" -> hidden += source(1)
            }
            assertFalse(budget.finishInference(ready.ticket, ready.missing[0], true))
            assertFalse(budget.beginInference(ready.ticket, ready.missing[1]))
            assertFalse(budget.finishGroup(ready.ticket))
            assertTrue(budget.reserve(route(source(1), source(2)), contract, emptySet()) is BudgetDecision.Deferred)
        }
    }

    @Test
    fun `failed outputs do not retry through another group and foreign tickets cannot advance work`() {
        val budget = budget()
        val ready = budget.reserve(route(source(1), source(2)), contract, emptySet()) as BudgetDecision.Ready
        assertFalse(budget.beginStage(ready.ticket + 1))
        assertTrue(budget.beginStage(ready.ticket))
        val key = ready.missing.first()
        assertTrue(budget.beginInference(ready.ticket, key))
        assertFalse(budget.finishInference(ready.ticket + 1, key, true))
        assertFalse(budget.finishInference(ready.ticket, key, false))
        assertFalse(budget.beginInference(ready.ticket, key))
        assertFalse(budget.finishGroup(ready.ticket))
        assertEquals(
            BudgetDecision.Deferred(BudgetStop.INVALID_ROUTE),
            budget.reserve(route(source(1), source(3)), contract, emptySet()),
        )
    }

    @Test
    fun `empty scope does no work and pathological huge count does not overflow the cap`() {
        val empty = budget(0)
        assertEquals(0, empty.snapshot().startedUnique)
        assertEquals(
            BudgetDecision.Deferred(BudgetStop.INVALID_ROUTE),
            empty.reserve(route(source(1), source(2)), contract, emptySet()),
        )
        assertEquals(256, budget(Int.MAX_VALUE).inferenceLimit)
        assertEquals(2, budget(1).inferenceLimit)
        assertEquals(3, budget(41).inferenceLimit)
    }

    @Test
    fun `threshold changes require matching calibration and early resolution stops scheduling`() {
        val budget = budget()
        val pair = route(source(1), source(2))
        assertEquals(
            BudgetDecision.Deferred(BudgetStop.INVALID_ROUTE),
            budget.reserve(pair.copy(policy = policy.copy(dHashDistance = 3)), contract, emptySet()),
        )
        val ready = budget.reserve(pair, contract, emptySet()) as BudgetDecision.Ready
        assertTrue(budget.beginStage(ready.ticket))
        assertTrue(budget.beginInference(ready.ticket, ready.missing[0]))
        assertTrue(budget.finishInference(ready.ticket, ready.missing[0], true))
        assertTrue(budget.finishGroup(ready.ticket, resolvedEarly = true))
        assertFalse(budget.beginInference(ready.ticket, ready.missing[1]))
        assertEquals(1, budget.snapshot().startedUnique)
        assertEquals(2, budget.snapshot().reservedUnique)
    }

    @Test
    fun `mutating a returned work list cannot expand or erase the private reservation`() {
        val budget = budget()
        val ready = budget.reserve(route(source(1), source(2)), contract, emptySet()) as BudgetDecision.Ready
        val original = ready.missing.toList()
        val injected = LearnedFeatureKey(source(3), contract)
        (ready.missing as MutableList<LearnedFeatureKey>).apply {
            clear()
            add(injected)
        }
        assertTrue(budget.beginStage(ready.ticket))
        assertFalse(budget.beginInference(ready.ticket, injected))
        assertFalse(budget.finishGroup(ready.ticket))
        assertEquals(0, budget.snapshot().startedUnique)
        assertEquals(2, budget.snapshot().reservedUnique)
        // Even after an unsuccessful finish, the original sources were not quietly retried in a new group.
        assertEquals(
            BudgetDecision.Deferred(BudgetStop.INVALID_ROUTE),
            budget.reserve(route(original[0].source, original[1].source), contract, emptySet()),
        )
    }
}
