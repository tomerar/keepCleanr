// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import io.github.tomerar.keepclear.core.safety.ProtectedPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** MSG-3: cloned-app storage (user 999) fixtures. */
class ClonedAppsTest {
    private val clone = "/storage/emulated/999"

    @Test
    fun `a readable clone root is scanned`() {
        val s = ClonedApps.probe(true, exists = { it == clone }, canList = { true })
        assertEquals(listOf(clone), s.readable)
        assertFalse(s.hidden)
    }

    @Test
    fun `a clone root that cannot be listed is reported hidden, not scanned`() {
        val s = ClonedApps.probe(true, exists = { it == clone }, canList = { false })
        assertTrue(s.readable.isEmpty())
        assertTrue(s.hidden)
    }

    @Test
    fun `without all-files access nothing is claimed`() {
        assertEquals(ClonedStorage(), ClonedApps.probe(false, exists = { true }, canList = { true }))
    }

    @Test
    fun `no clone root, nothing shown`() {
        assertEquals(ClonedStorage(), ClonedApps.probe(true, exists = { false }, canList = { true }))
    }

    @Test
    fun `clone paths are labelled, the main user is not`() {
        assertTrue(ClonedApps.isCloned("$clone/WhatsApp/Media/a.jpg"))
        assertFalse(ClonedApps.isCloned("/storage/emulated/0/WhatsApp/Media/a.jpg"))
        assertFalse(ClonedApps.isCloned("/storage/emulated/10/x.jpg"))
    }

    @Test
    fun `cloned WhatsApp chats and backups stay protected (MSG-2 under MSG-3)`() {
        val p = ProtectedPaths()
        assertTrue(p.isProtected("$clone/Android/media/com.whatsapp/WhatsApp/Databases/msgstore.db.crypt14"))
        assertTrue(p.isProtected("$clone/WhatsApp/Backups/chatsettingsbackup.db.crypt1"))
        assertTrue(p.isProtectedContainer(clone))
        assertFalse(p.isProtected("$clone/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/a.jpg"))
    }
}
