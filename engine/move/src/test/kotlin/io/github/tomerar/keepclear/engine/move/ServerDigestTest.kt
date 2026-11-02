// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.move

import io.github.tomerar.keepclear.backup.BackupTarget
import io.github.tomerar.keepclear.backup.DigestKind
import io.github.tomerar.keepclear.backup.GatewayBackupTarget
import io.github.tomerar.keepclear.backup.TargetCaps
import io.github.tomerar.keepclear.core.hash.DropboxContentHash
import io.github.tomerar.keepclear.core.hash.Sha256
import io.github.tomerar.keepclear.core.storage.FakeStorageGateway
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.core.storage.StorageException
import io.github.tomerar.keepclear.core.storage.StorageGateway
import io.github.tomerar.keepclear.engine.scan.ScanItem
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * M17, ADR 0011: a digest the **server** computed over the stored upload (Nextcloud `X-Hash-SHA256`, Dropbox
 * `content_hash`) verifies a copy without downloading it. A wrong or missing digest never counts; the copy is
 * still re-hashed at free time.
 */
class ServerDigestTest {
    private enum class Mode {
        HONEST,
        LYING,
        NONE,

        /** A value that isn't a digest at all: treated like no digest (read back). */
        GARBAGE,

        /** The plain SHA-256 where the block hash belongs (a server or target mixing up kinds): a mismatch. */
        WRONG_KIND,
    }

    private class Rig(
        private val mode: Mode,
        files: Int = 4,
        private val kind: DigestKind = DigestKind.SHA256,
        sizes: (Int) -> Int = { 5_000 + it },
    ) {
        val fake = FakeStorageGateway(caseInsensitive = true, volumeRoots = listOf(PHONE, USB)).apply { mkdirs(USB) }
        val store = InMemoryMoveJobStore()
        val digests = mutableMapOf<String, String>()
        var dataReads = 0

        /** Reads of the phone's originals by the engine. */
        var sourceReads = 0
        private val source: StorageGateway = object : StorageGateway by fake {
            override suspend fun openRead(path: KPath): InputStream {
                sourceReads++
                return fake.openRead(path)
            }
        }

        /** Bytes the "server" stores are changed after it hashed them (a disk that went bad later). */
        var corruptStored = false
        private val inner = GatewayBackupTarget(
            fake,
            KPath.Local(USB),
            "nc",
            "Nextcloud",
            TargetCaps(canReadBack = true, readsFromServer = true, serverDigests = setOf(kind)),
        )
        val target: BackupTarget = object : BackupTarget by inner {
            override suspend fun create(rel: String, size: Long): OutputStream {
                val out = inner.create(rel)
                val sink = java.io.ByteArrayOutputStream()
                return object : FilterOutputStream(out) {
                    override fun write(b: ByteArray, off: Int, len: Int) {
                        sink.write(b, off, len)
                        out.write(b, off, len)
                    }

                    override fun close() {
                        super.close()
                        val received = sink.toByteArray()
                        val sha = when (kind) {
                            DigestKind.SHA256 -> Sha256.of(received)
                            DigestKind.DROPBOX_CONTENT_HASH -> DropboxContentHash.of(received)
                        }
                        when (mode) {
                            Mode.HONEST -> digests[rel] = sha
                            Mode.LYING -> digests[rel] = sha.reversed()
                            Mode.NONE -> Unit
                            Mode.GARBAGE -> digests[rel] = "not-a-digest"
                            Mode.WRONG_KIND -> digests[rel] = Sha256.of(received)
                        }
                        if (corruptStored && !rel.contains("SHA256SUMS") && !rel.contains(".jsonl")) {
                            val path = "$USB/$rel"
                            val bytes = fake.readAllBytes(path).also { it[0] = (it[0] + 1).toByte() }
                            fake.modify(path, FakeStorageGateway.Content.Bytes(bytes))
                        }
                    }
                }
            }

            override suspend fun rename(fromRel: String, toRel: String) {
                inner.rename(fromRel, toRel)
                digests.remove(fromRel)?.let { digests[toRel] = it }
            }

            override suspend fun delete(rel: String) {
                inner.delete(rel)
                digests.remove(rel)
            }

            override suspend fun openRead(rel: String): InputStream {
                if (!rel.endsWith("SHA256SUMS") && !rel.endsWith(".jsonl")) dataReads++
                return inner.openRead(rel)
            }

            override suspend fun serverDigest(rel: String, kind: DigestKind): String? =
                if (kind == this@Rig.kind) digests[rel] else null
        }
        val items: List<ScanItem> = (0 until files).map { i ->
            val path = "$PHONE/DCIM/Camera/IMG_2021010${i}_120000.jpg"
            fake.addFile(path, ByteArray(sizes(i)) { (it * (i + 7)).toByte() }, mtime = 1_610_000_000_000L + i)
            ScanItem(KPath.Local(path), path, sizes(i).toLong(), 1_610_000_000_000L + i)
        }

        suspend fun job(): String {
            store.putJob(MoveJob("j", target.id, target.label, MovePlanner.rootRel("Phone"), 1))
            MovePlanner.plan("j", items, "Phone", "Camera", target.caps).forEach { store.putFile(it) }
            return "j"
        }

        fun engine() = MoveEngine(source, target, store, clock = { 7L })
    }

