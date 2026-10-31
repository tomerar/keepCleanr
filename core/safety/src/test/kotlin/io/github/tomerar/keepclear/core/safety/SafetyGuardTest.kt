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
}
