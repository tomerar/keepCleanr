// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.safety

import io.github.tomerar.keepclear.core.storage.KPath

/**
 * G4b (GAL-8, AC-G6, C227): one photo of a similar-photos group that stays on the phone while other members of the
 * same group are moved to the trash. It is what the user saw in the group: the scan's ref, path, size, modification
 * time and identity (generation and trashed flag for a MediaStore row, inode for a file). The executor re-reads it
 * right before each op of the group ([ActionExecutor]); a member that is gone, changed, in a trash, kept or protected
 * since then makes the group's ops refused.
 *
 * It is **not** a [Witness]: it never claims that the retained photo has the same bytes as the one being trashed, it
 * carries no SHA-256, and nothing turns it into a duplicate group, a [BackupProof] or a permanent delete (GAL-3).
 */
data class RetainedMember(
    val ref: KPath,
    val path: String,
    val size: Long,
    val mtime: Long,
    /** Recorded identity; when it is not null the current one must be present and equal. */
    val identity: String?,
)

/**
 * G4b (GAL-8, GAL-9, C227): the typed keep-one evidence of a similar-photos group. The app attaches it to the op of
 * every photo of that group the plan moves, and an op lists the evidence of every group its photo belongs to
 * ([PlannedOp.visualGroups]), so overlapping groups each keep a member. [groupId] names the group within one plan;
 * [groupVersion] binds the evidence to the group version the user reviewed (a digest of the run, policy and member
 * source versions; a label, never an authority). [retained] are the members the user did **not** choose, at least
 * one.
 *
 * Rules at the choke point:
 * - [SafetyGuard.check] rejects the whole plan when the evidence is malformed (no retained member, blank ids), sits
 *   on an op that is not a plain REVIEW move to the trash of one file, differs between ops of one group, names one
 *   group twice on an op, or names a retained member that any op of the same plan acts on (choosing every member,
 *   overlapping groups).
 * - [ActionExecutor] re-checks every retained member right before each op of the group and refuses the group for the
 *   rest of the run ([SkipReason.RETAINED_MEMBER_GONE]) when one of them no longer holds.
 * - [PermanentKinds] never deletes such an op for good: it always goes to the 30-day trash.
 */
data class VisualRetention(
    val groupId: String,
    val groupVersion: String,
    val retained: List<RetainedMember>,
)
