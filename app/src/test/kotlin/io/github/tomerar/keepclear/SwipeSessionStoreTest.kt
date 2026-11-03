// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.engine.classify.ClassifiedItem
import io.github.tomerar.keepclear.engine.classify.Classifier
import io.github.tomerar.keepclear.engine.scan.ScanItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR H2b (owner brief H2 item 8): resumable one-by-one sessions. The saved format is versioned and read defensively;
 * on resume, a decision whose file changed or went away is dropped, so it can never act on another file.
 */
class SwipeSessionStoreTest {
    private val classifier = Classifier()

    private fun item(name: String, size: Long = 1_000, mtime: Long = 1_678_000_000_000L): ClassifiedItem {
        val p = "/storage/emulated/0/Pictures/Screenshots/$name"
        return classifier.classify(ScanItem(KPath.Local(p), p, size, mtime), now = 1_800_000_000_000L)
    }

    private val a = item("a.png")
    private val b = item("b.png")
    private val c = item("c.png")
    private val saved = listOf(
        SwipeDecision.of(a, SwipeChoice.DELETE),
        SwipeDecision.of(b, SwipeChoice.KEEP),
        SwipeDecision.of(c, SwipeChoice.DELETE),
    )

    // ------------------------------------------------------------------ resume: what still holds

    @Test
    fun `resume keeps every decision when nothing changed, in order`() {
        assertEquals(saved, SwipeSession.resume(listOf(a, b, c), saved))
        assertNull(SwipeSession.next(listOf(a, b, c), saved))
    }

    @Test
    fun `resume drops a file that is gone, one whose size changed and one whose date changed`() {
        val bigger = item("a.png", size = 1_001)
        val touched = item("c.png", mtime = 1_678_000_000_001L)
        val now = listOf(bigger, touched) // b is gone
        assertEquals(emptyList<SwipeDecision>(), SwipeSession.resume(now, saved))
        // Nothing the user saw in another state is offered for deletion; both come up again, first one first.
        assertEquals(emptyList<ClassifiedItem>(), SwipeSession.toDelete(now, saved))
        assertEquals(bigger, SwipeSession.next(now, saved))
        assertEquals(0, SwipeSession.decided(now, saved))
    }

    @Test
    fun `table - which decisions survive a resume`() {
        data class Row(val now: List<ClassifiedItem>, val kept: List<String>)
        val rows = listOf(
            Row(listOf(a, b, c), listOf("a.png", "b.png", "c.png")),
            Row(listOf(c, b, a), listOf("a.png", "b.png", "c.png")), // another order: decisions follow the files
            Row(listOf(b, c), listOf("b.png", "c.png")),
            Row(listOf(item("a.png", size = 2), b, c), listOf("b.png", "c.png")),
            Row(listOf(a, item("b.png", mtime = 1), c), listOf("a.png", "c.png")),
            Row(listOf(item("d.png")), emptyList()),
            Row(emptyList(), emptyList()),
        )
        for (r in rows) {
            assertEquals(r.kept, SwipeSession.resume(r.now, saved).map { it.path.substringAfterLast('/') })
        }
    }

    @Test
    fun `a changed file decided again replaces its old decision, and Delete applies only to the new one`() {
        val changed = item("a.png", size = 5_000)
        var d = SwipeSession.decide(saved, changed, SwipeChoice.KEEP)
        assertEquals(3, d.size)
        assertEquals(SwipeDecision.of(changed, SwipeChoice.KEEP), d.last())
        assertEquals(listOf(c), SwipeSession.toDelete(listOf(changed, b, c), d))
        d = SwipeSession.decide(d, changed, SwipeChoice.DELETE) // already decided: nothing changes
        assertEquals(SwipeChoice.KEEP, d.single { it.path == changed.item.path }.choice)
    }

    // ------------------------------------------------------------------ the format

    @Test
    fun `encode and decode round-trip, tabs in a path kept`() {
        val tab = item("with\ttab.png")
        val all = saved + SwipeDecision.of(tab, SwipeChoice.SKIP)
        val text = SwipeSessionCodec.encode("screenshots|REVIEW", all)
        assertTrue(text.startsWith("keepclear-swipe\nv=1\nkey=screenshots|REVIEW\n"))
        assertEquals("screenshots|REVIEW" to all, SwipeSessionCodec.decode(text))
    }

