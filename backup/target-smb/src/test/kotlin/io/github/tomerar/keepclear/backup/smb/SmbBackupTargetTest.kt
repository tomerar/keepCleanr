// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.backup.smb

import io.github.tomerar.keepclear.core.storage.StorageException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * M15 against a real Samba server (scripts/samba-test-server.sh; signing required, like Windows 11). CI always
 * starts one; without it these tests are skipped locally, but never in CI (see [SmbTestServerPresentTest]).
 */
class SmbBackupTargetTest {
    private val env = SmbTestEnv.location()
    private lateinit var target: SmbBackupTarget
    private val run = "run-${UUID.randomUUID()}"

    @Before
    fun setUp() {
        assumeTrue("no Samba test server", env != null)
        target = SmbBackupTarget(env!!.copy(folder = ""), id = "smb-test", label = "Test share", timeoutSeconds = 10)
        runBlocking { target.mkdirs(run) }
    }

    @After
    fun tearDown(): Unit = runBlocking { if (::target.isInitialized) target.close() }

    private inline fun <reified T : StorageException> expect(noinline block: suspend () -> Unit) {
        try {
            runBlocking { block() }
            fail("expected ${T::class.java.simpleName}")
        } catch (e: StorageException) {
            if (e !is T) throw AssertionError("expected ${T::class.java.simpleName}, got $e", e)
        }
    }

    @Test
    fun `a copy reads back byte-exact over the network`(): Unit = runBlocking {
        val bytes = ByteArray(3_000_000) { (it * 31).toByte() }
        target.create("$run/a.bin").use { it.write(bytes) }
        assertArrayEquals(bytes, target.openRead("$run/a.bin").use { it.readBytes() })
        assertEquals(bytes.size.toLong(), target.stat("$run/a.bin")!!.size)
        assertTrue(target.stat("$run/a.bin")!!.mtimeMillis > 0)
    }

    @Test
    fun `create never overwrites`(): Unit = runBlocking {
        target.create("$run/a.txt").use { it.write("one".toByteArray()) }
        expect<StorageException.AlreadyExists> { target.create("$run/a.txt") }
        // Windows rules: names differing only in case collide too.
        expect<StorageException.AlreadyExists> { target.create("$run/A.TXT") }
        assertArrayEquals("one".toByteArray(), target.openRead("$run/a.txt").use { it.readBytes() })
    }

    @Test
    fun `rename never overwrites and moves within the share`(): Unit = runBlocking {
        target.create("$run/a").use { it.write(1) }
        target.create("$run/b").use { it.write(2) }
        expect<StorageException.AlreadyExists> { target.rename("$run/a", "$run/b") }
        target.mkdirs("$run/sub/deeper")
        target.rename("$run/a", "$run/sub/deeper/c")
        assertNull(target.stat("$run/a"))
        assertEquals(1, target.openRead("$run/sub/deeper/c").use { it.read() })
    }

    @Test
    fun `list, delete files and empty folders, but not full ones`(): Unit = runBlocking {
        target.mkdirs("$run/d")
        target.create("$run/d/f").use { it.write(1) }
        assertEquals(listOf("d"), target.list(run).map { it.name })
        assertTrue(target.list(run).single().isDirectory)
        expect<StorageException.NotEmpty> { target.delete("$run/d") }
        target.delete("$run/d/f")
        target.delete("$run/d")
        assertTrue(target.list(run).isEmpty())
        expect<StorageException.NotFound> { target.delete("$run/d") }
    }

    @Test
    fun `Hebrew and emoji names round-trip`(): Unit = runBlocking {
        val name = "תמונה של אמא 🎂.jpg"
        target.create("$run/$name").use { it.write(9) }
        assertEquals(listOf(name), target.list(run).map { it.name })
    }

    @Test
    fun `reading a missing file or a folder fails with a storage error`(): Unit = runBlocking {
        expect<StorageException.NotFound> { target.openRead("$run/missing").close() }
        target.mkdirs("$run/folder")
        expect<StorageException.IsADirectory> { target.openRead("$run/folder").close() }
        assertNull(target.stat("$run/missing"))
    }

    @Test
    fun `free space is reported`(): Unit = runBlocking {
        assertNotNull(target.freeBytes())
        assertTrue(target.freeBytes()!! > 0)
    }

    @Test
    fun `a wrong password is refused, never treated as a missing drive`(): Unit = runBlocking {
        val wrong = SmbBackupTarget(env!!.copy(password = "wrong"), id = "x", label = "x", timeoutSeconds = 10)
        expect<StorageException.PermissionDenied> { wrong.stat("") }
        wrong.close()
    }

    @Test
    fun `an unreachable server means the drive is gone for now (pause, never delete)`(): Unit = runBlocking {
        val off = SmbBackupTarget(env!!.copy(port = 1), id = "x", label = "x", timeoutSeconds = 3)
        expect<StorageException.Disconnected> { off.stat("") }
        off.close()
    }
}

/** Fails in CI when the Samba server is missing, so the integration tests above can't be skipped silently there. */
class SmbTestServerPresentTest {
    @Test
    fun `CI provides the Samba test server`() {
        if (System.getenv("CI") ==
            "true"
        ) {
            assertNotNull("start scripts/samba-test-server.sh in CI", SmbTestEnv.location())
        }
    }
}

/** Pure checks, no server needed. */
class SmbLocationTest {
    @Test
    fun `only plain host names and addresses are accepted (ADR 0011 network rule)`() {
        listOf("nas", "192.168.1.20", "my-pc.local", "NAS-01").forEach { assertTrue(it, SmbHosts.isPlainHost(it)) }
        listOf("", "smb://nas", "nas/share", "user@nas", "nas:445", "999.1.1.1", "-nas", "a..b", "nas ").forEach {
            assertFalse(it, SmbHosts.isPlainHost(it))
        }
    }

    @Test
    fun `the password never appears in logs`() {
        val l = SmbLocation("nas", "photos", user = "me", password = "s3cret")
        assertFalse(l.toString().contains("s3cret"))
    }
}

internal object SmbTestEnv {
    fun location(): SmbLocation? {
        val host = System.getenv("KEEPCLEAR_SMB_HOST") ?: return null
        return SmbLocation(
            host = host,
            share = System.getenv("KEEPCLEAR_SMB_SHARE") ?: return null,
            user = System.getenv("KEEPCLEAR_SMB_USER").orEmpty(),
            password = System.getenv("KEEPCLEAR_SMB_PASSWORD").orEmpty(),
            port = System.getenv("KEEPCLEAR_SMB_PORT")?.toInt() ?: 445,
        )
    }
}
