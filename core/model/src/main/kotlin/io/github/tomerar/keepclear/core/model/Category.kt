// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.model

/**
 * How KeepClear treats an item (requirements §0 glossary).
 *
 * Stored by [name] in Room and JSON; never rename a constant (MIG-2/3).
 */
enum class Category {
    /** Regenerable or provably duplicate. One tap (after one confirmation) sends it to trash. */
    SAFE,

    /** Possibly wanted. Explained; the user decides. Never pre-selected. */
    REVIEW,

    /** Memories. Removed only after backup → L3 verify → confirmation. Never pre-selected. */
    MOVE,

    /** Never offered and never deletable by any code path (SAFETY-3). */
    PROTECTED,
}
