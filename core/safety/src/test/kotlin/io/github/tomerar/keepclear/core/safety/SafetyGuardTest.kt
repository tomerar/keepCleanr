// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.safety

import io.github.tomerar.keepclear.core.storage.KPath
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class SafetyGuardTest {
    private val guard = SafetyGuard(ProtectedPaths())

    private fun op(
        path: String,
        kind: OpKind = OpKind.TRASH,
        dir: Boolean = false,
        sha: String? = null,
        group: String? = null,
    ) = PlannedOp(KPath.Local(path), path, kind, dir, 10, 1, expectedSha256 = sha, duplicateGroup = group)

    private val photo = "$ROOT/DCIM/Camera/IMG_1.jpg"

    @Test
    fun `confirmed plan in this session is accepted`() {
        guard.check(plan(op(photo)), SESSION) shouldBe SafetyGuard.Verdict.Accepted
    }

    @Test
    fun `unconfirmed plan is rejected (SAFETY-1)`() {
        val unconfirmed = ActionPlan("p", listOf(op(photo)), "t")
        guard.check(unconfirmed, SESSION).shouldBeInstanceOf<SafetyGuard.Verdict.Rejected>()
    }

    @Test
    fun `plan confirmed in an earlier session is rejected (MIG-5)`() {
        guard.check(plan(op(photo), session = "old"), SESSION).shouldBeInstanceOf<SafetyGuard.Verdict.Rejected>()
    }

    @Test
    fun `one protected op rejects the whole plan (SAFETY-3)`() {
        val verdict = guard.check(
            plan(op(photo), op("$ROOT/Android/media/com.whatsapp/WhatsApp/Databases/msgstore.db.crypt14")),
            SESSION,
        )
        (verdict as SafetyGuard.Verdict.Rejected).reasons shouldHaveSize 1
    }

    @Test
    fun `directory op on a messenger root is rejected`() {
        guard.check(plan(op("$ROOT/Android/media/com.whatsapp/WhatsApp", dir = true)), SESSION)
            .shouldBeInstanceOf<SafetyGuard.Verdict.Rejected>()
    }

    @Test
    fun `MOVE original needs a valid SHA-256 (SAFETY-5)`() {
        guard.check(
            plan(op(photo, OpKind.MOVE_DELETE_ORIGINAL)),
            SESSION,
        ).shouldBeInstanceOf<SafetyGuard.Verdict.Rejected>()
        guard.check(plan(op(photo, OpKind.MOVE_DELETE_ORIGINAL, sha = "XYZ")), SESSION)
            .shouldBeInstanceOf<SafetyGuard.Verdict.Rejected>()
        guard.check(plan(op(photo, OpKind.MOVE_DELETE_ORIGINAL, sha = "a".repeat(64))), SESSION) shouldBe
            SafetyGuard.Verdict.Accepted
    }

    @Test
    fun `duplicate group keeps at least one copy (SAFETY-8)`() {
        val a = op("$ROOT/a.jpg", group = "g")
        val b = op("$ROOT/b.jpg", group = "g")
        guard.check(plan(a, b, groups = mapOf("g" to 2)), SESSION).shouldBeInstanceOf<SafetyGuard.Verdict.Rejected>()
        guard.check(plan(a, groups = mapOf("g" to 2)), SESSION) shouldBe SafetyGuard.Verdict.Accepted
        guard.check(plan(a), SESSION).shouldBeInstanceOf<SafetyGuard.Verdict.Rejected>() // unknown group size
    }

    @Test
    fun `purge only inside the trash`() {
        guard.check(plan(op(photo, OpKind.PURGE)), SESSION).shouldBeInstanceOf<SafetyGuard.Verdict.Rejected>()
        guard.check(plan(op("$ROOT/.KeepClearTrash/1/x.jpg", OpKind.PURGE)), SESSION) shouldBe
            SafetyGuard.Verdict.Accepted
        // A look-alike folder whose name only normalises to the trash is not the trash.
        guard.check(plan(op("$ROOT/Download/.KeepClear\u200BTrash/photo.jpg", OpKind.PURGE)), SESSION)
            .shouldBeInstanceOf<SafetyGuard.Verdict.Rejected>()
    }

    @Test
    fun `purge containment is canonical - only the trash at a volume root counts (review Low)`() {
        // A user folder that happens to be named like the trash is not the trash.
        guard.check(plan(op("$ROOT/DCIM/.KeepClearTrash/IMG_1.jpg", OpKind.PURGE)), SESSION)
            .shouldBeInstanceOf<SafetyGuard.Verdict.Rejected>()
        guard.check(plan(op("/storage/1234-ABCD/.KeepClearTrash/1/x.jpg", OpKind.PURGE)), SESSION) shouldBe
            SafetyGuard.Verdict.Accepted
        guard.check(plan(op("/storage/1234-ABCD/Music/.KeepClearTrash/x.mp3", OpKind.PURGE)), SESSION)
            .shouldBeInstanceOf<SafetyGuard.Verdict.Rejected>()
        // The trash folder itself is never a purge target.
        guard.check(plan(op("$ROOT/.KeepClearTrash", OpKind.PURGE)), SESSION)
            .shouldBeInstanceOf<SafetyGuard.Verdict.Rejected>()
    }

    @Test
    fun `a granted folder's own trash may be emptied, nothing deeper or elsewhere (M12b, PR 23 review)`() {
        val whatsapp = "$ROOT/Android/media/com.whatsapp/WhatsApp"
        val trashed = "$whatsapp/.KeepClearTrash/20261101/1/IMG-1.jpg"
        // Without the grant it is a user folder named like the trash: refused.
        guard.check(plan(op(trashed, OpKind.PURGE)), SESSION).shouldBeInstanceOf<SafetyGuard.Verdict.Rejected>()
        val granted = SafetyGuard(ProtectedPaths(), grantedTrashRoots = listOf(whatsapp))
        granted.check(plan(op(trashed, OpKind.PURGE)), SESSION) shouldBe SafetyGuard.Verdict.Accepted
        // Deeper look-alikes, the trash folder itself and other folders stay refused.
        granted.check(plan(op("$whatsapp/Media/.KeepClearTrash/x.jpg", OpKind.PURGE)), SESSION)
            .shouldBeInstanceOf<SafetyGuard.Verdict.Rejected>()
        granted.check(plan(op("$whatsapp/.KeepClearTrash", OpKind.PURGE)), SESSION)
            .shouldBeInstanceOf<SafetyGuard.Verdict.Rejected>()
        granted.check(plan(op("$whatsapp/.KeepClear\u200BTrash/x.jpg", OpKind.PURGE)), SESSION)
            .shouldBeInstanceOf<SafetyGuard.Verdict.Rejected>()
        granted.check(plan(op("$ROOT/Android/media/com.whatsapp/Other/.KeepClearTrash/x", OpKind.PURGE)), SESSION)
            .shouldBeInstanceOf<SafetyGuard.Verdict.Rejected>()
    }

    @Test
    fun `a plan confirmed by the user's tap may only hold what was shown (review PR B)`() {
        val a = op(photo)
        val other = op("$ROOT/DCIM/Camera/other.jpg")
        val shown = Confirmation.of(listOf(photo), at = 5)
        guard.check(ActionPlan("p", listOf(a), "t").confirm(shown, SESSION), SESSION) shouldBe
            SafetyGuard.Verdict.Accepted
        guard.check(ActionPlan("p", listOf(a, other), "t").confirm(shown, SESSION), SESSION)
            .shouldBeInstanceOf<SafetyGuard.Verdict.Rejected>()
        // Unconfirmed plans are still rejected.
        guard.check(ActionPlan("p", listOf(a), "t"), SESSION).shouldBeInstanceOf<SafetyGuard.Verdict.Rejected>()
    }

    @Test
    fun `an op whose path ref differs from the path shown is rejected (review PR B)`() {
        val swapped = op(photo).copy(ref = io.github.tomerar.keepclear.core.storage.KPath.Local("$ROOT/DCIM/other.jpg"))
        guard.check(plan(swapped), SESSION).shouldBeInstanceOf<SafetyGuard.Verdict.Rejected>()
    }

    @Test
    fun `a trashed item confirmed by its original path matches through shownAs`() {
        val trashed = "$ROOT/.KeepClearTrash/1/x.jpg"
        val restore = op(trashed, OpKind.RESTORE).copy(shownAs = photo)
        guard.check(
            ActionPlan("p", listOf(restore), "t").confirm(Confirmation.of(listOf(photo), 1), SESSION),
            SESSION,
        ) shouldBe SafetyGuard.Verdict.Accepted
    }

    @Test
    fun `a MediaStore row may be planned for purge, its trashed state is checked by the media handler (M12)`() {
        val row = op(
            photo,
            OpKind.PURGE,
        ).copy(ref = io.github.tomerar.keepclear.core.storage.KPath.Media("external_primary", 3))
        guard.check(plan(row), SESSION) shouldBe SafetyGuard.Verdict.Accepted
        // Still PROTECTED-checked like everything else? PURGE is trash-only, so a protected display path is rejected
        // only for non-trash kinds; a TRASH of the same protected row is rejected.
        val protectedRow = op("$ROOT/Android/media/com.whatsapp/WhatsApp/Databases/msgstore.db.crypt14")
            .copy(ref = io.github.tomerar.keepclear.core.storage.KPath.Media("external_primary", 4))
        guard.check(plan(protectedRow), SESSION).shouldBeInstanceOf<SafetyGuard.Verdict.Rejected>()
    }
}
