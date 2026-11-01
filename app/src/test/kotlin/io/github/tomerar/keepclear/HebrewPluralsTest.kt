// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.test.core.app.ApplicationProvider
import io.github.tomerar.keepclear.core.i18n.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Review PR C (UX 7): count sentences agree in Hebrew (one/two/other), and "test" means only backup checks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "iw")
class HebrewPluralsTest {
    private val res = ApplicationProvider.getApplicationContext<android.content.Context>().resources

    @Test
    fun `one, two and many read correctly`() {
        assertEquals(
            "קובץ אחד כבר לא היה בטלפון ודולג.",
            res.getQuantityString(R.plurals.move_receipt_failed_missing, 1, "1"),
        )
        assertEquals(
            "שני קבצים כבר לא היו בטלפון ודולגו.",
            res.getQuantityString(R.plurals.move_receipt_failed_missing, 2, "2"),
        )
        assertEquals(
            "5 קבצים כבר לא היו בטלפון ודולגו.",
            res.getQuantityString(R.plurals.move_receipt_failed_missing, 5, "5"),
        )
        assertEquals(
            "קובץ אחד השתנה מאז הסריקה, ולכן לא נגענו בו.",
            res.getQuantityString(R.plurals.clean_skipped, 1, "1"),
        )
    }

    @Test
    fun `trial mode and the owner's rewrites`() {
        assertEquals("מצב ניסיון – שום קובץ לא יימחק או יועבר", res.getString(R.string.test_mode_banner))
        assertEquals("לבחור מה להשאיר", res.getString(R.string.home_card_review_title))
        assertEquals("להחזיר הכול", res.getString(R.string.clean_undo))
        assertEquals("להתחיל לגבות", res.getString(R.string.move_start))
        assertFalse(res.getString(R.string.test_mode_title).contains("בדיקה"))
    }
}
