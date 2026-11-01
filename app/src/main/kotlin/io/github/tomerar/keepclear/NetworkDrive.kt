// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import io.github.tomerar.keepclear.backup.smb.SmbLocation
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts small secrets at rest. */
interface SecretBox {
    fun seal(plain: ByteArray): ByteArray

    /** Null if the data was tampered with or the key is gone (e.g. after a backup-restore to another phone). */
    fun open(sealed: ByteArray): ByteArray?
}

/** AES-256-GCM with a key that never leaves Android's key store (M15b, PRIVACY.md "Network"). */
class KeystoreSecretBox(private val alias: String = "keepclear-network-drive") : SecretBox {
    private fun key(): SecretKey {
        val store = KeyStore.getInstance(STORE).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE).apply { init(spec) }.generateKey()
    }

    override fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray? = runCatching {
        require(sealed.size > IV_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, IV_BYTES))
        cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
    }.getOrNull()

    private companion object {
        const val STORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}

/**
 * M15b: the one network drive the user set up. Address, share, folder and user are plain preferences; the
 * password is stored only sealed by [SecretBox] and is never logged.
 */
class NetworkDriveStore(private val prefs: SharedPreferences, private val box: SecretBox) {
    private val _hostState = kotlinx.coroutines.flow.MutableStateFlow(prefs.getString(HOST, null))

    /** The saved drive's address, updated on save and forget (Settings shows it, PR #25 review). */
    val hostState: kotlinx.coroutines.flow.StateFlow<String?> = _hostState

    fun save(location: SmbLocation) = prefs.edit(commit = true) {
        putString(HOST, location.host)
        putInt(PORT, location.port)
        putString(SHARE, location.share)
        putString(FOLDER, location.folder)
        putString(USER, location.user)
        putString(DOMAIN, location.domain)
        val sealed = if (location.password.isEmpty()) {
            ""
        } else {
            Base64.encodeToString(
                box.seal(location.password.toByteArray(Charsets.UTF_8)),
                Base64.NO_WRAP,
            )
        }
        putString(PASSWORD, sealed)
    }.also { _hostState.value = location.host }

    /** The saved drive, or null if none (or the password can't be opened any more: set it up again). */
    fun load(): SmbLocation? {
        val host = prefs.getString(HOST, null) ?: return null
        val sealed = prefs.getString(PASSWORD, "").orEmpty()
        val password = if (sealed.isEmpty()) {
            ""
        } else {
            box.open(Base64.decode(sealed, Base64.NO_WRAP))?.toString(Charsets.UTF_8) ?: return null
        }
        return runCatching {
            SmbLocation(
                host = host,
                share = prefs.getString(SHARE, null) ?: return null,
                folder = prefs.getString(FOLDER, "").orEmpty(),
                user = prefs.getString(USER, "").orEmpty(),
                password = password,
                domain = prefs.getString(DOMAIN, "").orEmpty(),
                port = prefs.getInt(PORT, 445),
            )
        }.getOrNull()
    }

    /** The address shown in Settings ("only to …"), without loading the password. */
    fun host(): String? = prefs.getString(HOST, null)

    fun clear() = prefs.edit(commit = true) { clear() }.also { _hostState.value = null }

    private companion object {
        const val HOST = "host"
        const val PORT = "port"
        const val SHARE = "share"
        const val FOLDER = "folder"
        const val USER = "user"
        const val DOMAIN = "domain"
        const val PASSWORD = "passwordSealed"
    }
}
