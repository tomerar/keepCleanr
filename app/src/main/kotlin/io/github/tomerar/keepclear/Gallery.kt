// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.tomerar.keepclear.core.gallery.GalleryCoordinator
import io.github.tomerar.keepclear.core.gallery.GalleryExclusions
import io.github.tomerar.keepclear.core.gallery.GalleryProgress
import io.github.tomerar.keepclear.core.gallery.GalleryRunState
import io.github.tomerar.keepclear.core.gallery.GallerySkip
import io.github.tomerar.keepclear.core.gallery.GallerySnapshot
import io.github.tomerar.keepclear.core.model.Category
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.engine.classify.ClassificationSummary
import io.github.tomerar.keepclear.engine.classify.ClassifiedItem
import io.github.tomerar.keepclear.engine.gallery.GalleryScope
import io.github.tomerar.keepclear.engine.gallery.GalleryScopeSource
import io.github.tomerar.keepclear.engine.gallery.KeeperAdvice
import io.github.tomerar.keepclear.engine.gallery.KeeperReason
import io.github.tomerar.keepclear.engine.gallery.SourceKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** G4a: what the gallery comparison screens show (ux-flows.md §10). Advisory only: no selection, no file action. */
enum class GalleryPhase {
    /** Nothing started yet in this session; [GalleryUi.scope] says what KeepClear may compare. */
    NOT_STARTED,

    /** No completed scan to compare from. */
    NO_SCAN,

    /** E-G01: no photo access; nothing was read. */
    NO_ACCESS,
    RUNNING,
    STOPPED,
    PAUSED_LOW_SPACE,
    PAUSED_FOR_FILE_ACTION,

    /** E-G02: the scope changed; earlier groups are hidden and only a new run can start. */
    ACCESS_CHANGED,
    DONE,
}

/** One photo of a comparison group: the scan's own item (its lane is unchanged, GAL-4) and the keeper marker. */
data class GalleryMember(val item: ClassifiedItem, val suggested: Boolean)

/**
 * One small virtual group. [reasons] are the factual reasons for the "Suggested to keep" marker; empty means
 * "No clear recommendation" (abstention). The marker is never a selection (GAL-6, GAL-10).
 */
data class GalleryGroupUi(val id: String, val members: List<GalleryMember>, val reasons: List<KeeperReason>) {
    val suggested: GalleryMember? get() = members.firstOrNull { it.suggested }

    /** MOVE memories offer the backup handoff, never deletion (GAL-4, AC-G3). */
    val hasMemories: Boolean get() = members.any { it.item.category == Category.MOVE }
}

/** Technical counts for the Details sheet only (never on the default path). */
data class GalleryDetails(
    val eligible: Int = 0,
    val fromCache: Int = 0,
    val newlyDecoded: Int = 0,
    val notReached: Int = 0,
    val skipped: Map<GallerySkip, Int> = emptyMap(),
    val excluded: Int = 0,
    val notSaved: Boolean = false,
    val limited: Boolean = false,
)

