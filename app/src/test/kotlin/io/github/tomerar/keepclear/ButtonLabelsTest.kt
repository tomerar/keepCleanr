// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Design QA item 4: no parentheses in button labels, in English or Hebrew ("Free up 31.0 GB from the phone", the count
 * goes on a line under the button). Finds every string the app's code passes as a button's label (KcButton,
 * SafeConfirmButton, Material buttons, and the `button =`, `actionText =`, `undoText =` and `dismissText =` slots of
 * the design-system components) and checks its text in both languages.
 */
class ButtonLabelsTest {
    private val code = File("src/main/kotlin")
    private val res = File("../core/i18n/src/main/res")

    private val direct = Regex(
        """\b(?:KcButton|SafeConfirmButton|TextButton|OutlinedButton|FilledTonalButton|Button)\(\s*""" +
            """(?:text\s*=\s*)?(?:stringResource|pluralStringResource)\(\s*R\.(?:string|plurals)\.(\w+)""",
    )
    private val slot = Regex(
        """\b(?:button|actionText|undoText|dismissText|confirmText|confirm)\s*=\s*""" +
            """(?:stringResource|pluralStringResource)\(\s*R\.(?:string|plurals)\.(\w+)""",
    )

    private fun buttonKeys(): Set<String> = code.walkTopDown().filter { it.extension == "kt" }.flatMap { file ->
        val text = file.readText()
        (direct.findAll(text) + slot.findAll(text)).map { it.groupValues[1] }
    }.toSet()

    private fun strings(dir: String): Map<String, String> {
        val xml = File(res, "$dir/strings.xml").readText()
        val single = Regex("""<string name="(\w+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
        val plural = Regex("""<plurals name="(\w+)"[^>]*>(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL)
        return (single.findAll(xml) + plural.findAll(xml)).associate { it.groupValues[1] to it.groupValues[2] }
    }

    /** Labels the code picks indirectly (e.g. `CleanSheetText.confirmLabel`), which the scan can't see. */
    private val indirect = setOf(
        "clean_confirm_yes",
        "clean_confirm_yes_now",
        // PR H2c (C156): the receipt's free button picks one of two by the keep-in-trash setting.
        "move_free_button",
        "move_free_button_trash",
        "move_free_confirm_yes",
    )

    @Test
    fun `no button label has parentheses, in English or Hebrew`() {
        val keys = buttonKeys() + indirect
        // The scan must see the labels this rule was written for, or it would pass by finding nothing.
        val known = setOf(
            "move_free_button",
            "trash_release_all",
            "trash_release_now",
            "trash_empty_now",
            "trash_confirm_delete",
            "move_nextcloud_option",
            "restore_from_nextcloud",
            "move_network_saved_option",
        )
        assertTrue("not found: ${known - keys}", keys.containsAll(known))
        val bad = listOf("values", "values-iw").flatMap { dir ->
            strings(dir).filterKeys { it in keys }.filterValues { '(' in it || ')' in it }.map { "$dir/${it.key}" }
        }
        assertEquals(emptyList<String>(), bad)
    }
}
