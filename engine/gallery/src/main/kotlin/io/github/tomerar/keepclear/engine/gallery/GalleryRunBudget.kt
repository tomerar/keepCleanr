// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.gallery

/** Numeric scheduling policy only. G1a has no runtime, tensors, model loader or inference callback. */
data class LearnedContract(val modelDigest: String, val features: FeatureVersion, val representation: String) {
    init {
        require(modelDigest.length == 64 && modelDigest.all { it in '0'..'9' || it in 'a'..'f' })
        require(representation.isNotEmpty())
    }
}

data class LearnedFeatureKey(val source: SourceVersion, val contract: LearnedContract)

/** G2 must supply real calibration evidence; G1a does not construct one in the application. */
data class RouterCalibration(val policy: GalleryPolicy, val evidenceId: String) {
    init {
        require(evidenceId.isNotEmpty())
    }
}

enum class BudgetStop { COUNT, TIME, CANCELLED, STALE, INVALID_ROUTE, BUSY, INCOMPATIBLE }

sealed interface BudgetDecision {
    class Ready internal constructor(val ticket: Long, val missing: List<LearnedFeatureKey>) : BudgetDecision

    data class Deferred(val reason: BudgetStop) : BudgetDecision
}

data class BudgetSnapshot(
    val reservedUnique: Int,
    val startedUnique: Int,
    val cacheHits: Int,
    val elapsedMillis: Long,
    val overrunMillis: Long,
    val stop: BudgetStop?,
)

/**
 * One instance per user-started run, owned by one worker. Reserve all missing participants before loading.
 * Reservation consumes slots conservatively even if interrupted; failures never become automatic retries.
 * Pause/resume retains the same object/counters; process recreation needs an explicit new foreground flow
 * with restored counters (G1b/G3), never a fresh implicit budget. No persistence is implemented here.
 */
internal class GalleryRunBudget(
    private val run: GalleryRun,
    private val eligibleImageCount: Int,
    private val calibration: RouterCalibration?,
    private val nowMillis: () -> Long,
    private val current: (GalleryRun, SourceVersion) -> Boolean,
) {
    val inferenceLimit: Int
    private val timeLimitMillis = 30_000L
    private val reserved = linkedSetOf<LearnedFeatureKey>()
    private val started = linkedSetOf<LearnedFeatureKey>()
    private val completed = linkedSetOf<LearnedFeatureKey>()
    private var cacheHits = 0
    private var accrued = 0L
    private var activeSince: Long? = null
    private var lastClock = Long.MIN_VALUE
    private var stop: BudgetStop? = null
    private var contract: LearnedContract? = null
    private var nextTicket = 0L
    private var active: BudgetDecision.Ready? = null
    private var participants: List<SourceVersion> = emptyList()
    private var pending: Set<LearnedFeatureKey> = emptySet()
    private var inFlight: LearnedFeatureKey? = null
    private var initialized = false

    init {
        require(eligibleImageCount >= 0)
        inferenceLimit = minOf(256, maxOf(2, ((eligibleImageCount.toLong() + 19) / 20).toInt()))
    }

    fun reserve(route: CandidateRoute, requested: LearnedContract, cached: Set<LearnedFeatureKey>): BudgetDecision {
        refresh()
        stop?.let { return BudgetDecision.Deferred(it) }
        if (active != null) return BudgetDecision.Deferred(BudgetStop.BUSY)
        if (route.state != RouteState.AMBIGUOUS_CANDIDATE || route.reason != RouteReason.CONFLICTING_HASHES ||
            route.run != run || calibration?.policy != route.policy || requested.features != route.policy.features ||
            route.participants.size !in 2..minOf(20, eligibleImageCount) ||
            route.participants.map { it.key }.distinct().size != route.participants.size
        ) {
            return BudgetDecision.Deferred(BudgetStop.INVALID_ROUTE)
        }
        if (route.participants.any { !current(run, it) }) return halt(BudgetStop.STALE)
        if (contract != null && contract != requested) return BudgetDecision.Deferred(BudgetStop.INCOMPATIBLE)
        val keys = route.participants.map { LearnedFeatureKey(it, requested) }
        val missing = keys.filterNot { it in completed || it in cached }
        // Previously reserved but failed/stopped work cannot be silently retried, even in a different group.
        if (missing.any { it in reserved }) return BudgetDecision.Deferred(BudgetStop.INVALID_ROUTE)
        if (reserved.size + missing.size > inferenceLimit) return BudgetDecision.Deferred(BudgetStop.COUNT)
        contract = requested
        reserved += missing
        cacheHits += keys.size - missing.size
        participants = route.participants.toList()
        pending = missing.toSet()
        return BudgetDecision.Ready(++nextTicket, missing.toList()).also { active = it }
    }

    /** Call immediately before first initialization OR cached numeric refinement; initialization time counts. */
    fun beginStage(ticket: Long): Boolean {
        if (!valid(ticket)) return false
        if (activeSince == null) activeSince = clock()
        initialized = true
        return valid(ticket)
    }

    fun beginInference(ticket: Long, key: LearnedFeatureKey): Boolean {
        if (!valid(ticket) || !initialized || activeSince == null || inFlight != null ||
            key !in pending || key !in reserved || key in started
        ) {
            return false
        }
        started += key
        inFlight = key
        return true
    }

    /** A stale or late result is never reusable. Time exhaustion permits only this one bounded in-flight end. */
    fun finishInference(ticket: Long, key: LearnedFeatureKey, outputValid: Boolean): Boolean {
        if (active?.ticket != ticket || inFlight != key) return false
        inFlight = null
        val publish = valid(ticket) && outputValid
        if (publish) completed += key
        return publish
    }

    fun finishGroup(ticket: Long, resolvedEarly: Boolean = false): Boolean {
        if (active?.ticket != ticket || inFlight != null) return false
        val publish = valid(ticket) && (resolvedEarly || pending.all { it in completed })
        active = null
        participants = emptyList()
        pending = emptySet()
        return publish
    }

    /** Stop scheduling immediately; the caller cancels/finishes only its one bounded in-flight operation. */
    fun cancel() {
        refresh()
        stop = BudgetStop.CANCELLED
    }

    fun pause(): Boolean {
        refresh()
        if (inFlight != null) return false
        activeSince?.let { accrued += clock() - it }
        activeSince = null
        return true
    }

    fun snapshot(): BudgetSnapshot {
        refresh()
        val elapsed = elapsed()
        return BudgetSnapshot(
            reserved.size,
            started.size,
            cacheHits,
            elapsed,
            maxOf(0, elapsed - timeLimitMillis),
            stop,
        )
    }

    private fun valid(ticket: Long): Boolean {
        refresh()
        if (stop != null || active?.ticket != ticket) return false
        if (participants.any { !current(run, it) }) {
            stop = BudgetStop.STALE
            return false
        }
        return true
    }

    private fun halt(reason: BudgetStop): BudgetDecision.Deferred {
        stop = reason
        return BudgetDecision.Deferred(reason)
    }

    private fun clock(): Long {
        val value = nowMillis()
        check(value >= lastClock) { "monotonic clock required" }
        lastClock = value
        return value
    }

    private fun elapsed(): Long = accrued + (activeSince?.let { clock() - it } ?: 0L)

    private fun refresh() {
        if (elapsed() >= timeLimitMillis && stop == null) stop = BudgetStop.TIME
    }
}
