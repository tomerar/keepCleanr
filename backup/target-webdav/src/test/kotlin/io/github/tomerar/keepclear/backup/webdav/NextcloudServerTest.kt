// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.backup.webdav

import io.github.tomerar.keepclear.backup.BackupTarget
import io.github.tomerar.keepclear.backup.BackupTargetContract
import io.github.tomerar.keepclear.backup.DigestKind
import io.github.tomerar.keepclear.backup.net.HostHttpClient
import io.github.tomerar.keepclear.core.storage.FakeStorageGateway
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.core.storage.StorageException
import io.github.tomerar.keepclear.engine.move.FreshRead
import io.github.tomerar.keepclear.engine.move.InMemoryMoveJobStore
import io.github.tomerar.keepclear.engine.move.JobState
import io.github.tomerar.keepclear.engine.move.MoveEngine
import io.github.tomerar.keepclear.engine.move.MoveFileState
import io.github.tomerar.keepclear.engine.move.MoveJob
import io.github.tomerar.keepclear.engine.move.MovePlanner
import io.github.tomerar.keepclear.engine.scan.ScanItem
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/**
 * W4 (owner 2026-11-02): the WebDAV target against a **real** Nextcloud, the official Docker image started by
 * `scripts/nextcloud-test-server.sh` (CI always starts it). Skipped when `KEEPCLEAR_NEXTCLOUD_URL` isn't set,
 * except in CI ([NextcloudTestServerPresentTest]).
 */
internal object NextcloudTestEnv {
    val url: String? = System.getenv("KEEPCLEAR_NEXTCLOUD_URL")
    val user: String = System.getenv("KEEPCLEAR_NEXTCLOUD_USER").orEmpty()
    val password: String = System.getenv("KEEPCLEAR_NEXTCLOUD_PASSWORD").orEmpty()

    fun assume() = assumeTrue(url != null, "no Nextcloud test server (scripts/nextcloud-test-server.sh)")

    /** A fresh, empty folder for one test. */
    fun location(nextcloud: Boolean = true, folder: String = "KeepClear-test/run-${UUID.randomUUID()}") =
        WebDavLocation.nextcloud(url!!, user, password, folder).let {
            if (nextcloud) it else WebDavLocation(it.canonicalUrl, user, password, nextcloud = false)
        }

    /** A plain client to the same server, for checks behind the target's back. */
    fun raw(): HostHttpClient {
        val u = URI(url!!)
        return HostHttpClient(u.scheme == "https", u.host, if (u.port == -1) 80 else u.port, 30_000)
    }

    val auth: Pair<String, String>
        get() = "Authorization" to "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray())
}

/** The shared contract against real Nextcloud, with its server-computed SHA-256. */
class NextcloudServerContractTest : BackupTargetContract() {
    @BeforeEach
    fun needsServer() = NextcloudTestEnv.assume()

    override fun newTarget(): BackupTarget = runBlocking {
        WebDavBackupTarget(NextcloudTestEnv.location(), "nc", "Nextcloud", timeoutSeconds = 30).also { it.mkdirs("") }
    }
}

/** The same contract against real Nextcloud used as a generic WebDAV server (verified by reading back). */
class NextcloudAsWebDavContractTest : BackupTargetContract() {
    @BeforeEach
    fun needsServer() = NextcloudTestEnv.assume()

    override fun newTarget(): BackupTarget = runBlocking {
        WebDavBackupTarget(NextcloudTestEnv.location(nextcloud = false), "dav", "WebDAV", timeoutSeconds = 30)
            .also { it.mkdirs("") }
    }
}

class NextcloudServerTest {
    @BeforeEach
    fun needsServer() = NextcloudTestEnv.assume()

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun target(location: WebDavLocation = NextcloudTestEnv.location()) =
        WebDavBackupTarget(location, "nc", "Nextcloud", timeoutSeconds = 30)

    private suspend fun BackupTarget.put(rel: String, bytes: ByteArray) =
        create(rel, bytes.size.toLong()).use { it.write(bytes) }

    @Test
    fun `X-Hash sha256 is answered with the SHA-256 of what Nextcloud stored, for sized and chunked uploads`(): Unit =
        runBlocking {
            val t = target()
            t.mkdirs("")
            val bytes = ByteArray(1_234_567) { (it * 13 + it / 7).toByte() }
            t.put("sized.bin", bytes)
            t.serverDigest("sized.bin", DigestKind.SHA256) shouldBe sha256(bytes)
            t.create("chunked.bin").use { it.write(bytes) }
            t.serverDigest("chunked.bin", DigestKind.SHA256) shouldBe sha256(bytes)
            t.put("empty", ByteArray(0))
            t.serverDigest("empty", DigestKind.SHA256) shouldBe sha256(ByteArray(0))
            t.openRead("sized.bin").use { it.readBytes() } shouldBe bytes
        }

