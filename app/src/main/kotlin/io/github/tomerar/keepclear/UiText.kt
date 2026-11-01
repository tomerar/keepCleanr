// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import io.github.tomerar.keepclear.core.common.Bidi
import io.github.tomerar.keepclear.core.common.ByteSize
import java.text.NumberFormat

/**
 * A whole sentence that agrees with [count] (Hebrew one/two/other, review PR C). The count is passed formatted
 * for the locale and bidi-isolated as `%1$s`; [more] follow as `%2$s`….
 */
@Composable
fun countSentence(id: Int, count: Int, vararg more: Any): String {
    val locale = LocalConfiguration.current.locales[0]
    return pluralStringResource(id, count, Bidi.isolate(NumberFormat.getIntegerInstance(locale).format(count)), *more)
}

/** A size such as "7.9 GB", isolated so a line that starts with it still reads in the UI's direction. */
@Composable
fun sizeText(bytes: Long): String = Bidi.isolate(ByteSize.format(bytes, LocalConfiguration.current.locales[0]))
