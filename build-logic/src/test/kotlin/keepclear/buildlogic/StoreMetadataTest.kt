// SPDX-License-Identifier: GPL-3.0-or-later
package keepclear.buildlogic

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class StoreMetadataTest {
    @TempDir
    lateinit var tmp: File

    private fun write(path: String, text: String) {
        File(tmp, path).apply { parentFile.mkdirs() }.writeText(text)
    }

    private fun validTree() {
        write("app/build.gradle.kts", "android { defaultConfig { versionCode = 7 } }")
        for (locale in StoreMetadata.locales) {
            val dir = "${StoreMetadata.METADATA_DIR}/$locale"
            write("$dir/title.txt", "KeepClear\n")
            write("$dir/short_description.txt", "Short\n")
            write("$dir/full_description.txt", "Full\n")
            write("$dir/changelogs/7.txt", "First\n")
        }
    }

    @Test
    fun `a complete listing passes`() {
        validTree()
        assertEquals(emptyList<String>(), StoreMetadata.checkTree(tmp))
    }

    @Test
    fun `a missing Hebrew text or changelog for the current versionCode fails`() {
        validTree()
        File(tmp, "${StoreMetadata.METADATA_DIR}/iw-IL/short_description.txt").delete()
        write("app/build.gradle.kts", "versionCode = 8")
        val problems = StoreMetadata.checkTree(tmp)
        assertTrue(problems.any { "iw-IL/short_description.txt is missing" in it }, problems.toString())
        assertTrue(problems.any { "en-US/changelogs/8.txt is missing" in it }, problems.toString())
    }

    @Test
    fun `limits are counted in characters, not bytes`() {
        assertEquals(emptyList<String>(), StoreMetadata.checkText("s", "א".repeat(80) + "\n", 80, hebrew = true))
        assertEquals(1, StoreMetadata.checkText("s", "a".repeat(81), 80, hebrew = false).size)
    }

    @Test
    fun `jargon, boost claims and Latin Android in Hebrew fail`() {
        assertEquals(1, StoreMetadata.checkText("s", "Checked with SHA-256", 500, hebrew = false).size)
        assertEquals(1, StoreMetadata.checkText("s", "Boosts your phone", 500, hebrew = false).size)
        assertEquals(1, StoreMetadata.checkText("s", "החלון של Android", 500, hebrew = true).size)
        assertEquals(emptyList<String>(), StoreMetadata.checkText("s", "Android's own window", 500, hebrew = false))
    }

    @Test
    fun `an unexpected locale folder fails`() {
        validTree()
        write("${StoreMetadata.METADATA_DIR}/he/title.txt", "KeepClear")
        assertEquals(1, StoreMetadata.checkTree(tmp).size)
    }

    @Test
    fun `the repository's listing passes`() {
        assertEquals(emptyList<String>(), StoreMetadata.checkTree(File("..")))
    }
}
