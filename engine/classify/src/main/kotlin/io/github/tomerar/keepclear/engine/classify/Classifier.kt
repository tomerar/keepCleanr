// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.classify

import io.github.tomerar.keepclear.core.model.Category
import io.github.tomerar.keepclear.core.safety.ProtectedPaths
import io.github.tomerar.keepclear.engine.scan.ScanItem

/**
 * Deterministic classification (CLS-1): PROTECTED first (asked of `core:safety`, so the two can never
 * disagree), then the first matching rule in [Rules.all], then [Rules.unknown].
 *
 * Keep signals (CLS-5) only move items toward safety: a favourite or recently modified SAFE item
 * becomes REVIEW; MOVE and REVIEW are unchanged (they are never pre-selected anyway, SAFETY-10).
 * Owner decision B1: regenerable rules ([Rule.regenerable]) ignore the age signal, never favourites.
 */
class Classifier(
    private val protectedPaths: ProtectedPaths = ProtectedPaths(),
    private val rules: List<Rule> = Rules.all,
    private val recentMillis: Long = 30L * 24 * 3_600_000,
) {
    fun classify(item: ScanItem, facts: Set<Fact> = emptySet(), now: Long): ClassifiedItem {
        val (canonical, rel) = Rules.relativeSegments(item.path)
            ?: return protectedItem(item) // unparseable path: refuse to classify as anything deletable
        if (protectedPaths.isProtected(item.path)) return protectedItem(item)
        val input = RuleInput(item, canonical, rel, facts, now)
        val rule = rules.firstOrNull { it.matches(input) } ?: Rules.unknown
        val recent = (now - item.mtimeMillis) in 0 until recentMillis
        // B1: thumbnails, temp files, old statuses and OS junk regenerate, so their age says nothing about
        // value. Everything else SAFE (archives, duplicates, other apps' bins, LOST.DIR) still needs review
        // when recent.
        val keep = item.isFavorite || (recent && !rule.regenerable)
        // SAFE is an allowlist for deletion: it must not rely on lossy normalisation. If removing
        // zero-width/bidi characters changed the path, a junk-looking name is not trusted as junk.
        val lossy = canonical != losslessLowercase(item.path)
        val downgraded = rule.category == Category.SAFE && (keep || lossy)
        return ClassifiedItem(
            item = item,
            category = if (downgraded) Category.REVIEW else rule.category,
            ruleId = rule.id,
            group = GroupKey(rule.id, rule.bucket(input)),
            risk = rule.risk,
            keepSignal = downgraded,
        )
    }

    private fun losslessLowercase(path: String): String =
        "/" + java.text.Normalizer.normalize(path, java.text.Normalizer.Form.NFC).lowercase(java.util.Locale.ROOT)
            .split('/').filter { it.isNotEmpty() }.joinToString("/")

    private fun protectedItem(item: ScanItem) = ClassifiedItem(
        item = item,
        category = Category.PROTECTED,
        ruleId = Rules.PROTECTED_RULE_ID,
        group = GroupKey(Rules.PROTECTED_RULE_ID),
        risk = RiskChip.YOUR_CHATS,
    )

    /** All rule ids whose explanation strings must exist (CI string completeness, CLS-2). */
    companion object {
        val explainedRuleIds: List<String> = Rules.all.map { it.id } + Rules.unknown.id + Rules.PROTECTED_RULE_ID
    }
}
