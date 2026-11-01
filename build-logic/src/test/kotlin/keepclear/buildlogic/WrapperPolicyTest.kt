// SPDX-License-Identifier: GPL-3.0-or-later
package keepclear.buildlogic

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class WrapperPolicyTest {
    private val good = """
        distributionBase=GRADLE_USER_HOME
        distributionPath=wrapper/dists
        distributionUrl=https\://services.gradle.org/distributions/gradle-9.8.2-bin.zip
        distributionSha256Sum=21bd5da2de49b642904077f38c0efdc7778e92b3ed0889392dca61b97e389607
    """.trimIndent()

    @Test
    fun `a clean wrapper file passes`() {
        assertEquals(emptyList<String>(), WrapperPolicy.check(good))
    }

    @Test
    fun `an appended HTML page, a missing checksum or another host fail`() {
        assertTrue(WrapperPolicy.check("$good\n<head><title>301 Moved Permanently</title></head>\r\n").isNotEmpty())
        assertTrue(WrapperPolicy.check(good.lines().dropLast(1).joinToString("\n")).isNotEmpty())
        assertTrue(WrapperPolicy.check(good.replace("services.gradle.org", "example.com")).isNotEmpty())
    }

    @Test
    fun `the repository's wrapper file passes`() {
        val file = File("../gradle/wrapper/gradle-wrapper.properties")
        assertEquals(emptyList<String>(), WrapperPolicy.check(file.readText()))
    }
}
