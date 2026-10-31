// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.safety

import io.github.tomerar.keepclear.testing.fixtures.OwnerDevice
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Table tests for SAFETY-3 / MSG-2 / SEC-6 (E-SEC-9). */
class ProtectedPathsTest {
    private val protected = ProtectedPaths(extraRoots = listOf("/storage/emulated/0/Keep Forever"))

    private val mustProtect = listOf(
        "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Databases/msgstore.db.crypt14",
        "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Databases",
        "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Backups/stickers.db.crypt14",
        "/storage/emulated/0/Android/media/com.whatsapp.w4b/WhatsApp Business/Databases/msgstore.db.crypt14",
        "/storage/emulated/0/Android/media/com.whatsapp.w4b/WhatsApp Business/Backups",
        "/storage/emulated/999/Android/media/com.whatsapp/WhatsApp/Databases/msgstore.db.crypt14",
        "/storage/emulated/0/WhatsApp/Databases/msgstore-2021-12-01.1.db.crypt14",
        "/storage/1234-ABCD/WhatsApp/Databases/x",
        // case, aliases, NFD, zero-width and bidi tricks
        "/storage/emulated/0/android/media/com.whatsapp/whatsapp/DATABASES/x",
        "/sdcard/WhatsApp/Databases/msgstore.db.crypt14",
        "/storage/self/primary/WhatsApp/Backups/a",
        "/storage/emulated/0/Android/media/com.whatsapp/Whats​App/Databases/x",
        "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Data⁨bases⁩/x",
        "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Databases/x́",
        // database files anywhere
        "/storage/emulated/0/Download/msgstore.db.crypt14",
        "/storage/emulated/0/Download/old.crypt12",
        "/storage/emulated/0/Download/wa.db",
        "/storage/emulated/0/Download/axolotl.db",
        "/storage/emulated/0/Download/chatsettingsbackup.db.crypt14",
        // our trash and exclusions
        "/storage/emulated/0/.KeepClearTrash/20261031/abc/IMG.jpg",
        "/storage/emulated/0/Keep Forever/a.jpg",
        "/storage/emulated/0/keep forever",
        // unparseable
        "relative/path",
        "/storage/emulated/0/../0/WhatsApp/Databases/x",
        "",
    )

    private val mustNotProtect = listOf(
        "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/IMG-20240101-WA0001.jpg",
        "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/Sent/IMG.jpg",
        "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/.Statuses/x.jpg",
        "/storage/emulated/0/DCIM/Camera/IMG_20240101_120000.jpg",
        "/storage/emulated/0/Documents/backups/report.pdf",
        "/storage/emulated/0/Download/databases-notes.txt",
        "/storage/emulated/0/Pictures/WhatsApp Images/IMG.jpg",
        "/storage/emulated/0/Keep Forever 2/a.jpg",
    )

    @Test
    fun `protected table`() {
        mustProtect.forEach { path -> check(protected.isProtected(path)) { "should be PROTECTED: $path" } }
    }

    @Test
    fun `not protected table`() {
        mustNotProtect.forEach { path -> check(!protected.isProtected(path)) { "should NOT be protected: $path" } }
    }

    @Test
    fun `every fixture database is protected`() {
        val fixture = OwnerDevice.build()
        fixture.protectedPaths.forEach { protected.isProtected(it) shouldBe true }
    }

    @Test
    fun `containers are protected for directory ops`() {
        listOf(
            "/storage/emulated/0",
            "/storage/1234-ABCD",
            "/storage/emulated/0/Android",
            "/storage/emulated/0/Android/media",
            "/storage/emulated/0/Android/media/com.whatsapp",
            "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp",
            "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media",
            "/storage/emulated/0/WhatsApp",
            "/storage/emulated/0/WhatsApp/Media",
            "/storage/emulated/0", // ancestor of an exclusion
        ).forEach { check(protected.isProtectedContainer(it)) { "container should be protected: $it" } }
        listOf(
            "/storage/emulated/0/MIUI/ShareMe",
            "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/Sent",
            "/storage/emulated/0/Download/trip",
        ).forEach { check(!protected.isProtectedContainer(it)) { "should be allowed: $it" } }
    }
}
