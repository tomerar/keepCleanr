// SPDX-License-Identifier: GPL-3.0-or-later
package keepclear.buildlogic

/**
 * Supply chain (review PR B): `gradle-wrapper.properties` must be plain `key=value` lines, download only from
 * `services.gradle.org` over HTTPS, and pin `distributionSha256Sum`. Catches junk such as an HTML error page
 * appended by a proxy.
 */
object WrapperPolicy {
    private val line = Regex("^[A-Za-z][A-Za-z0-9]*=.*$")
    private val sha = Regex("^[0-9a-f]{64}$")

    fun check(text: String): List<String> {
        val problems = mutableListOf<String>()
        val props = mutableMapOf<String, String>()
        text.lines().forEachIndexed { i, raw ->
            val l = raw.trimEnd()
            if (l.isEmpty() || l.startsWith("#")) return@forEachIndexed
            if (!line.matches(l) || '\r' in raw) {
                problems += "line ${i + 1} is not a key=value property: ${l.take(60)}"
                return@forEachIndexed
            }
            props[l.substringBefore('=')] = l.substringAfter('=')
        }
        val url = props["distributionUrl"]?.replace("\\:", ":")
        if (url == null || !url.startsWith("https://services.gradle.org/distributions/")) {
            problems += "distributionUrl must be https://services.gradle.org/distributions/…"
        }
        if (props["distributionSha256Sum"]?.let { sha.matches(it) } != true) {
            problems += "distributionSha256Sum must be a 64-character lowercase SHA-256"
        }
        return problems
    }
}
