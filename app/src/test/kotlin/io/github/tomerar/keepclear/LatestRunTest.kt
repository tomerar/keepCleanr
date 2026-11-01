// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PR #27 review: an older classification never overwrites a newer one. */
class LatestRunTest {
    @Test
    fun `an earlier run is superseded by a later one`() {
        val runs = LatestRun()
        val first = runs.start()
        val second = runs.start()
        assertFalse(runs.isLatest(first))
        assertTrue(runs.isLatest(second))
    }
}
