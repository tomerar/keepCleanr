// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.classify

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/** CLS-2: every rule has group/why/if-deleted texts in English and Hebrew. */
class ExplanationStringsTest {
    private fun keys(lang: String): Set<String> {
        val folder = if (lang == "en") "values" else "values-$lang"
        val text = File("../../core/i18n/src/main/res/$folder/explanations.xml").readText()
        return Regex("""<string name="([^"]+)">""").findAll(text).map { it.groupValues[1] }.toSet()
    }

    @Test
    fun `all rule explanations exist in en and he`() {
        val required = Classifier.explainedRuleIds.flatMap { listOf("group_$it", "why_$it", "if_deleted_$it") }.toSet()
        for (lang in listOf("en", "iw")) (required - keys(lang)) shouldBe emptySet()
    }

    @Test
    fun `rule ids are unique`() {
        Classifier.explainedRuleIds.size shouldBe Classifier.explainedRuleIds.toSet().size
    }
}
