// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.tomerar.keepclear.core.database.KeepClearDatabase
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.core.storage.StorageGateway
import io.github.tomerar.keepclear.engine.trash.FakeSigner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * KeepClear's real dependency graph for ViewModel tests on Robolectric: an in-memory database, a stand-in signing
 * key (Robolectric has no key store), [root] as the phone's only volume, and every file read recorded.
 */
class TestApp(val root: File) {
    /** Reads (buffers) of user files that happened on the main thread (E1: must stay 0). */
    val mainThreadReads = AtomicInteger()

    /** Every file opened for reading. */
    val opens = AtomicInteger()

    /** Every file checked (stat). */
    val stats = AtomicInteger()

    init {
        org.robolectric.shadows.ShadowLog.stream = System.out
    }

    val app: KeepClearApplication = KeepClearApplication().apply {
        val attach = android.content.ContextWrapper::class.java
            .getDeclaredMethod("attachBaseContext", android.content.Context::class.java)
        attach.isAccessible = true
        attach.invoke(this, ApplicationProvider.getApplicationContext<android.app.Application>())
        databaseFactory = { Room.inMemoryDatabaseBuilder(it, KeepClearDatabase::class.java).build() }
        sidecarSigner = { FakeSigner() }
        volumeRootsOverride = listOf(root.absolutePath)
        secretBox = { NextcloudStoreTest.FakeBox() }
        gatewayWrapper = { RecordingGateway(it) }
        moveController.startTaskService = { _, _ -> }
    }

    private inner class RecordingGateway(private val d: StorageGateway) : StorageGateway by d {
        override suspend fun stat(path: KPath) = d.stat(path).also { stats.incrementAndGet() }

        override suspend fun openRead(path: KPath): InputStream {
            opens.incrementAndGet()
            val input = d.openRead(path)
            return object : java.io.FilterInputStream(input) {
                private fun check() {
                    if (Looper.myLooper() == Looper.getMainLooper()) mainThreadReads.incrementAndGet()
                }

                override fun read(): Int = check().let { super.read() }

                override fun read(b: ByteArray, off: Int, len: Int): Int = check().let { super.read(b, off, len) }
            }
        }
    }

    private val store = ViewModelStore()

    /** Never Android's shared factory: it keeps the first test's application for the whole test run. */
    private val factory = object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            modelClass.getConstructor(android.app.Application::class.java).newInstance(app)
    }

    fun <T : ViewModel> viewModel(type: Class<T>, owner: ViewModelStore = store): T = ViewModelProvider(
        owner,
        factory,
    )[type]

    private val stores = mutableListOf(store)

    /** A separate screen's ViewModel store (cleared at [close] if the test didn't). */
    fun newStore(): ViewModelStore = ViewModelStore().also { stores += it }

    /** Stops everything this app started before its database closes, so nothing leaks into the next test. */
    fun close() {
        stores.forEach { it.clear() }
        app.moveController.scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        app.moveController.setRunning(null)
        settle(300)
        app.database.close()
    }

    fun file(rel: String, bytes: ByteArray = ByteArray(1000) { it.toByte() }): File =
        File(root, rel).apply {
            parentFile!!.mkdirs()
            writeBytes(bytes)
        }

    companion object {
        /** Lets [millis] of real time pass while running the main looper (work on other threads finishes). */
        fun settle(millis: Long = 1_000) {
            val end = System.currentTimeMillis() + millis
            while (System.currentTimeMillis() < end) {
                shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(5))
                Thread.sleep(5)
            }
        }

        /** Runs the main looper (where viewModelScope runs) until [done], or fails after [timeoutMillis]. */
        fun awaitMain(what: String, timeoutMillis: Long = 30_000, done: () -> Boolean) {
            val end = System.currentTimeMillis() + timeoutMillis
            while (true) {
                // The main looper's clock moves with real time, so delays on the main thread (throttles) pass.
                shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(3))
                if (done()) return
                if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for: $what")
                Thread.sleep(3)
            }
        }
    }
}
