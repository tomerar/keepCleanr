// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** PR #23 review: a granted folder stays a trash volume in every access mode; only the routing changes. */
class GrantedFolderRootsTest {
    private val point = "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp"
    private val found = mapOf(point to "mount")

    @Test
    fun `without All files access the folder is mounted and is a trash volume`() {
        val split = GrantedFolderSplit.of(found, allFiles = false)
        assertEquals(setOf(point), split.trashRoots)
        assertEquals(setOf(point), split.mounts.keys)
    }

    @Test
    fun `with All files access it is not mounted but stays a trash volume (no orphaned trash)`() {
        val split = GrantedFolderSplit.of(found, allFiles = true)
        assertEquals(setOf(point), split.trashRoots)
        assertTrue(split.mounts.isEmpty())
    }
}
