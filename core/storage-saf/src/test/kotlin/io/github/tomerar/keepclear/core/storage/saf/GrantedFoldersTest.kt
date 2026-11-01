// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.storage.saf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** M12b, ONB-6: table of granted folders and where they are mounted. */
class GrantedFoldersTest {
    private val ext = GrantedFolders.EXTERNAL_STORAGE

    @Test
    fun `mount points of Android's storage provider`() {
        val cases = mapOf(
            "primary:Android/media/com.whatsapp/WhatsApp" to "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp",
            "1234-ABCD:Android/media/com.whatsapp/WhatsApp" to "/storage/1234-ABCD/Android/media/com.whatsapp/WhatsApp",
            "primary:Android/media/com.whatsapp/WhatsApp/" to "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp",
        )
        cases.forEach { (id, expected) -> assertEquals(id, expected, GrantedFolders.mountPoint(ext, id)) }
    }

    @Test
    fun `no mount point for other providers, odd volumes, whole volumes or dot segments`() {
        assertNull(GrantedFolders.mountPoint("com.android.providers.downloads.documents", "primary:Download"))
        assertNull(GrantedFolders.mountPoint(ext, "home:Documents"))
        assertNull(GrantedFolders.mountPoint(ext, "primary:"))
        assertNull(GrantedFolders.mountPoint(ext, "primary:Android/../DCIM"))
        assertNull(GrantedFolders.mountPoint(ext, "no-colon"))
    }

    @Test
    fun `only WhatsApp's own folder is accepted`() {
        assertTrue(GrantedFolders.isWhatsAppFolder("primary:Android/media/com.whatsapp/WhatsApp"))
        assertTrue(GrantedFolders.isWhatsAppFolder("primary:Android/media/com.whatsapp.w4b/WhatsApp Business"))
        assertFalse(GrantedFolders.isWhatsAppFolder("primary:Android/media/com.whatsapp"))
        assertFalse(GrantedFolders.isWhatsAppFolder("primary:Android/media/com.whatsapp/WhatsApp/Media"))
        assertFalse(GrantedFolders.isWhatsAppFolder("primary:DCIM"))
    }
}
