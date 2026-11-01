// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.content.ContentResolver
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.content.edit
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.tomerar.keepclear.engine.trash.ClockMark
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

/**
 * Clocks for the trash (E-DEL-10, M2). [uptimeMillis] adds up `elapsedRealtime` (which includes sleep) across
 * reboots and is stored, so it keeps counting real time the user can't set. Time lost between the last save and
 * a power-off only delays purges.
 */
class SystemTrashClock(
    private val resolver: ContentResolver,
    context: Context,
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
    private val boot: (() -> String)? = null,
) : TrashClock {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun wallMillis() = System.currentTimeMillis()

    override fun elapsedMillis() = elapsed()

    override fun bootId(): String =
        boot?.invoke() ?: Settings.Global.getInt(resolver, Settings.Global.BOOT_COUNT, -1).toString()

    @Synchronized
    override fun uptimeMillis(): Long {
        val now = elapsed()
        val bootNow = bootId()
        var base = prefs.getLong(K_BASE, 0)
        val lastBoot = prefs.getString(K_BOOT, null)
        if (lastBoot != null && lastBoot != bootNow) base += prefs.getLong(K_LAST_ELAPSED, 0) // a reboot happened
        // Same boot but elapsed went backwards can't happen; if it does, treat it as a reboot (never lose time).
        if (lastBoot == bootNow && now < prefs.getLong(K_LAST_ELAPSED, 0)) base += prefs.getLong(K_LAST_ELAPSED, 0)
        prefs.edit(commit = true) {
            putLong(K_BASE, base)
            putString(K_BOOT, bootNow)
            putLong(K_LAST_ELAPSED, now)
        }
        return base + now
    }

    override fun lastMark(): ClockMark? {
        val boot = prefs.getString(K_MARK_BOOT, null) ?: return null
        return ClockMark(prefs.getLong(K_MARK_WALL, 0), prefs.getLong(K_MARK_UP, 0), boot)
    }

    override fun saveMark(mark: ClockMark) = prefs.edit(commit = true) {
        putLong(K_MARK_WALL, mark.wallMillis)
        putLong(K_MARK_UP, mark.uptimeMillis)
        putString(K_MARK_BOOT, mark.bootId)
    }

    companion object {
        const val PREFS = "trash_clock"
        private const val K_BASE = "uptimeBase"
        private const val K_BOOT = "uptimeBoot"
        private const val K_LAST_ELAPSED = "uptimeLastElapsed"
        private const val K_MARK_WALL = "markWall"
        private const val K_MARK_UP = "markUptime"
        private const val K_MARK_BOOT = "markBoot"
    }
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
