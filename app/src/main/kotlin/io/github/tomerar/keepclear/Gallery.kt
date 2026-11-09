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
import io.github.tomerar.keepclear.engine.gallery.GalleryGroup
import io.github.tomerar.keepclear.engine.gallery.GalleryScope
import io.github.tomerar.keepclear.engine.gallery.GalleryScopeSource
import io.github.tomerar.keepclear.engine.gallery.GroupVersion
import io.github.tomerar.keepclear.engine.gallery.KeeperAdvice
import io.github.tomerar.keepclear.engine.gallery.KeeperReason
import io.github.tomerar.keepclear.engine.gallery.SourceKey
import io.github.tomerar.keepclear.engine.gallery.SourceVersion
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

/**
 * One photo of a comparison group: the scan's own item (its lane is unchanged, GAL-4), the keeper marker, and the
 * source version the comparison read ([source], G4b: binds a choice to the content it was made for, GAL-9).
 */
data class GalleryMember(val item: ClassifiedItem, val suggested: Boolean, val source: SourceVersion? = null)

/**
 * One small virtual group. [reasons] are the factual reasons for the "Suggested to keep" marker; empty means
 * "No clear recommendation" (abstention). The marker is never a selection (GAL-6, GAL-10).
 *
 * G4b (C230): [version] is a digest of the group version (run, policy, member source versions); [chosen] are the
 * REVIEW photos the user explicitly chose to move to the trash in this session (empty at first, GAL-6); [choosable]
 * are the photos a tap may choose now: REVIEW only (never a memory, GAL-4), and never the last photo any similar group
 * it belongs to keeps (GAL-8).
 */
data class GalleryGroupUi(
    val id: String,
    val members: List<GalleryMember>,
    val reasons: List<KeeperReason>,
    val version: String = "",
    val chosen: Set<String> = emptySet(),
    val choosable: Set<String> = emptySet(),
) {
    val suggested: GalleryMember? get() = members.firstOrNull { it.suggested }

    /** MOVE memories offer the backup handoff, never deletion (GAL-4, AC-G3). */
    val hasMemories: Boolean get() = members.any { it.item.category == Category.MOVE }

    /** The chosen photos, in the group's order. */
    val chosenItems: List<ClassifiedItem> get() = members.map { it.item }.filter { it.item.path in chosen }
}

/**
 * G4b (C230/C231): what one group's choices hand to the existing review-trash path, without any file-action type
 * (that is `GalleryReview`'s job): the chosen REVIEW photos with the source versions they were compared at, and every
 * similar group any of them belongs to (overlapping groups too, also groups hidden for this session) with its photos
 * that are still available. The bridge keeps each group's unchosen photos as the evidence the choke point re-checks.
 */
data class GalleryDisposal(val groupId: String, val chosen: List<GalleryMember>, val groups: List<GalleryKeep>)