    @Test
    fun `a matching server digest verifies without a download and counts as confirmed`(): Unit = runBlocking {
        val rig = Rig(Mode.HONEST)
        val id = rig.job()
        rig.engine().run(id).state shouldBe JobState.DONE
        rig.dataReads shouldBe 0
        rig.store.files(id).all { it.state == MoveFileState.VERIFIED && it.confirmedAt != null } shouldBe true
        // M17b: the method is recorded per file, and the receipt counts what the server checked.
        rig.store.files(id).all { it.verifiedBy == VerifyMethod.SERVER_DIGEST } shouldBe true
        rig.engine().receipt(id).checkedByServer shouldBe 4
        // Free time still re-reads and re-hashes every copy.
        rig.engine().originalsPlan(id).size shouldBe 4
        rig.dataReads shouldBe 4
    }

    @Test
    fun `a wrong server digest is never verified, the copy is redone and finally fails`(): Unit = runBlocking {
        val rig = Rig(Mode.LYING)
        val id = rig.job()
        rig.engine().run(id)
        rig.store.files(id).forEach {
            it.state shouldBe MoveFileState.FAILED
            it.failReason shouldBe FailReason.HASH_MISMATCH
            it.confirmedAt shouldBe null
        }
        rig.engine().originalsPlan(id).shouldBeEmpty()
        rig.engine().receipt(id).verified shouldBe 0
    }

    @Test
    fun `without a server digest the copy is read back and needs the second check`(): Unit = runBlocking {
        val rig = Rig(Mode.NONE)
        val id = rig.job()
        rig.engine().run(id).state shouldBe JobState.DONE
        rig.dataReads shouldBe 4
        rig.store.files(id).all { it.state == MoveFileState.VERIFIED && it.confirmedAt == null } shouldBe true
        rig.store.files(id).all { it.verifiedBy == VerifyMethod.READ_BACK } shouldBe true
        rig.engine().receipt(id).checkedByServer shouldBe 0
        rig.engine().originalsPlan(id).shouldBeEmpty()
    }

    @Test
    fun `a copy damaged after the server hashed it is caught by the re-hash at free time`(): Unit = runBlocking {
        val rig = Rig(Mode.HONEST)
        rig.corruptStored = true
        val id = rig.job()
        rig.engine().run(id)
        rig.store.files(id).all { it.state == MoveFileState.VERIFIED } shouldBe true
        rig.engine().originalsPlan(id).shouldBeEmpty()
    }

    // ------------------------------------------------------------------ Dropbox content_hash (M17c)

    @Test
    fun `a matching Dropbox content hash verifies without a download, from a single read of the source`(): Unit =
        runBlocking {
            // Before M17c the engine only knew SHA256: this target's digests were ignored and every copy was
            // downloaded again (dataReads 4, never confirmed).
            val rig = Rig(Mode.HONEST, kind = DigestKind.DROPBOX_CONTENT_HASH)
            val id = rig.job()
            rig.engine().run(id).state shouldBe JobState.DONE
            rig.dataReads shouldBe 0
            rig.sourceReads shouldBe 4
            rig.store.files(id).forEach {
                it.state shouldBe MoveFileState.VERIFIED
                it.confirmedAt shouldNotBe null
                it.sourceContentHash shouldBe DropboxContentHash.of(rig.fake.readAllBytes(it.sourcePath))
            }
            rig.engine().originalsPlan(id).size shouldBe 4
            rig.dataReads shouldBe 4
        }

    @Test
    fun `files larger than one Dropbox block verify by the block hash`(): Unit = runBlocking {
        val size = DropboxContentHash.BLOCK_SIZE + 1_000
        val rig = Rig(Mode.HONEST, files = 2, kind = DigestKind.DROPBOX_CONTENT_HASH, sizes = { size + it })
        val id = rig.job()
        rig.engine().run(id).state shouldBe JobState.DONE
        rig.dataReads shouldBe 0
        rig.store.files(id).all { it.state == MoveFileState.VERIFIED && it.confirmedAt != null } shouldBe true
    }

    @Test
    fun `a wrong Dropbox content hash is redone and finally fails, never read back into a pass`(): Unit =
        runBlocking {
            for (mode in listOf(Mode.LYING, Mode.WRONG_KIND)) {
                val rig = Rig(mode, kind = DigestKind.DROPBOX_CONTENT_HASH)
                val id = rig.job()
                rig.engine().run(id)
                rig.store.files(id).forEach {
                    it.state shouldBe MoveFileState.FAILED
                    it.failReason shouldBe FailReason.HASH_MISMATCH
                    it.attempts shouldBe 3
                }
                rig.dataReads shouldBe 0
                rig.engine().originalsPlan(id).shouldBeEmpty()
            }
        }

