// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.classify

import io.github.tomerar.keepclear.core.model.Category
import io.github.tomerar.keepclear.engine.scan.ScanItem

/** Short, plain-language risk label shown on every item (CLS-2). Stored by name. */
enum class RiskChip {
    /** Apps or Android rebuild it. */
    REGENERATES,

    /** An identical copy exists elsewhere on the phone. */
    COPY_EXISTS,

    /** Already deleted once, waiting in another app's bin. */
    ALREADY_DELETED,

    /** Could be a photo, video or recording someone cares about. */
    MIGHT_BE_MEMORY,

    /** Chat history. Never touched. */
    YOUR_CHATS,

    /** We don't know; look inside first. */
    UNKNOWN,
}

/**
 * Facts computed by second-pass enrichers (duplicates, archives; M7) and passed to the classifier.
 * Stored by name.
 */
enum class Fact {
    /** Byte-identical (SHA-256) copy exists outside messenger folders. */
    HAS_TWIN_OUTSIDE_MESSENGER,

    /** Every archive entry exists, same name/size/CRC, in the sibling folder. */
    ARCHIVE_FULLY_EXTRACTED,

    /** Some, not all, archive entries exist next to it. */
    ARCHIVE_PARTIALLY_EXTRACTED,
}

/** Grouping for presentation: rule plus an optional bucket such as "2023-03". */
data class GroupKey(val ruleId: String, val bucket: String? = null)

/**
 * Result of classification (design.md §3.2). Explanation strings are resource keys
 * `why_<ruleId>`, `if_deleted_<ruleId>` and `group_<ruleId>` in `core:i18n` (CLS-2).
 */
data class ClassifiedItem(
    val item: ScanItem,
    val category: Category,
    val ruleId: String,
    val group: GroupKey,
    val risk: RiskChip,
    /** True when a keep signal (favourite, recently modified) moved it toward safety (CLS-5). */
    val keepSignal: Boolean = false,
) {
    val whyKey: String get() = "why_$ruleId"
    val ifDeletedKey: String get() = "if_deleted_$ruleId"
    val groupKey: String get() = "group_$ruleId"
}