/** One similar group a disposal touches: its id and version and its available photos (chosen or not). */
data class GalleryKeep(val id: String, val version: String, val members: List<GalleryMember>)

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
            /** G4b: the session's choices per group id: the group version they were made for and the paths. */
            chosen: Map<String, Pair<String, Set<String>>> = emptyMap(),
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
            val all = grouping?.groups.orEmpty()
            // G4b (C230): a member is available only while the latest scan still has it as it was compared (same
            // size and time); a photo the user just kept is not offered (and is never counted as the one kept).
            val available = all.map { group -> availableMembers(group, items, hidden) }
            val groups = all.mapIndexedNotNull { index, group ->
                val suggested = (group.advice as? KeeperAdvice.Suggested)?.source?.key
                val members = available[index].map { it.copy(suggested = suggested == it.source?.key) }
                val id = groupId(group)
                if (members.size < 2 || id in dismissed) return@mapIndexedNotNull null
                // A suggestion for a member that can't be shown is no suggestion at all.
                val reasons = if (members.any { it.suggested }) {
                    (group.advice as KeeperAdvice.Suggested).reasons.take(2)
                } else {
                    emptyList()
                }
                val version = versionDigest(group.version)
                // GAL-9: a choice holds only for the same group version and photos still shown and reviewable.
                val own = chosen[id]?.takeIf { it.first == version }?.second.orEmpty()
                    .filter { path -> members.any { it.item.item.path == path && it.item.category == Category.REVIEW } }
                    .toSet()
                val choosable = members.filter { m ->
                    val path = m.item.item.path
                    m.item.category == Category.REVIEW && path !in own &&
                        // GAL-8: every group this photo belongs to keeps another available, unchosen photo.
                        all.indices.filter { g -> available[g].any { it.item.item.path == path } }.all { g ->
                            available[g].any { it.item.item.path != path && it.item.item.path !in own }
                        }
                }.map { it.item.item.path }.toSet()
                GalleryGroupUi(
                    id,
                    members.map { if (reasons.isEmpty()) it.copy(suggested = false) else it },
                    reasons,
                    version,
                    own,
                    choosable,
                )
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

        /** The group's photos the latest scan still has as they were compared, without [hidden] ones. */
        internal fun availableMembers(
            group: GalleryGroup,
            items: Map<SourceKey, ClassifiedItem>,
            hidden: Set<String>,
        ): List<GalleryMember> = group.version.members.mapNotNull { source ->
            items[source.key]?.takeIf {
                it.item.path !in hidden && it.item.size == source.size && it.item.mtimeMillis == source.mtimeMillis
            }?.let { GalleryMember(it, suggested = false, source = source) }
        }

        /** The group's id: its smallest member key (stable across runs for the same photos). */
        internal fun groupId(group: GalleryGroup): String =
            group.version.members.minOf { it.key }.let { "${it.volume}:${it.ref}" }

        /**
         * G4b (GAL-9): a digest of the group version (run, scan, permission epoch, policy and feature versions, every
         * member's key and source version), length-prefixed so no two versions read the same. A label that binds
         * choices to what was reviewed, never authority to act: the choke point re-reads the files.
         */
        fun versionDigest(version: GroupVersion): String {
            val parts = listOf(
                version.run.id,
                version.run.scanId,
                version.run.permissionEpoch.toString(),
                version.policy.version,
                version.policy.features.preprocessing,
                version.policy.features.algorithm,
            ) + version.members.sortedBy { it.key }.flatMap {
                listOf(
                    it.key.volume,
                    it.key.ref,
                    it.size.toString(),
                    it.mtimeMillis.toString(),
                    it.revision ?: "-",
                    it.observationId,
                )
            }
            val text = parts.joinToString("") { "${it.length}:$it" }
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            return digest.joinToString("") { "%02x".format(it) }
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
 * dismissals for this session only (C223). G4b (C230): it holds the user's explicit choices in memory and describes
 * them ([disposal]); it never preselects, plans or confirms anything (`GalleryReview` and the existing clean path do),
 * and it changes no lane total: the scan's summary is only read (GAL-21).
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

    /**
     * G4b (C230, GAL-6/9): the user's explicit choices per group id, with the group version they were made for. In
     * memory only: nothing is preselected, and nothing brings a choice or a confirmation back after process death.
     */
    private var chosen: Map<String, Pair<String, Set<String>>> = emptyMap()
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
        chosen = emptyMap()
        // GAL-9: a new comparison carries no earlier choice, not even while it starts.
        _state.update { s -> s.copy(groups = s.groups.map { it.copy(chosen = emptySet()) }) }
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

    /**
     * G4b (GAL-6): the user taps a REVIEW photo of group [groupId] to choose it for the trash, or taps again to
     * un-choose it. Only a photo the group offers ([GalleryGroupUi.choosable]) can be chosen; a memory, a photo of another
     * version or the last photo a similar group keeps can't (GAL-4, GAL-8).
     */
    fun toggleChoice(groupId: String, path: String) {
        val group = _state.value.groups.firstOrNull { it.id == groupId } ?: return
        val now = group.chosen
        val next = when (path) {
            in now -> now - path
            in group.choosable -> now + path
            else -> return
        }
        chosen = if (next.isEmpty()) chosen - groupId else chosen + (groupId to (group.version to next))
        last?.let(::publish)
    }

    /** G4b: the confirmation's ✕ ("Keep this one") takes [path] out of the group's choices. */
    fun unchoose(groupId: String, path: String) {
        val group = _state.value.groups.firstOrNull { it.id == groupId } ?: return
        if (path in group.chosen) toggleChoice(groupId, path)
    }

    /** G4b: the group's choices are forgotten (confirmed and handed to the trash path, or the group was left). */
    fun clearChoices(groupId: String) {
        chosen = chosen - groupId
        last?.let(::publish)
    }

    /**
     * G4b (C231): what the group's current choices hand to the review-trash path, or null when there is nothing to
     * hand over. Every similar group of the last comparison that holds a chosen photo is included, also overlapping
     * groups and groups hidden for this session, each with its photos that are still available (as the screen shows
     * them); `GalleryReview` keeps their unchosen photos as the evidence the choke point re-checks.
     */
    fun disposal(groupId: String): GalleryDisposal? {
        val group = _state.value.groups.firstOrNull { it.id == groupId } ?: return null
        val picked = group.members.filter { it.item.item.path in group.chosen }
        if (picked.isEmpty() || picked.any { it.item.category != Category.REVIEW || it.source == null }) return null
        val all = last?.grouping?.groups.orEmpty()
        val paths = picked.map { it.item.item.path }.toSet()
        val keeps = all.mapNotNull { g ->
            val members = GalleryUi.availableMembers(g, items, hidden)
            if (members.none { it.item.item.path in paths }) {
                null
            } else {
                GalleryKeep(GalleryUi.groupId(g), GalleryUi.versionDigest(g.version), members)
            }
        }
        return GalleryDisposal(groupId, picked, keeps)
    }

    /**
     * G4b (GAL-21, E-G09): the latest scan's items, after every scan (also the one that follows a clean). A photo the
     * scan no longer has, or has with another size or time, leaves every group at once and can't be chosen or kept
     * as evidence.
     */
    fun sync(summary: ClassificationSummary?) {
        if (summary == null) return
        val latest = GalleryUi.keyed(summary.groups.flatMap { it.items })
        if (latest == items) return
        items = latest
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
        val ui = GalleryUi.of(progress, items, dismissed, hidden, chosen)
        // GAL-9: choices that no longer apply (another group version, a photo gone or changed) are dropped.
        chosen = ui.groups.filter { it.chosen.isNotEmpty() }.associate { it.id to (it.version to it.chosen) }
        _state.value = ui
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
