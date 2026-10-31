// SPDX-License-Identifier: GPL-3.0-or-later
package keepclear.buildlogic

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class StringParityTest {
    private fun res(dir: File, folder: String, body: String) {
        File(dir, "src/main/res/$folder").apply { mkdirs() }.resolve("strings.xml")
            .writeText("<resources>$body</resources>")
    }

    @Test
    fun `matching keys pass and untranslatable keys are ignored`(
        @TempDir root: File,
    ) {
        res(
            root,
            "values",
            """<string name="a">A</string><string name="brand" translatable="false">KeepClear</string>
            <plurals name="files"><item quantity="one">1</item></plurals>""",
        )
        res(
            root,
            "values-iw",
            """<string name="a">א</string><plurals name="files"><item quantity="one">1</item></plurals>""",
        )
        assertEquals(emptyList<String>(), StringParity.checkTree(root))
    }

    @Test
    fun `missing Hebrew string fails`(
        @TempDir root: File,
    ) {
        res(root, "values", """<string name="a">A</string><string name="b">B</string>""")
        res(root, "values-iw", """<string name="a">א</string>""")
        val violations = StringParity.checkTree(root)
        assertTrue(violations.single().contains("missing in values-iw: string/b"))
    }

    @Test
    fun `Hebrew-only key fails`(
        @TempDir root: File,
    ) {
        res(root, "values", """<string name="a">A</string>""")
        res(root, "values-iw", """<string name="a">א</string><string name="x">ש</string>""")
        assertTrue(StringParity.checkTree(root).single().contains("only in values-iw"))
    }
}
