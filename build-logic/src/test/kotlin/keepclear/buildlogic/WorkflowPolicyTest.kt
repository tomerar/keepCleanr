// SPDX-License-Identifier: GPL-3.0-or-later
package keepclear.buildlogic

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/** Review PR B: no workflow leaves the GitHub token in the checkout's git config. */
class WorkflowPolicyTest {
    @Test
    fun `every checkout step sets persist-credentials false`() {
        val missing = File("../.github/workflows").listFiles { f -> f.extension == "yml" }.orEmpty().flatMap { f ->
            val lines = f.readLines()
            lines.indices.filter { lines[it].contains("uses: actions/checkout@") }.filter { i ->
                lines.drop(i + 1).takeWhile { !it.trimStart().startsWith("- ") }
                    .none { it.trim() == "persist-credentials: false" }
            }.map { "${f.name}:${it + 1}" }
        }
        assertEquals(emptyList<String>(), missing)
    }
}
