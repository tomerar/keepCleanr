// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.backup.saf

import io.github.tomerar.keepclear.backup.TargetCaps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** MOVE-14 / SEC-3: provider classification and strict marker parsing, table-driven. */
class SafTargetsTest {
    @Test
    fun `classifies providers`() {
        val ext = SafTargets.EXTERNAL_STORAGE
        assertNull(SafTargets.classify(ext, "primary:DCIM"))
        assertNull(SafTargets.classify(ext, "primary:"))
        assertEquals(TargetKind.REMOVABLE, SafTargets.classify(ext, "1234-ABCD:")!!.first)
        assertEquals(true, SafTargets.classify(ext, "1234-ABCD:Backups")!!.second.canReadBack)
        assertEquals(TargetCaps.FAT32_MAX_FILE, SafTargets.classify(ext, "1234-ABCD:")!!.second.maxFileSize)
        val drive = SafTargets.classify("com.google.android.apps.docs.storage", "acc=1;doc=root")!!
        assertEquals(TargetKind.CLOUD_COPY_ONLY, drive.first)
        assertEquals(false, drive.second.canReadBack)
        assertEquals(TargetKind.UNKNOWN_PROVIDER, SafTargets.classify("org.example.docs", "x")!!.first)
        assertEquals(TargetKind.UNKNOWN_PROVIDER, SafTargets.classify(null, "x")!!.first)
    }

    @Test
    fun `an unknown provider is copy-only - it may answer reads from a cache (H1)`() {
        val unknown = SafTargets.classify("org.example.docs", "x")!!
        assertEquals(TargetKind.UNKNOWN_PROVIDER, unknown.first)
        assertEquals(false, unknown.second.canReadBack)
        assertEquals(false, SafTargets.classify(null, "x")!!.second.canReadBack)
        // Debug builds' fake USB provider is declared removable explicitly.
        val test = SafTargets.classify("app.testusb", "root", removableAuthorities = setOf("app.testusb"))!!
        assertEquals(TargetKind.REMOVABLE, test.first)
        assertEquals(true, test.second.canReadBack)
    }

    @Test
    fun `phone-side document roots are on this phone, not removable (H3)`() {
        val ext = SafTargets.EXTERNAL_STORAGE
        assertNull(SafTargets.classify(ext, "home:"))
        assertNull(SafTargets.classify(ext, "home:Backups"))
        assertNull(SafTargets.classify(ext, "primary"))
        assertNull(SafTargets.classify("com.android.providers.downloads.documents", "raw:/storage/emulated/0/Download"))
        assertNull(SafTargets.classify("com.android.providers.media.documents", "x"))
    }

    @Test
    fun `volume id and on-phone path of a tree (H3)`() {
        val ext = SafTargets.EXTERNAL_STORAGE
        assertEquals("1234-ABCD", SafTargets.volumeId(ext, "1234-ABCD:Backups"))
        assertEquals("/storage/1234-ABCD/Backups", SafTargets.localPath(ext, "1234-ABCD:Backups"))
        assertEquals("/storage/1234-ABCD", SafTargets.localPath(ext, "1234-ABCD:"))
        assertNull(SafTargets.localPath("org.example.docs", "1234-ABCD:Backups"))
        assertNull(SafTargets.localPath(ext, "1234-ABCD:../x"))
    }

    @Test
    fun `marker parsing accepts only our format`() {
        val good = "0123456789abcdef0123456789abcdef"
        assertEquals(good, SafTargets.parseMarker(good.toByteArray()))
        assertEquals(good, SafTargets.parseMarker("$good\n".toByteArray()))
        listOf(
            "",
            "0123456789ABCDEF0123456789ABCDEF",
            good.dropLast(1),
            "${good}0",
            "../../../etc",
            "{\"id\":\"$good\"}",
            good + " ".repeat(64),
        ).forEach { assertNull(it, SafTargets.parseMarker(it.toByteArray())) }
        assertEquals(32, SafTargets.newMarkerId().length)
        assertEquals(
            SafTargets.newMarkerId().length,
            SafTargets.parseMarker(SafTargets.newMarkerId().toByteArray())!!.length,
        )
    }
}
