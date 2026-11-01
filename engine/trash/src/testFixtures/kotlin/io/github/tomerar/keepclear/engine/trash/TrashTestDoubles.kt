// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.trash

import java.security.MessageDigest

class InMemoryTrashIndex : TrashIndex {
    val entries = linkedMapOf<String, TrashEntry>()

    override suspend fun put(entry: TrashEntry) {
        entries[entry.id] = entry
    }

    override suspend fun get(id: String) = entries[id]

    override suspend fun byTrashedPath(path: String) = entries.values.firstOrNull { it.trashedPath == path }

    override suspend fun all() = entries.values.toList()

    override suspend fun remove(id: String) {
        entries.remove(id)
    }
}

/** HMAC stand-in: SHA-256 over key + content (tests only). */
class FakeSigner(private val key: String = "test-key") : SidecarSigner {
    override fun sign(canonical: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(key.toByteArray() + canonical)
            .joinToString("") { "%02x".format(it) }

    override fun verify(canonical: ByteArray, mac: String): Boolean = sign(canonical) == mac
}

class FakeClock(
    var wall: Long = 1_800_000_000_000L,
    var elapsed: Long = 1_000_000L,
    var boot: String = "boot-1",
    var uptime: Long = 1_000_000L,
) : TrashClock {
    var mark: ClockMark? = null

    override fun wallMillis() = wall

    override fun elapsedMillis() = elapsed

    override fun bootId() = boot

    override fun uptimeMillis() = uptime

    override fun lastMark() = mark

    override fun saveMark(mark: ClockMark) {
        this.mark = mark
    }

    /** Real time passes: every clock moves. */
    fun advance(millis: Long) {
        wall += millis
        elapsed += millis
        uptime += millis
    }

    /** Reboot after [offMillis] powered off: wall moves, uptime keeps its total, elapsed restarts. */
    fun reboot(offMillis: Long = 0) {
        wall += offMillis
        elapsed = 0
        boot += "+"
    }
}
