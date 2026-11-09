// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.tomerar.keepclear.core.model.Category
import io.github.tomerar.keepclear.core.safety.ActionPlan
import io.github.tomerar.keepclear.core.safety.Confirmation
import io.github.tomerar.keepclear.core.safety.RetainedMember
import io.github.tomerar.keepclear.core.safety.VisualRetention
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.core.storage.media.MediaGateway
import io.github.tomerar.keepclear.engine.enrich.CleanPlanner

/**
 * G4b (C231, GAL-7/8/9, AC-G3/5/6): the bridge from a similar-photos group to the existing review-trash path. It turns
 * one group's explicit REVIEW choices into the usual clean plan ([CleanPlanner], to the 30-day trash, never permanent)
 * and attaches to each op the typed keep-one evidence of every similar group its photo belongs to: each group's
 * available photos the user did not choose ([VisualRetention]). The choke point re-checks that evidence; nothing here
 * decides that a deletion is safe.
 */
object GalleryReview {
    /**
     * The unconfirmed plan for [disposal], or null when it can't be planned safely: nothing chosen, a chosen photo that
     * is not REVIEW (a memory never reaches the trash from a group, GAL-4), a photo without the version it was compared
     * at, or a group whose kept photos can't be described with an identity.
     */
    fun plan(id: String, disposal: GalleryDisposal): ActionPlan? {
        val chosen = disposal.chosen
        if (chosen.isEmpty() || chosen.any { it.item.category != Category.REVIEW }) return null
        val paths = chosen.map { it.item.item.path }.toSet()
        if (paths.size != chosen.size) return null
        val evidence = disposal.groups.map { group ->
            val kept = group.members.filter { it.item.item.path !in paths }.map { m ->
                RetainedMember(
                    m.item.item.ref,
                    m.item.item.path,
                    m.item.item.size,
                    m.item.item.mtimeMillis,
                    expectedIdentity(m) ?: return null,
                )
            }
            group to VisualRetention("${group.id}#${group.version}", group.version, kept)
        }
        // Exact-duplicate evidence is deliberately not used here: similarity is not equality (GAL-3).
        val base = CleanPlanner.plan(id, chosen.map { it.item }, emptyMap(), "gallery")
        val ops = base.ops.map { op ->
            val member = chosen.first { it.item.item.path == op.displayPath }
            val groups = evidence.filter { (g, _) -> g.members.any { it.item.item.path == op.displayPath } }
                .map { it.second }
            if (groups.isEmpty()) return null
            op.copy(expectedIdentity = expectedIdentity(member) ?: return null, visualGroups = groups)
        }
        return base.copy(ops = ops)
    }

    /**
     * The identity a photo must still have (E-G07, T-G5): for a MediaStore row, the generation the comparison read
     * (from its source revision) and "not in Android's trash", in [MediaGateway]'s format, so an edit with the same
     * size and time, or a move to the gallery's bin, is noticed; for a file, the scan's own identity. Null when it
     * can't be known (then nothing is planned).
     */
    fun expectedIdentity(member: GalleryMember): String? = when (member.item.item.ref) {
        is KPath.Media -> member.source?.revision?.takeIf { it.startsWith("media:") }
            ?.substringAfterLast(':')?.toLongOrNull()?.takeIf { it > 0 }
            ?.let { MediaGateway.identity(it, trashed = false) }

        else -> member.item.item.identity
    }
}

/** G4b: what the comparison's review needs from Home (the existing clean path, its Undo and the Trash). */
class GalleryReviewActions(
    /** The existing clean path, with the bridge's plan ([HomeViewModel.cleanGallery]). */
    val onDispose: (GalleryDisposal, Confirmation) -> Unit = { _, _ -> },
    val onUndo: (CleanResult, Confirmation) -> Unit = { _, _ -> },
    val onOpenTrash: () -> Unit = {},
)

/**
 * G4b: one group with its review. The confirmation is the existing editable one ([EditableConfirm]: exact count,
 * size, "Move to trash", ✕ to keep any photo); its open state is plain `remember`, so it is never restored after the
 * process ends (GAL-9, E-G08), and the choices themselves live only in the controller's memory. The result of the
 * last action from this screen shows above the photos, with Undo when there is something to put back.
 */
@Composable
fun GalleryReviewGroupScreen(
    group: GalleryGroupUi,
    actions: GalleryActions,
    disposal: (String) -> GalleryDisposal?,
    onUnchoose: (groupId: String, path: String) -> Unit,
    onChoicesDone: (groupId: String) -> Unit,
    review: GalleryReviewActions,
    result: CleanResult?,
    onBack: () -> Unit,
) {
    var confirming by remember { mutableStateOf(false) }
    GalleryGroupScreen(
        group,
        GalleryActions(
            onStart = actions.onStart,
            onStop = actions.onStop,
            onContinue = actions.onContinue,
            onAllowAccess = actions.onAllowAccess,
            onCheckAccess = actions.onCheckAccess,
            onKeepThisTime = actions.onKeepThisTime,
            onDontOffer = actions.onDontOffer,
            onBackUp = actions.onBackUp,
            onOpenPhoto = actions.onOpenPhoto,
            onToggleChoice = actions.onToggleChoice,
            onReviewChosen = { confirming = true },
        ),
        onBack,
        header = result?.let { { GalleryResult(it, review) } },
    )
    val shown = group.chosenItems
    if (confirming && shown.isEmpty()) confirming = false
    if (confirming) {
        EditableConfirm(
            items = shown,
            onRemove = { removed -> onUnchoose(group.id, removed.item.path) },
            onConfirm = {
                confirming = false
                val d = disposal(group.id)
                // The tap names exactly the photos the sheet showed; the guard refuses anything else (review PR B).
                if (d != null) review.onDispose(d, userConfirmed(shown.map { it.item.path }))
                onChoicesDone(group.id)
            },
            onDismiss = { confirming = false },
        )
    }
}

/** The existing result banner for an action started from a gallery screen; nothing when there is none. */
@Composable
fun GalleryResult(result: CleanResult?, review: GalleryReviewActions) {
    result?.let {
        CleanResultBanner(it, review.onUndo, review.onOpenTrash, Modifier.testTag("galleryCleanResult"))
    }
}
