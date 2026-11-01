// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.enrich

import io.github.tomerar.keepclear.core.safety.ActionPlan
import io.github.tomerar.keepclear.core.safety.OpKind
import io.github.tomerar.keepclear.core.safety.PlannedOp
import io.github.tomerar.keepclear.engine.classify.ClassifiedItem

/**
 * Builds the unconfirmed "Clean now" / review plan (SAFE-1, REV-3). SAFETY-6: it carries what was scanned and
 * classified, not what is there now; the executor re-checks against it. H2: every op whose safety rests on
 * another copy carries its duplicate group and witnesses, and any op that deletes a witness joins the same group,
 * so the guard rejects a plan that would remove every copy (SAFETY-8).
 */
object CleanPlanner {
    fun plan(id: String, items: List<ClassifiedItem>, evidence: Map<String, Evidence>, reason: String): ActionPlan {
        val groupOfWitness = HashMap<String, String>()
        for (item in items) {
            val ev = evidence[item.item.path] ?: continue
            ev.witnesses.forEach { groupOfWitness[it.path] = ev.group }
        }
        // Copies known per group: every redundant item plus every witness (two Sent copies of one camera photo
        // are a group of three).
        val copies = HashMap<String, MutableSet<String>>()
        for ((path, ev) in evidence) {
            copies.getOrPut(ev.group) { mutableSetOf() }.apply {
                add(path)
                ev.witnesses.forEach { add(it.path) }
            }
        }
        val sizes = HashMap<String, Int>()
        val ops = items.map { c ->
            val ev = evidence[c.item.path]
            val group = ev?.group ?: groupOfWitness[c.item.path]
            if (group != null) sizes[group] = copies[group]?.size ?: 1
            PlannedOp(
                c.item.ref,
                c.item.path,
                OpKind.TRASH,
                false,
                c.item.size,
                c.item.mtimeMillis,
                c.item.identity,
                duplicateGroup = group,
                ruleId = c.ruleId,
                witnesses = ev?.witnesses.orEmpty(),
            )
        }
        return ActionPlan(id, ops, reason, duplicateGroupSizes = sizes)
    }
}