    @Test
    fun `the digest follows a rename, and the renamed copy still reads back to it`(): Unit = runBlocking {
        val t = target()
        t.mkdirs("d")
        val bytes = ByteArray(300_000) { (it * 7).toByte() }
        t.put("d/.kc-tmp-1", bytes)
        t.rename("d/.kc-tmp-1", "d/IMG_0001.jpg")
        t.serverDigest("d/.kc-tmp-1", DigestKind.SHA256) shouldBe null
        t.serverDigest("d/IMG_0001.jpg", DigestKind.SHA256) shouldBe sha256(bytes)
        sha256(t.openRead("d/IMG_0001.jpg").use { it.readBytes() }) shouldBe sha256(bytes)
        t.stat("d/.kc-tmp-1") shouldBe null
    }

    @Test
    fun `Nextcloud itself refuses to overwrite, by PUT with If-None-Match and MOVE with Overwrite F`() {
        val loc = NextcloudTestEnv.location()
        runBlocking {
            val t = target(loc)
            t.mkdirs("")
            t.put("a", "one".toByteArray())
            t.put("b", "two".toByteArray())
        }
        val base = DavPath.encode(loc.baseSegments)
        NextcloudTestEnv.raw().use { http ->
            val put = http.send(
                "PUT",
                "$base/a",
                listOf(NextcloudTestEnv.auth, "If-None-Match" to "*"),
                "evil".toByteArray(),
            )
            put.close()
            put.status shouldBe 412
            val move = http.send(
                "MOVE",
                "$base/b",
                listOf(NextcloudTestEnv.auth, "Destination" to http.absolute("$base/a"), "Overwrite" to "F"),
            )
            move.close()
            move.status shouldBe 412
        }
        runBlocking {
            val t = target(loc)
            t.openRead("a").use { it.readBytes() } shouldBe "one".toByteArray()
            t.openRead("b").use { it.readBytes() } shouldBe "two".toByteArray()
        }
    }

    @Test
    fun `a name being uploaded is locked, so no other writer can take it meanwhile (423 means busy)`(): Unit =
        runBlocking {
            val loc = NextcloudTestEnv.location()
            val t = target(loc)
            t.mkdirs("")
            val bytes = ByteArray(200_000) { 1 }
            val out = t.create("race", bytes.size.toLong())
            out.write(bytes, 0, 100_000)
            out.flush()
            Thread.sleep(500)
            // Another device tries to write the same name before KeepClear's upload ends: Nextcloud refuses it
            // with 423 Locked (FakeDavServer does the same since W4), which KeepClear treats as "busy, try later".
            NextcloudTestEnv.raw().use { http ->
                val r = http.send(
                    "PUT",
                    DavPath.encode(loc.baseSegments + "race"),
                    listOf(NextcloudTestEnv.auth, "If-None-Match" to "*"),
                    "theirs".toByteArray(),
                )
                r.close()
                r.status shouldBe 423
            }
            shouldThrow<StorageException.Disconnected> { target(loc).put("race", "theirs".toByteArray()) }
            out.write(bytes, 100_000, 100_000)
            out.close()
            t.openRead("race").use { it.readBytes() } shouldBe bytes
            t.serverDigest("race", DigestKind.SHA256) shouldBe sha256(bytes)
        }

    @Test
    fun `a wrong app password is refused, not a missing server`(): Unit = runBlocking {
        val good = NextcloudTestEnv.location()
        val bad = WebDavLocation(good.canonicalUrl, NextcloudTestEnv.user, "wrong-password", nextcloud = true)
        shouldThrow<StorageException.PermissionDenied> { target(bad).stat("") }
        WebDavTargets.open(bad, "Nextcloud", timeoutSeconds = 30) shouldBe WebDavCheck.Refused
    }

    @Test
    fun `a length mismatch leaves no file on the real server`(): Unit = runBlocking {
        val t = target()
        t.mkdirs("")
        shouldThrow<StorageException> { t.create("short", 100_000).use { it.write(ByteArray(50_000)) } }
        val out = t.create("long", 65_536)
        out.write(ByteArray(65_536) { 5 })
        out.flush()
        Thread.sleep(500)
        shouldThrow<StorageException> { out.write(1) }
        runCatching { out.close() }
        Thread.sleep(1_000)
        t.stat("short") shouldBe null
        t.stat("long") shouldBe null
        t.list("").map { it.name }.shouldBeEmpty()
    }

