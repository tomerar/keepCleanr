// SPDX-License-Identifier: GPL-3.0-or-later
package keepclear.buildlogic

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class CopyLintTest {
    @Test
    fun `technical terms are allowed only under details and advanced keys`() {
        val xml = """<resources>
            <string name="receipt">Checked with SHA-256</string>
            <string name="details_check">sha256sum -c SHA256SUMS</string>
            <plurals name="names"><item quantity="other">Listed in the manifest</item></plurals>
            <string name="ok">Every file was read again and matches</string>
        </resources>"""
        val problems = CopyLint.check("x", xml, hebrew = false)
        assertEquals(2, problems.size, problems.toString())
        assertTrue(problems.any { "receipt" in it } && problems.any { "names" in it })
    }

    @Test
    fun `Hebrew spells Android in Hebrew`() {
        val xml = """<resources><string name="a">מגבלה של Android</string>""" +
            """<string name="b">אנדרואיד</string></resources>"""
        assertEquals(1, CopyLint.check("x", xml, hebrew = true).size)
    }

    @Test
    fun `the app's strings pass`() {
        assertEquals(emptyList<String>(), CopyLint.checkTree(File("..")))
    }
}
