// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.gallery

import io.github.tomerar.keepclear.engine.classify.ClassifiedItem
import io.github.tomerar.keepclear.engine.gallery.CompletedFeature
import io.github.tomerar.keepclear.engine.gallery.ContentKind
import io.github.tomerar.keepclear.engine.gallery.FeatureRecord
import io.github.tomerar.keepclear.engine.gallery.GalleryAuthorization
import io.github.tomerar.keepclear.engine.gallery.GalleryFeatureStore
import io.github.tomerar.keepclear.engine.gallery.GalleryGrouping
import io.github.tomerar.keepclear.engine.gallery.GalleryPolicy
import io.github.tomerar.keepclear.engine.gallery.GalleryResult
import io.github.tomerar.keepclear.engine.gallery.GalleryRun
import io.github.tomerar.keepclear.engine.gallery.GalleryScope
import io.github.tomerar.keepclear.engine.gallery.GalleryScopeSource
import io.github.tomerar.keepclear.engine.gallery.SourceKey
import io.github.tomerar.keepclear.engine.gallery.StoreWrite
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.util.UUID

/** Why an eligible still image was not analysed in this run (GAL-2). Skipped items are never "checked". */
enum class GallerySkip {
    NO_READER,
    UNSUPPORTED_FORMAT,
    UNSUPPORTED_VARIANT,
    FILE_TOO_LARGE,
    IMAGE_TOO_LARGE,
    DAMAGED,
    EMPTY,
    INACCESSIBLE,
    CHANGED,
    UNREADABLE,
}

enum class GalleryRunState {
    IDLE,
    RUNNING,
    COMPLETED,

    /** The user stopped; the result so far is partial and Continue resumes the same run. */
    STOPPED,

    /** E-G15: below 500 MB free the cache refused a write; decoding and writing stop until Continue. */
    PAUSED_LOW_SPACE,

    /** E-G20: a backup, free, restore or clean flow took priority. */
    PAUSED_FOR_FILE_ACTION,

    /** E-G02: the scope changed; old results are hidden and only a new run can start. */
    ACCESS_CHANGED,

    /** E-G01: no photo access; nothing was read. */
    NO_ACCESS,
}

/** Honest counters (GAL-2, GAL-26): analysed = fromCache + newlyDecoded; notReached is what a stop left. */
data class GalleryCounts(
    val stillImages: Int = 0,
    val excluded: Map<GalleryExclusion, Int> = emptyMap(),
    val eligible: Int = 0,
    val fromCache: Int = 0,
    val newlyDecoded: Int = 0,
    val skipped: Map<GallerySkip, Int> = emptyMap(),
    val notReached: Int = 0,
) {
    val analysed: Int get() = fromCache + newlyDecoded
}

/**
 * Advisory progress and result. [grouping] covers only completed records and is null while running, after an
 * access change or without access. Nothing here is a category, proof, selection or file action.
 */
data class GalleryProgress(
    val state: GalleryRunState,
    val scope: GalleryScope,
    val run: GalleryRun? = null,
    val counts: GalleryCounts = GalleryCounts(),
    /** Selected photos only, an incomplete scan, items without a reader, a stop or a pause: not the whole gallery. */
    val partial: Boolean = true,
    /** The cache refused writes for space (E-G15) or failed (E-G14); results so far are in memory only. */
    val cacheWritesPaused: Boolean = false,
    val cacheWritesFailed: Boolean = false,
    val grouping: GalleryResult? = null,
    /** Work bounds actually observed in this run (one worker, a queue of at most [QUEUE_CAPACITY] refs). */
    val maxQueued: Int = 0,
    val maxInFlight: Int = 0,
)

/** E-G20: the hook file-action flows call before they plan; gallery work stops at the next item boundary or read. */
fun interface GalleryWorkGate {
    fun pauseForFileAction()
}

/** At most this many item refs are handed to the single worker at a time (gallery-design.md bounds). */
const val QUEUE_CAPACITY = 8

/**
 * One user-started, foreground gallery run (GAL-1, GAL-15, C213): eligible items → live observation → cached
 * record for exactly that version, else one guarded read → checkpoint of completed records only → advisory
 * grouping. Pure Kotlin (C210). State lives only in this object: after process death nothing resumes; there is no
 * background job, boot or scheduled start. One run at a time; [stop] and [pauseForFileAction] leave an honest partial
 * result and [continueRun] resumes the same run and epoch only while the scope is unchanged.
 */
