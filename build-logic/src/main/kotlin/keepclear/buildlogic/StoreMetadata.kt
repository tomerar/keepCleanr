// SPDX-License-Identifier: GPL-3.0-or-later
package keepclear.buildlogic

import java.io.File

/**
 * M18: the F-Droid / IzzyOnDroid listing in the fastlane layout (`fastlane/metadata/android/<locale>/`).
 * Both English and Hebrew must exist with every text, within the store limits, with a changelog for the app's
 * current versionCode, no technical terms (P-2, same list as [CopyLint]) and Android spelled in Hebrew.
 */
object StoreMetadata {
    const val METADATA_DIR = "fastlane/metadata/android"
    val locales = listOf("en-US", "iw-IL")

    /** Limits of F-Droid (title 50, short 80, full 4000, changelog 500) and Google Play (title 30), the stricter. */
    val limits = mapOf(
        "title.txt" to 30,
        "short_description.txt" to 80,
        "full_description.txt" to 4000,
    )
    const val CHANGELOG_LIMIT = 500

    private val jargon = listOf(
        Regex("""SHA-?256""", RegexOption.IGNORE_CASE),
        Regex("""\bmanifest\b""", RegexOption.IGNORE_CASE),
        Regex("""\bSAF\b"""),
        Regex("""MediaStore"""),
        Regex("""cache partition""", RegexOption.IGNORE_CASE),
        Regex("""\bboost""", RegexOption.IGNORE_CASE),
        Regex("""מניפסט"""),
    )

    private val versionCodeRegex = Regex("""versionCode\s*=\s*(\d+)""")

    fun versionCode(appBuildFile: File): Int? =
        versionCodeRegex.find(appBuildFile.readText())?.groupValues?.get(1)?.toInt()

    /** Problems with a single text; [name] is used in messages and to pick the limit. */
    fun checkText(name: String, text: String, limit: Int, hebrew: Boolean): List<String> {
        val problems = mutableListOf<String>()
        val body = text.trimEnd('\n', '\r')
        if (body.isBlank()) problems += "$name is empty"
        val length = body.codePointCount(0, body.length)
        if (length > limit) problems += "$name has $length characters (limit $limit)"
        jargon.firstOrNull { it.containsMatchIn(body) }?.let { problems += "$name uses \"${it.pattern}\"" }
        if (hebrew && "Android" in body) problems += "$name: write אנדרואיד in Hebrew"
        return problems
    }

    fun checkTree(root: File): List<String> {
        val base = File(root, METADATA_DIR)
        val versionCode = versionCode(File(root, "app/build.gradle.kts"))
            ?: return listOf("app/build.gradle.kts: no versionCode found")
        val problems = mutableListOf<String>()
        for (locale in locales) {
            val dir = File(base, locale)
            val hebrew = locale.startsWith("iw") || locale.startsWith("he")
            val texts = limits.map { (file, limit) -> Triple(File(dir, file), limit, file) } +
                Triple(File(dir, "changelogs/$versionCode.txt"), CHANGELOG_LIMIT, "changelogs/$versionCode.txt")
            for ((file, limit, label) in texts) {
                if (!file.isFile) {
                    problems += "$METADATA_DIR/$locale/$label is missing"
                    continue
                }
                problems += checkText("$METADATA_DIR/$locale/$label", file.readText(), limit, hebrew)
            }
        }
        base.listFiles { f -> f.isDirectory && f.name !in locales }?.forEach {
            problems += "$METADATA_DIR/${it.name}: unexpected locale (expected ${locales.joinToString()})"
        }
        return problems
    }
}