    @Test
    fun `a missing or malformed Dropbox content hash falls back to the read-back`(): Unit = runBlocking {
        for (mode in listOf(Mode.NONE, Mode.GARBAGE)) {
            val rig = Rig(mode, kind = DigestKind.DROPBOX_CONTENT_HASH)
            val id = rig.job()
            rig.engine().run(id).state shouldBe JobState.DONE
            rig.dataReads shouldBe 4
            rig.store.files(id).all { it.state == MoveFileState.VERIFIED && it.confirmedAt == null } shouldBe true
            rig.engine().originalsPlan(id).shouldBeEmpty()
        }
    }

    @Test
    fun `a malformed SHA-256 from the server falls back to the read-back`(): Unit = runBlocking {
        val rig = Rig(Mode.GARBAGE)
        val id = rig.job()
        rig.engine().run(id).state shouldBe JobState.DONE
        rig.dataReads shouldBe 4
        rig.store.files(id).all { it.state == MoveFileState.VERIFIED && it.confirmedAt == null } shouldBe true
    }

    @Test
    fun `without a local content hash (job resumed after a restart) the copy is read back`(): Unit = runBlocking {
        val rig = Rig(Mode.HONEST, kind = DigestKind.DROPBOX_CONTENT_HASH)
        val id = rig.job()
        val target = rig.target
        // The Room store doesn't keep the content hash yet (M17b): simulate a file checkpointed as PLACED without it.
        rig.store.files(id).forEach { f ->
            val bytes = rig.fake.readAllBytes(f.sourcePath)
            target.mkdirs(f.relTarget.substringBeforeLast('/'))
            target.create(f.relTarget, bytes.size.toLong()).use { it.write(bytes) }
            val stat = rig.fake.stat(f.source)!!
            rig.store.putFile(
                f.copy(
                    state = MoveFileState.PLACED,
                    sourceSize = stat.size,
                    sourceMtime = stat.mtimeMillis,
                    sourceSha256 = Sha256.of(bytes),
                    sourceContentHash = null,
                ),
            )
        }
        rig.engine().run(id).state shouldBe JobState.DONE
        rig.dataReads shouldBe 4
        rig.store.files(id).all { it.state == MoveFileState.VERIFIED && it.confirmedAt == null } shouldBe true
    }

    @Test
    fun `the plain SHA-256 path computes no content hash`(): Unit = runBlocking {
        val rig = Rig(Mode.HONEST)
        val id = rig.job()
        rig.engine().run(id)
        rig.store.files(id).forEach { it.sourceContentHash shouldBe null }
    }

    @Test
    fun `a target error while the source changed is a source change, not a failure`(): Unit = runBlocking {
        val fake = FakeStorageGateway(caseInsensitive = true, volumeRoots = listOf(PHONE, USB)).apply { mkdirs(USB) }
        val path = "$PHONE/DCIM/Camera/IMG_20210101_120000.jpg"
        fake.addFile(path, ByteArray(4_000) { 1 }, mtime = 1_610_000_000_000L)
        val item = ScanItem(KPath.Local(path), path, 4_000, 1_610_000_000_000L)
        val inner = GatewayBackupTarget(fake, KPath.Local(USB), "nc", "NC", TargetCaps(canReadBack = true))
        var grow = true
        val target = object : BackupTarget by inner {
            override suspend fun create(rel: String, size: Long): OutputStream {
                if (!grow) return inner.create(rel)
                // The source grows while it uploads; the server refuses a body longer than announced.
                fake.modify(path, FakeStorageGateway.Content.Bytes(ByteArray(5_000) { 2 }), mtime = 1_610_000_100_000L)
                grow = false
                val out = inner.create(rel)
                return object : FilterOutputStream(out) {
                    override fun close() {
                        super.close()
                        throw StorageException.Io(KPath.Local("/$rel"), message = "length mismatch")
                    }
                }
            }
        }
        val store = InMemoryMoveJobStore()
        store.putJob(MoveJob("j", target.id, target.label, MovePlanner.rootRel("Phone"), 1))
        MovePlanner.plan("j", listOf(item), "Phone", "Camera", target.caps).forEach { store.putFile(it) }
        val engine = MoveEngine(fake, target, store, clock = { 2_000_000_000_000L })
        engine.run("j")
        val file = store.files("j").single()
        file.state shouldNotBe MoveFileState.FAILED
        // Copied again from the new content and verified by read-back.
        file.state shouldBe MoveFileState.VERIFIED
        file.sourceSize shouldBe 5_000L
        fake.files(USB).filter { ".kc-tmp" in it }.shouldBeEmpty()
    }
}
