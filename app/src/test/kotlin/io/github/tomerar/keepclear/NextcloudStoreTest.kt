// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.tomerar.keepclear.backup.webdav.NextcloudAccount
import io.github.tomerar.keepclear.backup.webdav.NextcloudServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** M17b: the Nextcloud's app password is stored only sealed, and a broken seal means "sign in again". */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NextcloudStoreTest {
    private val prefs = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("nextcloud-test", Context.MODE_PRIVATE)

    /** Stand-in for the key store (Robolectric has none): reverses and flips bytes, refuses anything it didn't seal. */
    internal class FakeBox : SecretBox {
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
    private val store = NextcloudStore(prefs, box)
    private val secret = "Secret1AppPassword2" + "x".repeat(20)
    private val account =
        NextcloudAccount(NextcloudServer.parse("cloud.example.com/nc"), "dana@example.com", "dana", secret)

    @Test
    fun `round trip, and the app password is never stored, shown or referenced in plain text`() {
        store.save(account)
        assertEquals(account, store.load())
        assertFalse(prefs.all.values.any { it.toString().contains("Secret1") })
        assertEquals("cloud.example.com", store.host())
        assertFalse(account.toString().contains("Secret1"))
        assertFalse(store.load().toString().contains("Secret1"))
        // The job's reference names the place only: no login name, no password (E-MOVE-2).
        val ref = NextcloudStore.ref(account)
        assertEquals("davs://cloud.example.com/nc/remote.php/dav/files/dana", ref)
        assertTrue(OpenedTarget.isNextcloud(ref))
        assertFalse(OpenedTarget.isSmb(ref))
        assertTrue(OpenedTarget.isNetwork(ref))
    }

    @Test
    fun `an app password that can't be opened means sign in again, never a guessed one`() {
        store.save(account)
        box.broken = true
        assertNull(store.load())
    }

    @Test
    fun `Settings sees the host as soon as it is saved or forgotten`() {
        assertNull(store.hostState.value)
        store.save(account)
        assertEquals("cloud.example.com", store.hostState.value)
        store.clear()
        assertNull(store.hostState.value)
        assertNull(store.load())
        assertTrue(prefs.all.isEmpty())
    }
}