    @Test
    fun `stored choices use the enum names, which never change (MIG-2)`() {
        assertEquals(listOf("KEEP", "DELETE", "SKIP"), SwipeChoice.entries.map { it.name })
        val text = SwipeSessionCodec.encode("k", listOf(SwipeDecision("/x", SwipeChoice.DELETE, 7, 8)))
        assertTrue(text.endsWith("D\tDELETE\t7\t8\t/x\n"))
    }

    @Test
    fun `another version, a wrong header or no key is not read`() {
        val good = SwipeSessionCodec.encode("k", saved)
        assertNull(SwipeSessionCodec.decode(good.replace("v=1", "v=2")))
        assertNull(SwipeSessionCodec.decode(good.replace("keepclear-swipe", "something")))
        assertNull(SwipeSessionCodec.decode(good.replace("key=k", "key=")))
        assertNull(SwipeSessionCodec.decode(""))
    }

    @Test
    fun `bad lines are skipped, never guessed`() {
        val text = listOf(
            "keepclear-swipe",
            "v=1",
            "key=k",
            "D\tDELETE\t10\t20\t/ok",
            "D\tERASE\t10\t20\t/unknown-choice",
            "D\tDELETE\t-1\t20\t/negative-size",
            "D\tDELETE\tx\t20\t/bad-size",
            "D\tDELETE\t10\ty\t/bad-date",
            "D\tDELETE\t10\t20\trelative/path",
            "D\tDELETE\t10\t20",
            "X\tDELETE\t10\t20\t/other-kind",
            "D\tKEEP\t10\t20\t/ok", // a second line for the same file: ignored
        ).joinToString("\n")
        assertEquals(listOf(SwipeDecision("/ok", SwipeChoice.DELETE, 10, 20)), SwipeSessionCodec.decode(text)!!.second)
    }

    @Test
    fun `a path with a line break is never written`() {
        val odd = SwipeDecision("/a\nD\tDELETE\t1\t1\t/victim", SwipeChoice.KEEP, 1, 1)
        val text = SwipeSessionCodec.encode("k", listOf(odd, saved[0]))
        assertEquals(listOf(saved[0]), SwipeSessionCodec.decode(text)!!.second)
    }

    // ------------------------------------------------------------------ the store

    @Test
    fun `save, load in a new store, and clear`() {
        val backend = SwipeSessionStore.MapBackend()
        val store = SwipeSessionStore(backend)
        store.save("screenshots|REVIEW", saved)
        store.save("duplicates|REVIEW", saved.take(1))
        val again = SwipeSessionStore(backend).loadAll()
        assertEquals(mapOf("screenshots|REVIEW" to saved, "duplicates|REVIEW" to saved.take(1)), again)
        store.clear("screenshots|REVIEW")
        assertEquals(setOf("duplicates|REVIEW"), SwipeSessionStore(backend).loadAll().keys)
        store.save("duplicates|REVIEW", emptyList()) // empty = ended
        assertEquals(emptyMap<String, List<SwipeDecision>>(), SwipeSessionStore(backend).loadAll())
        assertTrue(backend.map.isEmpty())
    }

    @Test
    fun `other entries, a damaged one and a session filed under another group are ignored`() {
        val backend = SwipeSessionStore.MapBackend()
        val store = SwipeSessionStore(backend)
        assertEquals(emptyMap<String, List<SwipeDecision>>(), store.loadAll())
        store.save("a|REVIEW", saved)
        backend.map["something else"] = SwipeSessionCodec.encode("x|REVIEW", saved)
        backend.map["session:c|REVIEW"] = "keepclear-swipe\nv=1\nkey=c|REVIEW\nD\tDELETE" // half a line only
        // An entry whose inner key isn't its own (copied or edited): not this group's session.
        backend.map["session:b|REVIEW"] = SwipeSessionCodec.encode("z|REVIEW", saved.take(1))
        assertEquals(mapOf("a|REVIEW" to saved), store.loadAll())
    }
}