    @Test
    fun `OC-Checksum is never sent, so Nextcloud stores no client checksum for our uploads`(): Unit = runBlocking {
        val loc = NextcloudTestEnv.location()
        val t = target(loc)
        t.mkdirs("")
        t.put("f", ByteArray(1_000) { 2 })
        val body = """<?xml version="1.0"?><d:propfind xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">""" +
            """<d:prop><oc:checksums/></d:prop></d:propfind>"""
        val xml = NextcloudTestEnv.raw().use { http ->
            http.send(
                "PROPFIND",
                DavPath.encode(loc.baseSegments + "f"),
                listOf(NextcloudTestEnv.auth, "Depth" to "0", "Content-Type" to "application/xml"),
                body.toByteArray(),
            ).readBody(64 * 1024).toString(Charsets.UTF_8)
        }
        xml shouldNotContain "SHA256:"
        xml shouldNotContain "SHA1:"
        xml shouldNotContain "MD5:"
    }

    @Test
    fun `WebDavTargets open makes the folder and the marker, and finds it again`(): Unit = runBlocking {
        val loc = NextcloudTestEnv.location()
        val first = WebDavTargets.open(loc, "Nextcloud", timeoutSeconds = 30)
        val again = WebDavTargets.open(loc, "Nextcloud", timeoutSeconds = 30)
        (first as WebDavCheck.Ok).target.id shouldBe (again as WebDavCheck.Ok).target.id
    }
}

/**
 * W4: the MOVE engine end to end against real Nextcloud, like WebDavMoveTest against the fake: verified by the
 * server's own SHA-256 without a download, and freeable only after every copy is read back and re-hashed.
 */
class NextcloudMoveTest {
    @BeforeEach
    fun needsServer() = NextcloudTestEnv.assume()

    private val phone = "/storage/emulated/0"
    private val fake = FakeStorageGateway(caseInsensitive = true, volumeRoots = listOf(phone))
    private val store = InMemoryMoveJobStore()
    private val items = (0 until 4).map { i ->
        val path = "$phone/DCIM/Camera/IMG_2021010${i}_120000.jpg"
        fake.addFile(path, ByteArray(300_000 + i) { (it * (i + 3)).toByte() }, mtime = 1_610_000_000_000L + i)
        ScanItem(KPath.Local(path), path, (300_000 + i).toLong(), 1_610_000_000_000L + i)
    }

    private suspend fun open(nextcloud: Boolean): WebDavBackupTarget {
        val ok = WebDavTargets.open(NextcloudTestEnv.location(nextcloud), "Nextcloud", timeoutSeconds = 30)
        return (ok as WebDavCheck.Ok).target
    }

    private suspend fun job(target: WebDavBackupTarget): String {
        store.putJob(MoveJob("j", target.id, target.label, MovePlanner.rootRel("Phone"), 1))
        MovePlanner.plan("j", items, "Phone", "Camera", target.caps).forEach { store.putFile(it) }
        return "j"
    }

    private fun engine(target: WebDavBackupTarget) = MoveEngine(fake, target, store, clock = { 9L })

    @Test
    fun `Nextcloud verifies every copy by its own SHA-256, and frees only after re-reading them`(): Unit =
        runBlocking {
            val target = open(nextcloud = true)
            val id = job(target)
            engine(target).run(id).state shouldBe JobState.DONE
            store.files(id).all { it.state == MoveFileState.VERIFIED && it.confirmedAt != null } shouldBe true
            store.files(id).forEach { f -> target.serverDigest(f.relTarget, DigestKind.SHA256).shouldNotBeNull() }
            engine(target).originalsPlan(id).size shouldBe 4
            items.forEach { item ->
                val f = store.files(id).single { it.sourcePath == item.path }
                target.openRead(f.relTarget).use { it.readBytes() } shouldBe fake.readAllBytes(item.path)
            }
            target.list(store.files(id).first().relTarget.substringBeforeLast('/'))
                .filter { ".kc-tmp" in it.name }.shouldBeEmpty()
        }

    @Test
    fun `as a generic WebDAV server it verifies by reading back, frees after a check over a new connection`(): Unit =
        runBlocking {
            val target = open(nextcloud = false)
            val id = job(target)
            engine(target).run(id).state shouldBe JobState.DONE
            store.files(id).all { it.state == MoveFileState.VERIFIED && it.confirmedAt == null } shouldBe true
            engine(target).originalsPlan(id).shouldBeEmpty()
            target.close()
            engine(target).confirm(id, FreshRead.NEW_SESSION).ok shouldBe 4
            engine(target).originalsPlan(id).size shouldBe 4
        }
}

class NextcloudTestServerPresentTest {
    @Test
    fun `CI provides the Nextcloud test server`() {
        if (System.getenv("CI") == "true") {
            assertNotNull(NextcloudTestEnv.url, "start scripts/nextcloud-test-server.sh in CI")
        }
    }
}
