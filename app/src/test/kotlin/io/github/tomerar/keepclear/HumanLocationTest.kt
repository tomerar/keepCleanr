// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Owner brief H2 item 10 (PR H2c): places in plain words, never raw paths like "USB:KeepClear/Phone/IMG". */
class HumanLocationTest {
    private val phone = "הטלפון"
    private val card = "כרטיס הזיכרון"

    @Test
    fun `the drive of a checked copy is its label only`() {
        val table = listOf(
            "SanDisk: KeepClear/Pixel 8/Camera/IMG_1.jpg" to "SanDisk",
            "USB:KeepClear/Phone/IMG" to "USB",
            "USB:KeepClear/IMG_2.jpg" to "USB",
            "Computer at home: //nas/photos: KeepClear/Pixel/IMG.jpg" to "Computer at home",
            "SanDisk 3.2Gen1" to "SanDisk 3.2Gen1",
            "  Kingston  : KeepClear/a.jpg" to "Kingston",
        )
        for ((record, drive) in table) assertEquals(record, drive, HumanLocation.driveOf(record))
    }

    @Test
    fun `the details show the drive and its folders, without the file name or slashes`() {
        val table = listOf(
            "SanDisk: KeepClear/Pixel 8/Camera/IMG_1.jpg" to "SanDisk › KeepClear › Pixel 8 › Camera",
            "USB:KeepClear/Phone/IMG" to "USB › KeepClear › Phone",
            "SanDisk: IMG.jpg" to "SanDisk",
        )
        for ((record, shown) in table) {
            assertEquals(record, shown, HumanLocation.copyLocation(record))
            assertFalse(record, '/' in HumanLocation.copyLocation(record))
        }
    }

    @Test
    fun `a backup folder reads as folder names`() {
        val table = listOf(
            "KeepClear/Pixel 8" to "KeepClear › Pixel 8",
            "KeepClear/OnePlus 10T/" to "KeepClear › OnePlus 10T",
            "/KeepClear//Phone" to "KeepClear › Phone",
            "KeepClear" to "KeepClear",
        )
        for ((rel, shown) in table) assertEquals(rel, shown, HumanLocation.folder(rel))
    }

    @Test
    fun `a phone path loses its storage root and file name`() {
        val table = listOf(
            "/storage/emulated/0/KeepClear/Restored-changed/DCIM/IMG.jpg" to "KeepClear › Restored-changed › DCIM",
            "/storage/emulated/10/Pictures/a.png" to "Pictures",
            "/sdcard/Download/x.pdf" to "Download",
            "/storage/1A2B-3C4D/DCIM/Camera/IMG.jpg" to "$card › DCIM › Camera",
            "/storage/emulated/0/top.txt" to phone,
        )
        for ((path, shown) in table) {
            val text = HumanLocation.phoneFolder(path, phone, card)
            assertEquals(path, shown, text)
            assertFalse(path, "/storage" in text || '/' in text)
        }
    }
}
