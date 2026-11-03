// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import io.github.tomerar.keepclear.backup.smb.SmbLocation
import io.github.tomerar.keepclear.core.designsystem.KeepClearTheme
import io.github.tomerar.keepclear.core.designsystem.component.NoticeTimeout
import io.github.tomerar.keepclear.core.model.Category
import io.github.tomerar.keepclear.core.safety.OpKind
import io.github.tomerar.keepclear.core.safety.PlannedOp
import io.github.tomerar.keepclear.core.safety.ProtectedPaths
import io.github.tomerar.keepclear.core.safety.SafetyGuard
import io.github.tomerar.keepclear.core.storage.FakeStorageGateway
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.engine.classify.ClassificationSummary
import io.github.tomerar.keepclear.engine.classify.ClassifiedItem
import io.github.tomerar.keepclear.engine.classify.Classifier
import io.github.tomerar.keepclear.engine.classify.Total
import io.github.tomerar.keepclear.engine.enrich.CleanPlanner
import io.github.tomerar.keepclear.engine.enrich.FactEnricher
import io.github.tomerar.keepclear.engine.scan.ScanEvent
import io.github.tomerar.keepclear.engine.scan.ScanItem
import io.github.tomerar.keepclear.engine.scan.Scanner
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * PR H5 (owner brief "Power users"): review group sort, year filter, search, "select the N largest" and the selection
 * bar; the rules list with „לבטל את הבחירה” and the keep notice with „חזרה”; test mode's export; and the network
 * drive's port, domain and folder fields.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class H5PowerUsersTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private var testApp: TestApp? = null

    @After
    fun tearDown() {
        testApp?.close()
    }

    private val root = "/storage/emulated/0"
    private val utc = ZoneOffset.UTC
    private val now = LocalDate.of(2026, 11, 1).atStartOfDay(utc).toInstant().toEpochMilli()

    private fun millis(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).atTime(12, 0).toInstant(utc).toEpochMilli()

    private fun shot(name: String, size: Long, mtime: Long): ClassifiedItem {
        val path = "$root/Pictures/Screenshots/$name.png"
        return Classifier().classify(ScanItem(KPath.Local(path), path, size, mtime), now = now)
    }

    /** 30 screenshots over three years; sizes all differ (the largest are spread across years). */
    private val items: List<ClassifiedItem> = (0 until 30).map { i ->
        val year = 2024 + i % 3
        shot("Screenshot_$i", size = 1_000L + (i * 37 % 30) * 1_000L, mtime = millis(year, 1 + i % 12, 1 + i))
    }
    private val group get() = ClassificationSummary.of(items).group("screenshots", Category.REVIEW)!!

    // ------------------------------------------------------------------------------------- group tools (logic)

    @Test
    fun `newest first is the group as before, oldest reverses it, largest is one list by size`() {
        val g = group
        val newest = GroupTools.sections(g, GroupView(), utc)
        assertEquals(g.buckets.map { it.first }, newest.map { it.bucket })
        assertEquals(g.items, GroupTools.visible(newest))

        val oldest = GroupTools.visible(GroupTools.sections(g, GroupView(GroupSort.OLDEST), utc))
        assertEquals(g.items.sortedBy { it.item.mtimeMillis }, oldest)

        val largest = GroupTools.sections(g, GroupView(GroupSort.LARGEST), utc)
        assertEquals(1, largest.size)
        assertFalse(largest.single().byMonth)
        assertEquals(g.items.map { it.item.size }.sortedDescending(), largest.single().items.map { it.item.size })
    }

    @Test
    fun `a year and a search show only what matches, in every order`() {
        val g = group
        assertEquals(listOf(2026, 2025, 2024), GroupTools.years(g.items, utc))
        for (sort in GroupSort.entries) {
            val in2025 = GroupTools.visible(GroupTools.sections(g, GroupView(sort, year = 2025), utc))
            assertEquals(10, in2025.size)
            assertTrue(in2025.all { java.time.Instant.ofEpochMilli(it.item.mtimeMillis).atZone(utc).year == 2025 })
        }
        val found = GroupTools.visible(GroupTools.sections(g, GroupView(query = "Screenshot_12"), utc))
        assertEquals(listOf("$root/Pictures/Screenshots/Screenshot_12.png"), found.map { it.item.path })
        assertTrue(GroupView(year = 2025).filtered)
        assertFalse(GroupView(GroupSort.LARGEST).filtered)
    }

    @Test
    fun `the N largest come only from what is shown`() {
        val g = group
        val shown = GroupTools.visible(GroupTools.sections(g, GroupView(year = 2024), utc))
        val picked = GroupTools.largest(shown, 3)
        assertEquals(shown.sortedByDescending { it.item.size }.take(3).map { it.item.path }.toSet(), picked)
        // Nothing hidden by the filter is ever picked.
        assertTrue(picked.all { p -> shown.any { it.item.path == p } })
        assertEquals(listOf(10, 25), GroupTools.choices(30))
        assertEquals(emptyList<Int>(), GroupTools.choices(10))
    }

    @Test
    fun `duplicates keep their last copy when the largest are picked (SAFETY-8)`(): Unit = runBlocking {
        // Two WhatsApp Sent copies of one camera photo: the copies are offered, the camera photo is not.
        val g = FakeStorageGateway(caseInsensitive = true)
        val bytes = ByteArray(3_000) { 6 }
        val sent = "$root/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/Sent/IMG_7.jpg"
        val sent2 = "$root/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/Sent/IMG_7 (1).jpg"
        val camera = "$root/DCIM/Camera/IMG_7.jpg"
        listOf(sent, sent2, camera).forEach { g.addFile(it, bytes) }
        val scanned = Scanner(g).scan(listOf(KPath.Local(root))).filterIsInstance<ScanEvent.Batch>().toList()
            .flatMap { it.items }
        val e = FactEnricher(g).enrich(scanned)
        val classified = scanned.map { Classifier().classify(it, e.facts[it.path].orEmpty(), now) }
        val copies = ClassificationSummary.of(classified).group("wa_sent_copy", Category.SAFE)!!
        val shown = GroupTools.visible(GroupTools.sections(copies, GroupView(GroupSort.LARGEST), utc))
        val picked = GroupTools.largest(shown, 100)
        assertEquals(setOf(sent, sent2), picked)
        val plan = CleanPlanner.plan("p", classified.filter { it.item.path in picked }, e.evidence, "clean")
        assertEquals(SafetyGuard.Verdict.Accepted, SafetyGuard(ProtectedPaths()).check(plan.confirm(1, "s"), "s"))
        // Had the camera photo been picked too, the choke point would refuse the plan.
        val all = CleanPlanner.plan("q", classified, e.evidence, "clean")
        assertTrue(SafetyGuard(ProtectedPaths()).check(all.confirm(1, "s"), "s") is SafetyGuard.Verdict.Rejected)
    }

    // ------------------------------------------------------------------------------------- group tools (screen)

    private fun showGroup(onDelete: (List<ClassifiedItem>) -> Unit = {}) {
        compose.setContent {
            KeepClearTheme {
                GroupScreen(group, onOpenItem = {}, onBack = {}, onDelete = { list, _ -> onDelete(list) })
            }
        }
    }

    @Test
    fun `nothing is selected until the user taps, and the largest are picked only on that tap (SAFETY rule 7)`() {
        var deleted: List<ClassifiedItem>? = null
        showGroup { deleted = it }
        compose.onNodeWithTag("groupToolsPanel").assertDoesNotExist()
        compose.onNodeWithTag("grid").performScrollToNode(hasTestTag("groupTools"))
        compose.onNodeWithTag("groupTools").performClick()
        compose.onAllNodesWithTag("tileSelected").assertCountEquals(0)
        compose.onNodeWithTag("selectionBar").assertDoesNotExist()
        compose.onNodeWithTag("grid").performScrollToNode(hasTestTag("groupLargest"))
        compose.onNodeWithTag("groupLargest").performClick()
        compose.onAllNodesWithTag("tileSelected").assertCountEquals(0)
        compose.onNodeWithTag("largest_10").performClick()
        // The selection is exactly the 10 largest (count and size in the bar); nothing is deleted yet.
        assertNull(deleted)
        val expected = items.sortedByDescending { it.item.size }.take(10).fold(Total()) { t, i -> t + i }
        val size = io.github.tomerar.keepclear.core.common.ByteSize.format(expected.bytes, java.util.Locale.ENGLISH)
            .filter { it !in '\u2066'..'\u2069' }
        compose.onNodeWithTag("selectionCount", useUnmergedTree = true).assertTextContains(size, substring = true)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val count = context.resources.getQuantityString(
            io.github.tomerar.keepclear.core.i18n.R.plurals.file_count,
            10,
            "10",
        )
        compose.onNodeWithTag("selectionCount", useUnmergedTree = true).assertTextContains(count, substring = true)
        assertEquals(10, expected.files)
    }

    @Test
    fun `with a year shown, select all takes only what is shown, and the bar says what is hidden`() {
        showGroup()
        compose.onNodeWithTag("grid").performScrollToNode(hasTestTag("groupTools"))
        compose.onNodeWithTag("groupTools").performClick()
        compose.onNodeWithTag("grid").performScrollToNode(hasTestTag("year_2025"))
        compose.onNodeWithTag("year_2025").performClick()
        compose.onNodeWithTag("groupShown").assertExists()
        compose.onNodeWithTag("grid").performScrollToNode(hasTestTag("selectAll"))
        compose.onNodeWithTag("selectAll").performClick()
        compose.onNodeWithTag("selectionCount", useUnmergedTree = true).assertTextContains("10", substring = true)
        compose.onNodeWithTag("selectionHidden", useUnmergedTree = true).assertDoesNotExist()
        // Showing all again: the 10 stay selected, and nothing else was added.
        compose.onNodeWithTag("grid").performScrollToNode(hasTestTag("groupClearFilter"))
        compose.onNodeWithTag("groupClearFilter").performClick()
        compose.onNodeWithTag("selectionCount", useUnmergedTree = true).assertTextContains("10", substring = true)
        // Back to one year: 10 selected, none hidden; to another year: all 10 hidden.
        compose.onNodeWithTag("grid").performScrollToNode(hasTestTag("year_2024"))
        compose.onNodeWithTag("year_2024").performClick()
        compose.onNodeWithTag("selectionHidden", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("selectionClear").performClick()
        compose.onNodeWithTag("selectionBar").assertDoesNotExist()
    }

    @Test
    fun `search finds by name in the group`() {
        showGroup()
        compose.onNodeWithTag("grid").performScrollToNode(hasTestTag("groupTools"))
        compose.onNodeWithTag("groupTools").performClick()
        compose.onNodeWithTag("groupSearch").performTextInput("Screenshot_12")
        compose.onNodeWithTag("groupShown").assertTextContains("Showing", substring = true)
        compose.onNodeWithTag("groupShown").assertTextContains("30", substring = true)
        compose.onNodeWithTag("grid").performScrollToNode(hasTestTag("preview_Screenshot_12.png"))
        compose.onAllNodesWithTag("tile", useUnmergedTree = true).assertCountEquals(1)
    }

    // ------------------------------------------------------------------------------------- rules

    @Test
    fun `each rule says what it matched in the latest scan`() {
        val kept = "$root/Pictures/Screenshots"
        val junk = "$root/Download/Memes"
        val c = Classifier(keptByUser = listOf(kept), junkFolders = listOf(junk))
        val scanned = listOf(
            "$kept/a.png" to 1_000L,
            "$kept/b.png" to 2_000L,
            "$junk/x.jpg" to 500L,
            // In the junk folder, but "might be a memory": the rule doesn't take it (D6), so it isn't counted.
            "$junk/ChatGPT Image 1.png" to 7_000L,
            "$root/Download/other.zip" to 9_000L,
        ).map { (p, s) -> c.classify(ScanItem(KPath.Local(p), p, s, now - 90L * 86_400_000), now = now) }
        val rules = UserRules.of(
            Choices(kept = setOf(kept), junk = setOf(junk, "$root/Download/Gone")),
            ClassificationSummary.of(scanned),
        )
        assertEquals(
            listOf(
                UserRule(kept, UserRule.Kind.KEPT, Total(2, 3_000)),
                UserRule("$root/Download/Gone", UserRule.Kind.JUNK, Total()),
                UserRule(junk, UserRule.Kind.JUNK, Total(1, 500)),
            ),
            rules,
        )
        // Before any scan: "shown after the next scan".
        assertNull(UserRules.of(Choices(kept = setOf(kept)), null).single().matched)
    }

    @Test
    fun `the rules list cancels a rule with lebatel et habechira, not remove`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val kept = "$root/Pictures/Screenshots/a.png"
        val removed = mutableListOf<String>()
        compose.setContent {
            KeepClearTheme {
                SettingsScreen(
                    false,
                    {},
                    {},
                    {},
                    SettingsActions(choices = Choices(kept = setOf(kept)), onRemoveKept = { removed += it }),
                )
            }
        }
        compose.onNodeWithTag("settings").performScrollToNode(hasTestTag("removeKept_$kept"))
        compose.onNodeWithTag("removeKept_$kept")
            .assertTextContains(context.getString(io.github.tomerar.keepclear.core.i18n.R.string.rule_cancel))
        compose.onNodeWithTag("ruleMatched_$kept", useUnmergedTree = true).assertTextContains(
            context.getString(io.github.tomerar.keepclear.core.i18n.R.string.rule_matches_unknown),
        )
        compose.onNodeWithTag("removeKept_$kept").performClick()
        assertEquals(listOf(kept), removed)
    }

    @Test
    fun `cancelling a rule changes only the rule - the file stays where it is`() {
        val t = TestApp(tmp.newFolder("phone")).also { testApp = it }
        t.app.testMode.turnOff() // even with test mode off, nothing touches the file
        val file = t.file("Download/Tax/2023.zip", ByteArray(4_000) { 3 })
        // The rule is stored as the app stores it (a test phone folder isn't under /storage, so keep() refuses it).
        t.app.getSharedPreferences("user-choices", Context.MODE_PRIVATE).edit()
            .putStringSet("kept", setOf(file.absolutePath)).commit()
        val fresh = UserChoices(t.app.getSharedPreferences("user-choices", Context.MODE_PRIVATE))
        assertEquals(setOf(file.absolutePath), fresh.state.value.kept)
        val vm = t.viewModel(HomeViewModel::class.java)
        vm.removeKept(file.absolutePath)
        TestApp.settle(300)
        assertTrue(file.exists())
        assertEquals(4_000L, file.length())
        assertTrue(runBlocking { t.app.trashManager.entries() }.isEmpty())
        assertFalse(file.absolutePath in t.app.userChoices.state.value.kept)
        assertEquals(1, File(file.parentFile, "").list()!!.size)
    }

    @Test
    fun `after lo lehatzia et ze shuv a notice says so, and chazara cancels the rule`() {
        val t = TestApp(tmp.newFolder("phone")).also { testApp = it }
        val vm = t.viewModel(HomeViewModel::class.java)
        val path = "$root/Pictures/Screenshots/a.png"
        vm.keep(path)
        assertEquals(path, vm.state.value.keepNotice)
        assertTrue(path in t.app.userChoices.state.value.kept)
        vm.undoKeep(path)
        assertNull(vm.state.value.keepNotice)
        assertFalse(path in t.app.userChoices.state.value.kept)
        vm.keep(path)
        vm.dismissKeepNotice()
        assertNull(vm.state.value.keepNotice)
        assertTrue(path in t.app.userChoices.state.value.kept)
    }

    @Test
    fun `the keep notice shows with chazara and closes after the accessibility timeout`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val timeout = NoticeTimeout.millis(context)
        assertTrue("$timeout", timeout >= NoticeTimeout.BASE_MILLIS)
        var undone: String? = null
        var done = false
        val path = "$root/Pictures/Screenshots/a.png"
        compose.mainClock.autoAdvance = false
        compose.setContent {
            KeepClearTheme {
                AppScreens(
                    state = HomeUiState(keepNotice = path.takeIf { !done }),
                    onOpen = {},
                    onBack = {},
                    home = {},
                    previewActions = PreviewActions(onUndoKeep = { undone = it }, onKeepNoticeDone = { done = true }),
                )
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("keepNotice").assertExists()
        compose.onNodeWithText(context.getString(io.github.tomerar.keepclear.core.i18n.R.string.notice_undo))
            .assertExists()
        compose.mainClock.advanceTimeBy(NoticeTimeout.BASE_MILLIS - 3_000L)
        assertFalse(done)
        compose.mainClock.advanceTimeBy(timeout)
        assertTrue(done)
        assertNull(undone)
    }

    @Test
    fun `chazara on the notice cancels that rule`() {
        var undone: String? = null
        val path = "$root/Pictures/Screenshots/a.png"
        compose.setContent {
            KeepClearTheme {
                AppScreens(
                    state = HomeUiState(keepNotice = path),
                    onOpen = {},
                    onBack = {},
                    home = {},
                    previewActions = PreviewActions(onUndoKeep = { undone = it }),
                )
            }
        }
        compose.onNodeWithTag("keepNoticeUndo").performClick()
        assertEquals(path, undone)
    }

    // ------------------------------------------------------------------------------------- test mode export

    @Test
    fun `the export has every would-be deletion, with untrusted names made safe for a spreadsheet`() {
        val rows = listOf(
            WouldDelete("$root/Download/a.tmp", 1_000, forGood = true),
            WouldDelete("$root/Pictures/Screenshots/=HYPERLINK(\"x\").png", 2_000, forGood = false),
            WouldDelete("=cmd|' /C calc'!A0", 3, forGood = false),
            WouldDelete("$root/Download/two,\nlines.txt", 4, forGood = false),
        )
        assertEquals(
            "path,size_bytes,would\r\n" +
                "$root/Download/a.tmp,1000,delete_for_good\r\n" +
                "\"$root/Pictures/Screenshots/=HYPERLINK(\"\"x\"\").png\",2000,trash\r\n" +
                "'=cmd|' /C calc'!A0,3,trash\r\n" +
                "\"$root/Download/two, lines.txt\",4,trash\r\n",
            TestExport.csv(rows),
        )
        listOf("+1", "-1", "@x", "\tx").forEach { assertTrue(it, TestExport.cell(it).startsWith("'")) }
        assertEquals("keepclear-test-mode-2026-11-01.csv", TestExport.fileName(now, utc))
        val ops = listOf(
            PlannedOp(KPath.Local("$root/a"), "$root/a", OpKind.DELETE_FOR_GOOD, false, 5, 1),
            PlannedOp(KPath.Local("$root/b"), "$root/b", OpKind.TRASH, false, 6, 1),
        )
        assertEquals(
            listOf(WouldDelete("$root/a", 5, true), WouldDelete("$root/b", 6, false)),
            TestExport.rows(ops),
        )
    }

    @Test
    fun `the export is offered only for a test-mode run`() {
        val rows = listOf(WouldDelete("$root/a", 1, false))
        val simulated = CleanResult(Total(1, 1), 0, 0, emptyList(), simulated = true, wouldDelete = rows)
        assertTrue(TestExport.offered(simulated, testModeOn = true))
        assertFalse(TestExport.offered(simulated, testModeOn = false))
        assertFalse(TestExport.offered(simulated.copy(simulated = false), testModeOn = true))
        assertFalse(TestExport.offered(simulated.copy(wouldDelete = emptyList()), testModeOn = true))
    }

    private fun showBanner(r: CleanResult, testModeOn: Boolean, exported: MutableList<List<WouldDelete>>) {
        compose.setContent {
            KeepClearTheme {
                CompositionLocalProvider(
                    LocalTestExporter provides TestExporter(testModeOn = { testModeOn }) { exported += it },
                ) { CleanResultBanner(r, { _, _ -> }, {}) }
            }
        }
    }

    @Test
    fun `the banner warns that the list has file names before anything is saved`() {
        val rows = listOf(WouldDelete("$root/a", 1, false))
        val exported = mutableListOf<List<WouldDelete>>()
        showBanner(
            CleanResult(Total(1, 1), 0, 0, emptyList(), simulated = true, wouldDelete = rows),
            testModeOn = true,
            exported,
        )
        compose.onNodeWithTag("testExport").performClick()
        compose.onNodeWithTag("testExportSheet").assertExists()
        assertTrue(exported.isEmpty())
        compose.onNodeWithTag("testExportChoose").performClick()
        assertEquals(listOf(rows), exported)
    }

    @Test
    fun `no export once test mode is off, nor after a real clean`() {
        val rows = listOf(WouldDelete("$root/a", 1, false))
        val exported = mutableListOf<List<WouldDelete>>()
        showBanner(
            CleanResult(Total(1, 1), 0, 0, emptyList(), simulated = true, wouldDelete = rows),
            testModeOn = false,
            exported,
        )
        compose.onNodeWithTag("testExport", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `a test-mode clean keeps its would-delete list, a real one doesn't`() {
        val t = TestApp(tmp.newFolder("phone")).also { testApp = it }
        assertTrue(t.app.testMode.isOn)
        val f = t.file("Download/Old/x.tmp", ByteArray(700) { 1 })
        val p = f.absolutePath
        val item = runBlocking {
            val s = t.app.localGateway.stat(KPath.Local(p))!!
            Classifier().classify(ScanItem(s.path, p, s.size, s.mtimeMillis, identity = s.identity), now = now)
        }
        val vm = t.viewModel(HomeViewModel::class.java)
        vm.clean(listOf(item), userConfirmed(listOf(p)))
        TestApp.awaitMain("simulated") { vm.state.value.cleanResult != null && !vm.state.value.busy }
        val r = vm.state.value.cleanResult!!
        assertTrue(r.simulated)
        assertEquals(listOf(p), r.wouldDelete.map { it.path })
        assertEquals(700L, r.wouldDelete.single().bytes)
        assertTrue(f.exists())
    }

    // ------------------------------------------------------------------------------------- network drive fields

    @Test
    fun `the network form sends port, domain and folder, with hints`() {
        var connected: SmbLocation? = null
        compose.setContent {
            KeepClearTheme {
                NetworkDriveDialog(saved = null, onConnect = {
                    connected = it
                }, onDismiss = {}, testAccountNotice = false)
            }
        }
        compose.onNodeWithTag("networkHost").performTextInput("192.168.1.20")
        compose.onNodeWithTag("networkShare").performTextInput("photos")
        compose.onNodeWithTag("networkPort").performTextInput("4455")
        compose.onNodeWithTag("networkFolder").performTextInput("Backups/Phone/")
        compose.onNodeWithTag("networkUser").performTextInput("me")
        compose.onNodeWithTag("networkDomain").performTextInput("WORKGROUP")
        compose.onNodeWithTag("networkConnect").performClick()
        val c = connected!!
        assertEquals(4455, c.port)
        assertEquals("Backups/Phone", c.folder)
        assertEquals("WORKGROUP", c.domain)
        assertEquals(io.github.tomerar.keepclear.backup.net.HostRule.NetworkOnly, c.hosts)
    }

    @Test
    fun `the network form refuses a bad port or a folder that leaves the share`() {
        var connected: SmbLocation? = null
        compose.setContent {
            KeepClearTheme {
                NetworkDriveDialog(saved = null, onConnect = {
                    connected = it
                }, onDismiss = {}, testAccountNotice = false)
            }
        }
        compose.onNodeWithTag("networkHost").performTextInput("nas")
        compose.onNodeWithTag("networkShare").performTextInput("photos")
        compose.onNodeWithTag("networkPort").performTextInput("70000")
        compose.onNodeWithTag("networkConnect").performClick()
        assertNull(connected)
        compose.onNodeWithTag("networkFolder").performTextInput("../up")
        compose.onNodeWithTag("networkConnect").performClick()
        assertNull(connected)
    }

    @Test
    fun `an empty port is 445 and the saved folder and domain come back`() {
        var connected: SmbLocation? = null
        val saved =
            SmbLocation("nas", "photos", folder = "Phone", user = "me", password = "pw", domain = "HOME", port = 445)
        compose.setContent {
            KeepClearTheme {
                NetworkDriveDialog(saved, onConnect = { connected = it }, onDismiss = {}, testAccountNotice = false)
            }
        }
        compose.onNodeWithTag("networkConnect").performClick()
        assertEquals(saved, connected)
    }
}
