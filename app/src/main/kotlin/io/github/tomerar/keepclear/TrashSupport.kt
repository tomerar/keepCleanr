// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.content.ContentResolver
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.tomerar.keepclear.engine.trash.SidecarSigner
import io.github.tomerar.keepclear.engine.trash.TrashClock
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * Signs trash sidecars with an HMAC key that never leaves the Android Keystore (SEC-1). The key is
 * deleted on uninstall, so sidecars found after a reinstall are treated as untrusted, by design.
 */
class KeystoreSidecarSigner : SidecarSigner {
    private val key: SecretKey by lazy {
        val store = KeyStore.getInstance(STORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey) ?: newKey()
    }

    private fun newKey(): SecretKey {
        val spec = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
        ).build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, STORE).apply {
            init(spec)
        }.generateKey()
    }

    override fun sign(canonical: ByteArray): String =
        Mac.getInstance("HmacSHA256").apply { init(key) }.doFinal(canonical).joinToString("") { "%02x".format(it) }

    override fun verify(canonical: ByteArray, mac: String): Boolean =
        MessageDigest.isEqual(sign(canonical).toByteArray(), mac.toByteArray())

    private companion object {
        const val STORE = "AndroidKeyStore"
        const val ALIAS = "keepclear-trash-sidecar"
    }
}

class SystemTrashClock(private val resolver: ContentResolver) : TrashClock {
    override fun wallMillis() = System.currentTimeMillis()

    override fun elapsedMillis() = SystemClock.elapsedRealtime()

    override fun bootId(): String = Settings.Global.getInt(resolver, Settings.Global.BOOT_COUNT, -1).toString()
}

/**
 * Daily purge of expired trash (TRASH-2). Only deletes what the user already confirmed into the trash
 * 30+ days ago; never touches anything else (SAFETY-1/2).
 */
class TrashPurgeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as KeepClearApplication
        app.trashManager.recover()
        // Test mode: nothing is permanently deleted, not even expired trash.
        if (!app.testMode.isOn) app.trashManager.purgeExpired()
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "trash-purge",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<TrashPurgeWorker>(1, TimeUnit.DAYS).build(),
            )
        }
    }
}
