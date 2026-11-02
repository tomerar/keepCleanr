// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** PR #45 review: app-wide tasks (E3) and their cancellation (Android's time limit, Stop). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MoveTaskTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var t: TestApp

    @After
    fun tearDown() {
        if (::t.isInitialized) t.close()
    }

    @Test
    fun `a cancelled task that is still cleaning up blocks a new one, and never clears the next task's state`() {
        t = TestApp(tmp.newFolder("phone"))
        val controller = t.app.moveController
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = controller.runTask(t.app, TaskKind.CHECK) { _ ->
            try {
                started.complete(Unit)
                awaitCancellation()
            } finally {
                // e.g. closing the network connection after Stop.
                withContext(NonCancellable) { release.await() }
            }
        }
        assertNotNull(first)
        runBlocking { started.await() }
        controller.cancelTask()
        // The old task is still unwinding: a retry is refused instead of starting under it.
        assertNull(controller.runTask(t.app, TaskKind.CHECK) { _ -> })
        assertEquals(TaskKind.CHECK, controller.task.value?.kind)
        release.complete(Unit)
        runBlocking { first!!.join() }
        assertNull(controller.task.value)

        // Once it has ended, a new task starts and its state stays until it ends itself.
        val running = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val second = controller.runTask(t.app, TaskKind.FREE) { _ ->
            running.complete(Unit)
            finish.await()
        }
        assertNotNull(second)
        runBlocking { running.await() }
        assertEquals(TaskKind.FREE, controller.task.value?.kind)
        finish.complete(Unit)
        runBlocking { second!!.join() }
        assertNull(controller.task.value)
    }

    @Test
    fun `a task stopped by the time limit reports that it was cancelled`() {
        t = TestApp(tmp.newFolder("phone"))
        val controller = t.app.moveController
        val started = CompletableDeferred<Unit>()
        var cancelled = 0
        val job = controller.runTask(t.app, TaskKind.RESTORE, onCancelled = { cancelled++ }) { _ ->
            started.complete(Unit)
            awaitCancellation()
        }
        runBlocking { started.await() }
        // MoveService.onTimeout
        controller.cancelTask()
        runBlocking { job!!.join() }
        assertEquals(1, cancelled)
        assertNull(controller.task.value)
        // A task that ends normally doesn't.
        runBlocking { controller.runTask(t.app, TaskKind.RESTORE, onCancelled = { cancelled++ }) { _ -> }!!.join() }
        assertEquals(1, cancelled)
        assertTrue(controller.results.value.restore == null)
    }
}