data class GalleryUi(
    val phase: GalleryPhase = GalleryPhase.NOT_STARTED,
    /** The scope the current (or last) run was authorized for, or the live scope before a run. */
    val scope: GalleryScope? = null,
    /** Handled so far: compared + skipped, of [total] photos to compare. */
    val done: Int = 0,
    val total: Int = 0,
    val compared: Int = 0,
    val skipped: Int = 0,
    /**
     * True only when the run finished over a whole-library scope, nothing was skipped or left out by a limit and the
     * scan was complete. Only then may the screen say every photo was compared (GAL-2).
     */
    val complete: Boolean = false,
    val groups: List<GalleryGroupUi> = emptyList(),
    val details: GalleryDetails = GalleryDetails(),
) {
    val running: Boolean get() = phase == GalleryPhase.RUNNING

    val resumable: Boolean
        get() = phase == GalleryPhase.STOPPED || phase == GalleryPhase.PAUSED_LOW_SPACE ||
            phase == GalleryPhase.PAUSED_FOR_FILE_ACTION

    companion object {
        /**
         * Maps the coordinator's advisory progress to what the screens show. Members are looked up among the scan's
         * own items by their MediaStore key; a member that is not there (or no longer offered: [dismissed] groups)
         * is never shown, and a group needs at least two shown members.
         */
        fun of(
            progress: GalleryProgress,
            items: Map<SourceKey, ClassifiedItem>,
            dismissed: Set<String> = emptySet(),
            /** Photos the user just chose "Don't suggest this again" for (the kept rule, CLS-7). */
            hidden: Set<String> = emptySet(),
        ): GalleryUi {
            val phase = when (progress.state) {
                GalleryRunState.IDLE -> GalleryPhase.NOT_STARTED
                GalleryRunState.RUNNING -> GalleryPhase.RUNNING
                GalleryRunState.COMPLETED -> GalleryPhase.DONE
                GalleryRunState.STOPPED -> GalleryPhase.STOPPED
                GalleryRunState.PAUSED_LOW_SPACE -> GalleryPhase.PAUSED_LOW_SPACE
                GalleryRunState.PAUSED_FOR_FILE_ACTION -> GalleryPhase.PAUSED_FOR_FILE_ACTION
                GalleryRunState.ACCESS_CHANGED -> GalleryPhase.ACCESS_CHANGED
                GalleryRunState.NO_ACCESS -> GalleryPhase.NO_ACCESS
            }
            val counts = progress.counts
            val skipped = counts.skipped.values.sum()
            val grouping = progress.grouping?.takeIf {
                phase != GalleryPhase.ACCESS_CHANGED && phase != GalleryPhase.NO_ACCESS
            }
            val groups = grouping?.groups.orEmpty().mapNotNull { group ->
                val suggested = (group.advice as? KeeperAdvice.Suggested)?.source?.key
                val members = group.version.members.mapNotNull { source ->
                    items[source.key]?.takeIf { it.item.path !in hidden }
                        ?.let { GalleryMember(it, suggested == source.key) }
                }
                val id = group.version.members.minOf { it.key }.let { "${it.volume}:${it.ref}" }
                if (members.size < 2 || id in dismissed) return@mapNotNull null
                // A suggestion for a member that can't be shown is no suggestion at all.
                val reasons = if (members.any { it.suggested }) {
                    (group.advice as KeeperAdvice.Suggested).reasons.take(2)
                } else {
                    emptyList()
                }
                GalleryGroupUi(id, members.map { if (reasons.isEmpty()) it.copy(suggested = false) else it }, reasons)
            }
            return GalleryUi(
                phase = phase,
                scope = progress.scope,
                done = counts.analysed + skipped,
                total = counts.eligible,
                compared = counts.analysed,
                skipped = skipped,
                complete = phase == GalleryPhase.DONE && !progress.partial && skipped == 0 &&
                    grouping?.complete == true,
                groups = groups,
                details = GalleryDetails(
                    eligible = counts.eligible,
                    fromCache = counts.fromCache,
                    newlyDecoded = counts.newlyDecoded,
                    notReached = counts.notReached,
                    skipped = counts.skipped,
                    excluded = counts.excluded.values.sum(),
                    notSaved = progress.cacheWritesPaused || progress.cacheWritesFailed,
                    limited = grouping != null && !grouping.complete && phase == GalleryPhase.DONE,
                ),
            )
        }

        /** The scan's MediaStore items by the key the gallery observation uses (volume + row id, C212). */
        fun keyed(items: List<ClassifiedItem>): Map<SourceKey, ClassifiedItem> = items.mapNotNull { item ->
            (item.item.ref as? KPath.Media)?.let { SourceKey(it.volume, it.id.toString()) to item }
        }.toMap()
    }
}

/**
 * G4a (C220): the app side of one user-started gallery comparison. It builds the snapshot from the latest scan and
 * the live exclusions, runs the coordinator off the main thread, maps its progress, and keeps "Keep this time"
 * dismissals for this session only (C223). It never selects, plans or confirms anything, and it changes no lane
 * total: the scan's summary is only read (GAL-21).
 */
