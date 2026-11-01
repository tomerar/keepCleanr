// SPDX-License-Identifier: GPL-3.0-or-later
package keepclear.buildlogic

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * P-2 / review PR C: no technical terms on the default path. Strings whose key starts with an allowed prefix
 * (`details_`, `advanced_`, `debug_`) may use them. Hebrew strings spell Android as "אנדרואיד".
 */
object CopyLint {
    private val jargon = listOf(
        Regex("""SHA-?256""", RegexOption.IGNORE_CASE),
        Regex("""sha256sum"""),
        Regex("""\bmanifest\b""", RegexOption.IGNORE_CASE),
        Regex("""\.keepclear-target"""),
        Regex("""\bSAF\b"""),
        Regex("""MediaStore"""),
        Regex("""cache partition""", RegexOption.IGNORE_CASE),
        Regex("""מניפסט"""),
    )
    private val allowedPrefixes = listOf("details_", "advanced_", "debug_")

    fun check(location: String, xml: String, hebrew: Boolean): List<String> {
        if (xml.isBlank()) return emptyList()
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
        val nodes = doc.documentElement.childNodes
        val problems = mutableListOf<String>()
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as? Element ?: continue
            if (e.tagName != "string" && e.tagName != "plurals") continue
            val key = e.getAttribute("name")
            if (allowedPrefixes.any { key.startsWith(it) }) continue
            val text = e.textContent
            jargon.firstOrNull {
                it.containsMatchIn(text)
            }?.let { problems += "$location: $key uses \"${it.pattern}\"" }
            if (hebrew && "Android" in text) problems += "$location: $key: write אנדרואיד in Hebrew"
        }
        return problems
    }

    fun checkTree(root: File): List<String> = root.walkTopDown()
        .onEnter { it.name != "build" && it.name != ".git" && it.name != ".gradle" && it.name != "build-logic" }
        .filter { it.isFile && it.extension == "xml" && it.parentFile.name in setOf("values", "values-iw") }
        .filter { it.parentFile.parentFile.name == "res" }
        .flatMap { check(it.path, it.readText(), hebrew = it.parentFile.name == "values-iw") }
        .toList()
}
