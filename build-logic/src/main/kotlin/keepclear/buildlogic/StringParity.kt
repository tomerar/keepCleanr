// SPDX-License-Identifier: GPL-3.0-or-later
package keepclear.buildlogic

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * I18N-1: every translatable string/plurals/string-array key in `values/` must exist in
 * `values-iw/` (Hebrew) and vice versa, for every resource directory.
 */
object StringParity {
    private val resourceTags = setOf("string", "plurals", "string-array")

    fun keys(xml: String): Set<String> {
        if (xml.isBlank()) return emptySet()
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
        val nodes = doc.documentElement.childNodes
        return (0 until nodes.length).map { nodes.item(it) }.filterIsInstance<Element>()
            .filter { it.tagName in resourceTags && it.getAttribute("translatable") != "false" }
            .map { "${it.tagName}/${it.getAttribute("name")}" }
            .toSet()
    }

    /** Compares two key sets; [location] is used in messages. */
    fun compare(location: String, english: Set<String>, hebrew: Set<String>): List<String> =
        (english - hebrew).sorted().map { "$location: missing in values-iw: $it" } +
            (hebrew - english).sorted().map { "$location: only in values-iw (no English source): $it" }

    /** Checks every `res` directory that has a `values` folder below [root]. */
    fun checkTree(root: File): List<String> {
        val resDirs = root.walkTopDown()
            .onEnter { it.name != "build" && it.name != ".git" && it.name != ".gradle" && it.name != "build-logic" }
            .filter { it.isDirectory && it.name == "res" && File(it, "values").isDirectory }
            .toList()
        return resDirs.flatMap { res ->
            fun collect(dir: File) = dir.listFiles { f -> f.extension == "xml" }.orEmpty()
                .flatMap { keys(it.readText()) }.toSet()
            val english = collect(File(res, "values"))
            val hebrew = collect(File(res, "values-iw"))
            if (english.isEmpty() && hebrew.isEmpty()) {
                emptyList()
            } else {
                compare(res.relativeTo(root).path, english, hebrew)
            }
        }
    }
}