class GalleryCoordinator(
    private val store: GalleryFeatureStore,
    private val observer: GallerySourceObserver,
    private val reader: GalleryImageReader,
    private val scope: GalleryScopeSource,
    private val policy: GalleryPolicy = GalleryPolicy(),
    private val newRunId: () -> String = { UUID.randomUUID().toString() },
) : GalleryWorkGate {
    private val active = Mutex()
    private val _progress = MutableStateFlow(GalleryProgress(GalleryRunState.IDLE, GalleryScope.NONE))
    val progress: StateFlow<GalleryProgress> = _progress

    @Volatile private var interrupt: GalleryRunState? = null

    @Volatile private var worker: Job? = null

    private var session: Session? = null

    /** Starts a new run over [snapshot]; returns null when a run is already active (E-G25). */
    suspend fun start(snapshot: GallerySnapshot): GalleryProgress? {
        if (!active.tryLock()) return null
        try {
            interrupt = null
            session = null
            val auth = scope.current()
            val cacheReady = enterEpoch(auth.epoch)
            if (auth.scope == GalleryScope.NONE) {
                return publish(GalleryProgress(GalleryRunState.NO_ACCESS, auth.scope, partial = true))
            }
            val s = Session(GalleryRun(newRunId(), snapshot.scanId, auth.epoch), auth, snapshot, cacheReady)
            s.plan(snapshot.exclusions())
            session = s
            return drive(s)
        } finally {
            active.unlock()
        }
    }

    /** Resumes the stopped or paused run with the same epoch and completed records; null when busy. */
    suspend fun continueRun(): GalleryProgress? {
        if (!active.tryLock()) return null
        try {
            val s = session ?: return _progress.value
            if (s.state !in RESUMABLE) return _progress.value
            interrupt = null
            val auth = scope.current()
            if (auth != s.auth) {
                s.accessChanged(auth)
                return publish(s.progress(GalleryRunState.ACCESS_CHANGED, null))
            }
            s.writesPaused = false
            return drive(s)
        } finally {
            active.unlock()
        }
    }

    fun stop() = halt(GalleryRunState.STOPPED)

    override fun pauseForFileAction() = halt(GalleryRunState.PAUSED_FOR_FILE_ACTION)

    private fun halt(state: GalleryRunState) {
        interrupt = state
        worker?.cancel()
    }

    private suspend fun drive(s: Session): GalleryProgress {
        publish(s.progress(GalleryRunState.RUNNING, null))
        var halted: GalleryRunState? = null
        coroutineScope {
            val job = launch { halted = work(s) }
            worker = job
            if (interrupt != null) job.cancel()
            job.join()
            worker = null
        }
        if (halted != GalleryRunState.ACCESS_CHANGED) {
            val live = scope.current()
            if (live != s.auth) {
                s.accessChanged(live)
                halted = GalleryRunState.ACCESS_CHANGED
            }
        }
        val state =
            halted ?: interrupt ?: if (s.pending.isEmpty()) GalleryRunState.COMPLETED else GalleryRunState.STOPPED
        // E-G02: never show groups from an authorization that is no longer current. Grouping is bounded CPU work
        // over completed records only (no reads); a stopped or paused run still gets its honest partial groups.
        val grouping = if (state == GalleryRunState.ACCESS_CHANGED) null else group(s)
        if (grouping != null && scope.current() != s.auth) {
            s.accessChanged(scope.current())
            return publish(s.progress(GalleryRunState.ACCESS_CHANGED, null))
        }
        return publish(s.progress(state, grouping))
    }

    /**
     * One worker over a bounded queue: at most [QUEUE_CAPACITY] item refs are handed out at a time, the next batch
     * only when the worker has drained the current one, and a stop leaves the rest pending. Returns a halt state,
     * or null when every pending item was handled.
     */
    private suspend fun work(s: Session): GalleryRunState? {
        val order = s.pending.toList()
        var next = 0
        while (next < order.size) {
            val queue = order.subList(next, minOf(order.size, next + QUEUE_CAPACITY))
            s.maxQueued = maxOf(s.maxQueued, queue.size)
            for (index in queue) {
                currentCoroutineContext().ensureActive()
                (interrupt ?: process(s, index))?.let { return it }
            }
            next += queue.size
        }
        return null
    }

    private suspend fun process(s: Session, index: Int): GalleryRunState? {
        val planned = s.items[index]
        val live = scope.current()
        if (live != s.auth) {
            s.accessChanged(live)
            return GalleryRunState.ACCESS_CHANGED
        }
        if (planned.item.item.size == 0L) return s.skip(index, GallerySkip.EMPTY)
        val exclusions = s.snapshot.exclusions()
        val protectedPaths = exclusions.protectedPaths
        val gate = GalleryGate { run, item ->
            run == s.run && interrupt == null && scope.current() == s.auth &&
                GalleryEligibility.decide(item, s.snapshot.exclusions()) is GalleryEligibility.Decision.Eligible
        }
        s.inFlight++
        s.maxInFlight = maxOf(s.maxInFlight, s.inFlight)
        try {
            val version = observer.observe(planned.item, s.run, protectedPaths, gate)
                ?: return interruption(s) ?: s.skip(index, GallerySkip.INACCESSIBLE)
            store.get(version, policy.features, s.run.permissionEpoch)
                ?.takeIf { it.source == version && it.version == policy.features && it.facts.valid() }
                ?.let { cached ->
                    s.complete(index, cached, planned.kind, fromCache = true)
                    return null
                }
            val record = when (val read = reader.read(planned.item, s.run, policy.features, protectedPaths, gate)) {
                is GalleryRead.Read -> {
                    read.record
                }

                is GalleryRead.Unsupported -> {
                    return s.skip(index, read.reason.skip())
                }

                GalleryRead.Unavailable -> {
                    interruption(s)?.let { return it }
                    val again = observer.observe(planned.item, s.run, protectedPaths, gate)
                    interruption(s)?.let { return it }
                    return s.skip(
                        index,
                        when (again) {
                            null -> GallerySkip.INACCESSIBLE
                            version -> GallerySkip.UNREADABLE
                            else -> GallerySkip.CHANGED
                        },
                    )
                }
            }
            if (record.source != version || record.version != policy.features || !record.facts.valid()) {
                return s.skip(index, GallerySkip.CHANGED)
            }
            if (s.writesEnabled && !s.writesPaused) {
                when (store.put(record, s.run.permissionEpoch)) {
                    StoreWrite.STORED -> {
                        // Checkpointed: a later run reuses it while the source and epoch are unchanged.
                    }

                    StoreWrite.PAUSED_LOW_SPACE -> {
                        s.complete(index, record, planned.kind, fromCache = false)
                        s.writesPaused = true
                        return GalleryRunState.PAUSED_LOW_SPACE
                    }

                    // A newer epoch began elsewhere: this run's authorization is no longer current.
                    StoreWrite.STALE_EPOCH -> {
                        s.accessChanged(scope.current())
                        return GalleryRunState.ACCESS_CHANGED
                    }

                    StoreWrite.REJECTED -> {
                        return s.skip(index, GallerySkip.UNREADABLE)
                    }

                    // E-G14: keep going in memory; no further writes in this run.
                    StoreWrite.FAILED -> {
                        s.writesEnabled = false
                        s.writesFailed = true
                    }
                }
            }
            s.complete(index, record, planned.kind, fromCache = false)
            return null
        } finally {
            s.inFlight--
        }
    }

    /** A read refused because of a stop, pause or scope change leaves its item pending, not skipped. */
    private suspend fun interruption(s: Session): GalleryRunState? {
        interrupt?.let { return it }
        val live = scope.current()
        if (live != s.auth) {
            s.accessChanged(live)
            return GalleryRunState.ACCESS_CHANGED
        }
        return null
    }

    private suspend fun group(s: Session): GalleryResult = GalleryGrouping.group(
        s.run,
        s.completed.values.sortedBy { it.record.source.key },
        scopeComplete = s.scopeComplete(),
        current = { !s.authChanged },
        policy = policy,
    )

    /** Records the epoch in the cache, which hides and drops other epochs' records (GAL-12). */
    private suspend fun enterEpoch(epoch: Long): Boolean = when (store.beginEpoch(epoch)) {
        StoreWrite.STORED -> true

        // The cache holds a newer epoch than the ledger (for example a lost preferences file): the cache is
        // recomputable, so clear it and start over rather than trust it.
        StoreWrite.STALE_EPOCH -> store.clear() == StoreWrite.STORED && store.beginEpoch(epoch) == StoreWrite.STORED

        else -> false
    }

    private fun publish(progress: GalleryProgress): GalleryProgress {
        session?.state = progress.state
        _progress.value = progress
        return progress
    }

    private fun UnsupportedImage.skip(): GallerySkip = when (this) {
        UnsupportedImage.FORMAT -> GallerySkip.UNSUPPORTED_FORMAT
        UnsupportedImage.VARIANT -> GallerySkip.UNSUPPORTED_VARIANT
        UnsupportedImage.FILE_TOO_LARGE -> GallerySkip.FILE_TOO_LARGE
        UnsupportedImage.IMAGE_TOO_LARGE -> GallerySkip.IMAGE_TOO_LARGE
        UnsupportedImage.DAMAGED -> GallerySkip.DAMAGED
    }

    private class Planned(val item: ClassifiedItem, val kind: ContentKind)

    private inner class Session(
        val run: GalleryRun,
        val auth: GalleryAuthorization,
        val snapshot: GallerySnapshot,
        var writesEnabled: Boolean,
    ) {
        val items = mutableListOf<Planned>()
        val pending = linkedSetOf<Int>()
        val completed = linkedMapOf<SourceKey, CompletedFeature>()
        val skipped = linkedMapOf<GallerySkip, Int>()
        val excluded = linkedMapOf<GalleryExclusion, Int>()
        var stillImages = 0
        var fromCache = 0
        var newlyDecoded = 0
        var writesPaused = false
        var writesFailed = !writesEnabled
        var authChanged = false
        var state = GalleryRunState.IDLE
        var maxQueued = 0
        var inFlight = 0
        var maxInFlight = 0

        fun plan(exclusions: GalleryExclusions) {
            for (item in snapshot.items) {
                when (val decision = GalleryEligibility.decide(item, exclusions)) {
                    GalleryEligibility.Decision.NotStill -> {
                        continue
                    }

                    is GalleryEligibility.Decision.Excluded -> {
                        excluded.merge(decision.reason, 1, Int::plus)
                    }

                    GalleryEligibility.Decision.NoReader -> {
                        items += Planned(item, ContentKind.UNSUPPORTED)
                        skipped.merge(GallerySkip.NO_READER, 1, Int::plus)
                    }

                    is GalleryEligibility.Decision.Eligible -> {
                        pending += items.size
                        items += Planned(item, decision.kind)
                    }
                }
                stillImages++
            }
        }

        fun skip(index: Int, reason: GallerySkip): GalleryRunState? {
            pending -= index
            skipped.merge(reason, 1, Int::plus)
            return null
        }

        fun complete(index: Int, record: FeatureRecord, kind: ContentKind, fromCache: Boolean) {
            pending -= index
            if (completed.put(record.source.key, CompletedFeature(record, kind)) != null) return
            if (fromCache) this.fromCache++ else newlyDecoded++
        }

        suspend fun accessChanged(live: GalleryAuthorization) {
            authChanged = true
            // Hide and drop the old epoch's records at once (E-G02); a failure still leaves them unreachable.
            if (live.epoch != run.permissionEpoch) enterEpoch(live.epoch)
        }

        fun scopeComplete(): Boolean = auth.scope.complete && snapshot.scanComplete && pending.isEmpty() &&
            GallerySkip.NO_READER !in skipped

        fun progress(state: GalleryRunState, grouping: GalleryResult?) = GalleryProgress(
            state = state,
            scope = auth.scope,
            run = run,
            counts = GalleryCounts(
                stillImages = stillImages,
                excluded = excluded.toMap(),
                eligible = items.size,
                fromCache = fromCache,
                newlyDecoded = newlyDecoded,
                skipped = skipped.toMap(),
                notReached = pending.size,
            ),
            partial = state != GalleryRunState.COMPLETED || !scopeComplete(),
            cacheWritesPaused = writesPaused,
            cacheWritesFailed = writesFailed,
            grouping = grouping,
            maxQueued = maxQueued,
            maxInFlight = maxInFlight,
        )
    }

    private companion object {
        val RESUMABLE = setOf(
            GalleryRunState.STOPPED,
            GalleryRunState.PAUSED_LOW_SPACE,
            GalleryRunState.PAUSED_FOR_FILE_ACTION,
        )
    }
}
