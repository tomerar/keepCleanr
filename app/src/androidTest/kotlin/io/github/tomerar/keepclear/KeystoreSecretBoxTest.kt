// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** M15b on a device: the network drive's password is sealed with a key in Android's key store. */
@RunWith(AndroidJUnit4::class)
class KeystoreSecretBoxTest {
    private val box = KeystoreSecretBox(alias = "keepclear-test-box")

    @Test
    fun sealsAndOpensAndRejectsTampering() {
        val secret = "s3cret-סיסמה".toByteArray()
        val sealed = box.seal(secret)
        assertFalse(String(sealed, Charsets.ISO_8859_1).contains("s3cret"))
        assertArrayEquals(secret, box.open(sealed))
        // Two seals differ (fresh IV each time).
        assertFalse(sealed.contentEquals(box.seal(secret)))
        sealed[sealed.size - 1] = (sealed.last().toInt() xor 1).toByte()
        assertNull(box.open(sealed))
    }
}
