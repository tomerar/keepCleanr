// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.tomerar.keepclear.backup.smb.SmbLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** M15b: the network drive's password is stored only sealed, and a broken seal means "set it up again". */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NetworkDriveStoreTest {
    private val prefs = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("network-drive-test", Context.MODE_PRIVATE)

    /** Stand-in for the key store: reverses and flips bytes, and refuses anything it didn't seal. */
    private class FakeBox : SecretBox {
        var broken = false

        override fun seal(plain: ByteArray) =
            byteArrayOf(42) + plain.reversedArray().map { (it.toInt() xor 0x5A).toByte() }

        override fun open(sealed: ByteArray): ByteArray? = if (broken || sealed.firstOrNull() != 42.toByte()) {
            null
        } else {
            sealed.drop(1).map { (it.toInt() xor 0x5A).toByte() }.reversed().toByteArray()
        }
    }

    private val box = FakeBox()
    private val store = NetworkDriveStore(prefs, box)
    private val nas = SmbLocation("nas", "photos", folder = "Phone", user = "me", password = "s3cret-סיסמה")

    @Test
    fun `round trip, and the password is never stored in plain text`() {
        store.save(nas)
        assertEquals(nas, store.load())
        assertFalse(prefs.all.values.any { it.toString().contains("s3cret") })
        assertEquals("nas", store.host())
    }

    @Test
    fun `a password that can't be opened means set it up again, never a guessed password`() {
        store.save(nas)
        box.broken = true
        assertNull(store.load())
    }

    @Test
    fun `guest access stores no password at all`() {
        store.save(nas.copy(user = "", password = ""))
        assertEquals("", store.load()!!.password)
    }
}
