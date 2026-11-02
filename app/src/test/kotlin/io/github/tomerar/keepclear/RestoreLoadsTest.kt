// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PR #48 review: a backup that finishes loading late never shows in a newer restore flow. */
class RestoreLoadsTest {
    @Test
    fun `a load from a cancelled flow is stale even when a new flow is loading`() {
        val loads = RestoreLoads()
        val old = loads.begin()
        assertTrue(loads.isCurrent(old))
        loads.end() // Cancel while it loads
        val new = loads.begin() // a new restore starts before the old read returns
        assertFalse("the old read must be dropped", loads.isCurrent(old))
        assertTrue(loads.isCurrent(new))
    }

    @Test
    fun `reading the catalog makes the opening's token stale, and no token is ever current`() {
        val loads = RestoreLoads()
        val opening = loads.begin()
        val catalog = loads.begin()
        assertFalse(loads.isCurrent(opening))
        assertTrue(loads.isCurrent(catalog))
        assertFalse(loads.isCurrent(RestoreLoads.NONE))
    }
}
