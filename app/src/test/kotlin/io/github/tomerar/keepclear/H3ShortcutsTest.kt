// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.content.Intent
import io.github.tomerar.keepclear.core.safety.ActionPlan
import io.github.tomerar.keepclear.core.safety.OpKind
import io.github.tomerar.keepclear.core.safety.PlannedOp
import io.github.tomerar.keepclear.core.storage.KPath
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.w3c.dom.Element
import java.io.File
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Owner brief H3 item 7: the launcher's static shortcuts open a screen and never delete anything, also when another
 * app sends the same intent to KeepClear's (exported) activity.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class H3ShortcutsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun intent(extra: String?, action: String = ShortcutRoute.ACTION) =
        Intent(action).apply { if (extra != null) putExtra(ShortcutRoute.EXTRA, extra) }

    @Test
    fun `only the shortcut action with a known name is a route`() {
        assertEquals(ShortcutRoute.FREE, ShortcutRoute.of(intent("free")))
        assertEquals(ShortcutRoute.TRASH, ShortcutRoute.of(intent("trash")))
        assertEquals(ShortcutRoute.BACKUP, ShortcutRoute.of(intent("backup")))
        assertNull(ShortcutRoute.of(intent("empty_trash")))
        assertNull(ShortcutRoute.of(intent(null)))
        assertNull(ShortcutRoute.of(intent("trash", Intent.ACTION_MAIN)))
        assertNull(ShortcutRoute.of(null))
    }

    @Test
    fun `each route only navigates`() {
        val calls = mutableListOf<String>()
        val nav = object : ShortcutNavigator {
            override fun selectTab(tab: Tab) {
                calls += "tab:$tab"
            }

            override fun openTrash() {
                calls += "trash"
            }
        }
        ShortcutRoute.entries.forEach { Shortcuts.handle(it, nav) }
        assertEquals(listOf("tab:FREE", "trash", "tab:BACKUP"), calls)
    }

    private lateinit var t: TestApp

    @After
    fun tearDown() {
        if (::t.isInitialized) t.close()
    }

    @Test
    fun `handling every shortcut on the real app changes no file, no trash entry and records nothing`() {
        t = TestApp(tmp.newFolder("phone"))
        // Test mode off: if anything destructive ran, it would really happen.
        t.app.testMode.turnOff()
        val historyWrites = java.util.concurrent.atomic.AtomicInteger()
        t.app.historyBackend = object : ActivityHistory.Backend {
            override fun read(): String? = null

            override fun write(text: String) {
                historyWrites.incrementAndGet()
            }
        }
        val kept = t.file("DCIM/Camera/IMG_1.jpg", ByteArray(3_000) { 7 })
        val trashed = t.file("Pictures/Screenshots/s.png", ByteArray(2_000) { 9 }).absolutePath
        runBlocking {
            val s = t.app.localGateway.stat(KPath.Local(trashed))!!
            val op = PlannedOp(s.path, trashed, OpKind.TRASH, false, s.size, s.mtimeMillis, s.identity)
            t.app.executor().run(
                ActionPlan(
                    UUID.randomUUID().toString(),
                    listOf(op),
                    "clean",
                ).confirm(userConfirmed(listOf(trashed)), t.app.sessionId),
            )
        }
        TestApp.settle(300)
        val writesBefore = historyWrites.get()
        val trashBefore = runBlocking { t.app.trashManager.entries() }
        val filesBefore = snapshot(t.root)
        val vm = t.viewModel(HomeViewModel::class.java)
        val nav = Shortcuts.navigator(vm)
        Shortcuts.handle(ShortcutRoute.TRASH, nav)
        TestApp.awaitMain("trash shown") {
            vm.state.value.screens.last() == Screen.Trash &&
                vm.state.value.trash.entries.size == 1
        }
        Shortcuts.handle(ShortcutRoute.BACKUP, nav)
        assertEquals(Tab.BACKUP, vm.state.value.tab)
        Shortcuts.handle(ShortcutRoute.FREE, nav)
        assertEquals(Tab.FREE, vm.state.value.tab)
        TestApp.settle(500)
        assertEquals(filesBefore, snapshot(t.root))
        assertEquals(trashBefore, runBlocking { t.app.trashManager.entries() })
        assertTrue(kept.exists())
        assertEquals(null, vm.state.value.cleanResult)
        assertEquals(writesBefore, historyWrites.get())
    }

    private fun snapshot(root: File): Map<String, Long> =
        root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).path to it.length() }

    // ------------------------------------------------------------------------------------------- the XML

    private val app = generateSequence(File("").absoluteFile) {
        it.parentFile
    }.first { File(it, "settings.gradle.kts").exists() }
        .let { File(it, "app") }

    private fun shortcuts(variant: String): List<Element> {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(File(app, "src/$variant/res/xml/shortcuts.xml"))
        val list = doc.getElementsByTagName("shortcut")
        return (0 until list.length).map { list.item(it) as Element }
    }

    private fun Element.a(name: String) = getAttributeNS("http://schemas.android.com/apk/res/android", name)

    private fun child(e: Element, tag: String) = e.getElementsByTagName(tag).item(0) as Element

    @Test
    fun `the release and test builds list the same shortcuts, each for its own app`() {
        for ((variant, pkg) in listOf(
            "main" to "io.github.tomerar.keepclear",
            "debug" to "io.github.tomerar.keepclear.test",
        )) {
            val items = shortcuts(variant)
            assertEquals(variant, ShortcutRoute.entries.map { it.extra }, items.map { child(it, "extra").a("value") })
            items.forEach { s ->
                val i = child(s, "intent")
                assertEquals(ShortcutRoute.ACTION, i.a("action"))
                assertEquals(pkg, i.a("targetPackage"))
                assertEquals("io.github.tomerar.keepclear.MainActivity", i.a("targetClass"))
                assertEquals(ShortcutRoute.EXTRA, child(s, "extra").a("name"))
                assertEquals(s.a("shortcutId"), child(s, "extra").a("value"))
            }
        }
    }

    @Test
    fun `short labels fit the launcher - at most 10 characters in both languages`() {
        val res = File(app, "../core/i18n/src/main/res")
        for (dir in listOf("values", "values-iw")) {
            val xml = File(res, "$dir/strings.xml").readText()
            shortcuts("main").forEach { s ->
                val key = s.a("shortcutShortLabel").removePrefix("@string/")
                val text = Regex("""<string name="$key">(.*?)</string>""").find(xml)!!.groupValues[1]
                assertTrue("$dir/$key: $text", text.length <= 10)
            }
        }
    }
}