class GalleryController(
    /** Created on first use only, so the gallery costs nothing until the user opens it (C220). */
    private val coordinator: () -> GalleryCoordinator,
    /** Read lazily too: nothing is created before the user opens the screen. */
    private val liveScope: () -> GalleryScopeSource,
    /** The live exclusions, rebuilt from the backup jobs and the user's kept choices (read off the main thread). */
    private val exclusions: suspend () -> () -> GalleryExclusions,
    private val scope: CoroutineScope,
    private val io: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
) {
    private val _state = MutableStateFlow(GalleryUi())
    val state: StateFlow<GalleryUi> = _state

    private var items: Map<SourceKey, ClassifiedItem> = emptyMap()
    private var dismissed: Set<String> = emptySet()
    private var hidden: Set<String> = emptySet()
    private var collector: Job? = null
    private var last: GalleryProgress? = null

    /** Shows the live scope before a run (no read, no coordinator). */
    fun checkScope() {
        scope.launch {
            val auth = withContext(io) { liveScope().current() }
            val run = last?.run
            _state.update { s ->
                when {
                    // E-G02, AC-G1: never keep showing groups from an authorization that is no longer current.
                    run != null && auth.epoch != run.permissionEpoch && !s.running -> {
                        GalleryUi(GalleryPhase.ACCESS_CHANGED, auth.scope)
                    }

                    s.phase == GalleryPhase.NOT_STARTED || s.phase == GalleryPhase.NO_ACCESS ||
                        s.phase == GalleryPhase.NO_SCAN -> {
                        s.copy(
                            phase = if (auth.scope == GalleryScope.NONE) {
                                GalleryPhase.NO_ACCESS
                            } else if (s.phase == GalleryPhase.NO_ACCESS) {
                                GalleryPhase.NOT_STARTED
                            } else {
                                s.phase
                            },
                            scope = auth.scope,
                        )
                    }

                    else -> {
                        s
                    }
                }
            }
        }
    }

    /** User-started only (GAL-1): compares the still images of [summary], the latest scan. */
    fun start(summary: ClassificationSummary?, scanComplete: Boolean) {
        if (_state.value.running) return
        if (summary == null) {
            _state.update { it.copy(phase = GalleryPhase.NO_SCAN, groups = emptyList()) }
            return
        }
        dismissed = emptySet()
        hidden = emptySet()
        scope.launch {
            val live = exclusions()
            val all = summary.groups.flatMap { it.items }
            items = GalleryUi.keyed(all)
            val snapshot = GallerySnapshot(UUID.randomUUID().toString(), all, scanComplete, live)
            val gallery = coordinator()
            follow(gallery)
            withContext(io) { gallery.start(snapshot) }?.let(::publish)
        }
    }

    fun stop() {
        if (_state.value.running) coordinator().stop()
    }

    fun continueRun() {
        if (!_state.value.resumable) return
        scope.launch {
            val gallery = coordinator()
            follow(gallery)
            withContext(io) { gallery.continueRun() }?.let(::publish)
        }
    }

    /** "Keep this time": hides the group in this session only; nothing is stored or changed (C223). */
    fun keepThisTime(groupId: String) {
        dismissed = dismissed + groupId
        last?.let(::publish) ?: _state.update { s -> s.copy(groups = s.groups.filterNot { it.id == groupId }) }
    }

    /**
     * After "Don't suggest this again" (the existing kept rule, applied by the caller): the photo leaves its group at
     * once; the coordinator's live exclusions already skip it in a run that continues.
     */
    fun dontOffer(path: String) {
        hidden = hidden + path
        last?.let(::publish)
    }

    private fun follow(gallery: GalleryCoordinator) {
        if (collector != null) return
        collector = scope.launch {
            gallery.progress.collect { p -> if (p.state != GalleryRunState.IDLE) publish(p) }
        }
    }

    private fun publish(progress: GalleryProgress) {
        last = progress
        _state.value = GalleryUi.of(progress, items, dismissed, hidden)
    }
}

/** G4a: the gallery comparison's ViewModel; the work is the application's coordinator (one per process). */
class GalleryViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as KeepClearApplication
    val controller = GalleryController(
        coordinator = { app.gallery },
        liveScope = { app.galleryScope },
        exclusions = { galleryExclusions(app) },
        scope = viewModelScope,
    )
    val state: StateFlow<GalleryUi> = controller.state
}

/**
 * The live exclusions for a gallery run (GAL-1, E-G03): every known backup folder and its drive, KeepClear's own
 * folders and the PROTECTED rules, and what the user kept, re-read before each item so a file kept mid-run is never
 * read. The backup jobs are read once, off the main thread.
 */
suspend fun galleryExclusions(app: KeepClearApplication): () -> GalleryExclusions {
    val jobs = withContext(Dispatchers.IO) { app.moveController.store.jobs() }
    val excluded = BackupRoots.scanExclusions(BackupRoots.of(jobs))
    var cached: Pair<Set<String>, GalleryExclusions>? = null
    return {
        val kept = app.userChoices.state.value.kept
        cached?.takeIf { it.first === kept }?.second
            ?: GalleryExclusions(BackupRoots.protectedPaths(jobs, app.packageName, kept), kept, excluded)
                .also { cached = kept to it }
    }
}

/** Details sheet rows: the skip reasons grouped as the user reads them. */
internal object GallerySkipGroups {
    val FORMAT = setOf(GallerySkip.UNSUPPORTED_FORMAT)
    val VARIANT = setOf(GallerySkip.UNSUPPORTED_VARIANT)
    val LARGE = setOf(GallerySkip.FILE_TOO_LARGE, GallerySkip.IMAGE_TOO_LARGE)
    val DAMAGED = setOf(GallerySkip.DAMAGED, GallerySkip.EMPTY)
    val CHANGED = setOf(GallerySkip.CHANGED, GallerySkip.INACCESSIBLE, GallerySkip.UNREADABLE)
    val NO_READER = setOf(GallerySkip.NO_READER)

    fun count(skipped: Map<GallerySkip, Int>, kinds: Set<GallerySkip>): Int = kinds.sumOf { skipped[it] ?: 0 }
}
