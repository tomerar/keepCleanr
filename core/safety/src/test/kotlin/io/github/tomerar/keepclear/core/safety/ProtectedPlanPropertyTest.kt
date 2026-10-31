// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.safety

import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.core.storage.StorageException
import io.github.tomerar.keepclear.testing.fixtures.OwnerDevice
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * tasks.md M2 DoD: 10,000 random plans over the owner-shaped tree never let a PROTECTED path reach
 * the handler (E-DEL-2), and random storage failures never crash the executor (LOW-4).
 */
@OptIn(io.kotest.common.ExperimentalKotest::class)
class ProtectedPlanPropertyTest {
    private val fixture = OwnerDevice.build(seed = 3, scale = 0.005)
    private val files = fixture.gateway.files()
    private val dirs = files.flatMap { f ->
        generateSequence(KPath.Local(f).parent) { it.parent }.map { it.absolute }.takeWhile { it.length > 1 }.toList()
    }.distinct()
    private val candidates = files + dirs
    private val protectedPaths = ProtectedPaths()

    @Test
    fun `10k random plans never hand a protected path to the handler`(): Unit = runBlocking {
        val handler = RecordingHandler()
        val executor = ActionExecutor(fixture.gateway, SafetyGuard(protectedPaths), protectedPaths, handler, SESSION)
        checkAll(PropTestConfig(iterations = 10_000, seed = 1), Arb.list(Arb.int(candidates.indices), 1..12)) { picks ->
            val ops = picks.distinct().map { fixture.gateway.op(candidates[it]) }
            executor.run(plan(*ops.toTypedArray()))
        }
        val protectedFiles = fixture.protectedPaths.toSet()
        for (op in handler.performed) {
            check(!protectedPaths.isProtected(op.displayPath)) { "handler got PROTECTED ${op.displayPath}" }
            if (op.isDirectory) {
                check(
                    protectedFiles.none {
                        it.startsWith(op.displayPath + "/")
                    },
                ) { "dir with protected inside: ${op.displayPath}" }
            }
        }
        check(handler.performed.isNotEmpty()) { "property test exercised nothing" }
    }

    @Test
    fun `random storage failures never crash the executor`(): Unit = runBlocking {
        checkAll(PropTestConfig(iterations = 500, seed = 2), Arb.long(), Arb.boolean()) { seed, failInHandler ->
            val random = java.util.Random(seed)
            val handler = ActionHandler { op, _ ->
                if (failInHandler && random.nextBoolean()) throw StorageException.NoSpace(op.ref)
            }
            val executor =
                ActionExecutor(fixture.gateway, SafetyGuard(protectedPaths), protectedPaths, handler, SESSION)
            val ops = (0 until 5).map { files[random.nextInt(files.size)] }.distinct()
                .filterNot { protectedPaths.isProtected(it) }.map { fixture.gateway.op(it) }
            if (ops.isNotEmpty()) executor.run(plan(*ops.toTypedArray()))
        }
    }
}
