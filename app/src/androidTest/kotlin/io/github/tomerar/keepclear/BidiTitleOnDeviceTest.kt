// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.content.ContentValues
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tomerar.keepclear.core.common.Bidi
import io.github.tomerar.keepclear.core.common.ByteSize
import io.github.tomerar.keepclear.core.designsystem.KeepClearTheme
import io.github.tomerar.keepclear.core.i18n.R
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * Design QA item 8, on the CI emulators (API 30 and 36): the Hebrew ConfirmSheet title as Android itself lays it out.
 * The Robolectric API 30 render showed „לנקות 7.9GB · 4,983 קבצים?” as "?4,983קבצים" (number and word swapped);
 * `BidiLayoutTest` reproduces that with Robolectric's API 30 text stack (an FSI or RLI isolate that starts with digits,
 * after left-to-right text) and shows API 36 right. This test checks it on a real Android 11 and 16.
 *
 * Each line is drawn on the device, its picture is saved to `Download/KeepClear-bidi/` (CI pulls it with adb and
 * uploads it as the artifact `bidi-screenshots-api<level>`), and the character positions of the text layout are
 * checked: in a right-to-left line, the number of "4,983 קבצים" is to the right of the word and the question mark is
 * at the far left. Lines the app shows now are asserted; the old F3 title (count isolated with FSI) is only recorded,
 * so the artifact shows whether the old form was wrong on this Android version.
 */
@RunWith(AndroidJUnit4::class)
class BidiTitleOnDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val hebrew: Context = context.createConfigurationContext(
        Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag("iw")) },
    )
    private val locale = Locale.forLanguageTag("iw")
    private val size = ByteSize.format(7_900_000_000, locale)
    private val count = hebrew.resources.getQuantityString(
        R.plurals.file_count,
        4_983,
        java.text.NumberFormat.getIntegerInstance(locale).format(4_983),
    )

    /** The lines to draw: tag → text. */
    private val lines = linkedMapOf(
        // The F3 title exactly as it was: both parts in FSI isolates (recorded, not asserted).
        "f3_title_fsi" to hebrew.getString(
            R.string.clean_confirm_title,
            hebrew.getString(R.string.total_value, "${Bidi.FSI}$size${Bidi.PDI}", "${Bidi.FSI}$count${Bidi.PDI}"),
        ),
        // The same sentence with today's Bidi.isolate (a Hebrew count that starts with digits gets RLM marks).
        "f3_title_now" to hebrew.getString(
            R.string.clean_confirm_title,
            hebrew.getString(R.string.total_value, Bidi.isolate(size), Bidi.isolate(count)),
        ),
        // Design QA item 5: the title the ConfirmSheet shows now, only the question.
        "title_now" to hebrew.getString(R.string.clean_confirm_title, Bidi.isolate(size)),
        // The count row of the sheet.
        "count_row" to Bidi.isolate(count),
    )

    private val layouts = mutableMapOf<String, TextLayoutResult>()

    @Test
    fun hebrewConfirmTitleKeepsTheNumberBeforeTheWord() {
        compose.setContent {
            KeepClearTheme(darkTheme = false, reducedMotion = true) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Column(Modifier.width(360.dp).background(MaterialTheme.colorScheme.surface)) {
                        lines.forEach { (tag, text) ->
                            Text(
                                text,
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.fillMaxWidth().padding(8.dp).testTag(tag),
                                onTextLayout = { layouts[tag] = it },
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val report = StringBuilder("API ${Build.VERSION.SDK_INT}\n")
        val verdicts = lines.keys.associateWith { tag ->
            val picture = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
            save("${tag}_api${Build.VERSION.SDK_INT}.png", picture)
            val v = order(tag)
            report.append("$tag: ${lines.getValue(tag).toCodePoints()} -> $v\n")
            v
        }
        saveText("report_api${Build.VERSION.SDK_INT}.txt", report.toString())
        for (tag in listOf("f3_title_now", "title_now", "count_row")) {
            assertTrue("$tag: ${verdicts[tag]}", verdicts.getValue(tag).ok)
        }
    }

    private data class Verdict(val numberRightOfWord: Boolean?, val questionAtLeft: Boolean?) {
        val ok: Boolean get() = numberRightOfWord != false && questionAtLeft != false
    }

    /** Where the count's number and word, and the question mark, were drawn (null when the line has no such part). */
    private fun order(tag: String): Verdict {
        val text = lines.getValue(tag)
        val layout = layouts.getValue(tag)

        fun x(index: Int) = layout.getBoundingBox(index).center.x
        val digit = text.indexOf('4').takeIf { it >= 0 && text.contains("קבצים") }
        val word = text.indexOf("קבצים").takeIf { it >= 0 }
        val number = digit?.let { d -> word?.let { w -> x(d) > x(w) } }
        val visible = text.indices.filter { i ->
            !Character.isWhitespace(text[i]) && Character.getType(text[i]) != Character.FORMAT.toInt()
        }
        val question = text.indexOf('?').takeIf { it >= 0 }?.let { q -> visible.all { it == q || x(q) <= x(it) } }
        return Verdict(number, question)
    }

    private fun String.toCodePoints() = map { c ->
        if (Character.getType(c) == Character.FORMAT.toInt()) "<U+%04X>".format(c.code) else c.toString()
    }.joinToString("")

    private fun save(name: String, bitmap: Bitmap) =
        write(name, "image/png") { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

    private fun saveText(name: String, text: String) = write(name, "text/plain") { it.write(text.toByteArray()) }

    /** Into the shared Downloads folder, which outlives the test app's uninstall, so CI can pull it with adb. */
    private fun write(name: String, mime: String, body: (java.io.OutputStream) -> Unit) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/KeepClear-bidi")
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
        resolver.openOutputStream(uri)?.use(body)
    }
}
