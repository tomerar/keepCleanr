// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.classify

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** M13: usage bucket math with month precision. */
class AppsReportTest {
    private val zone = ZoneId.of("Asia/Jerusalem")

    private fun at(y: Int, m: Int, d: Int) = LocalDateTime.of(y, m, d, 12, 0).atZone(zone).toInstant().toEpochMilli()

    private val now = at(2026, 11, 1)

    private fun app(pkg: String, size: Long, lastUsed: Long?, installed: Long = at(2024, 1, 1), cache: Long = 0) =
        AppInfo(pkg, pkg, size, 0, cache, lastUsed, installed)

    @Test
    fun `months are calendar months, table-driven`() {
        listOf(
            at(2026, 10, 31) to 1L, // last day of last month: 1 month ago
            at(2026, 11, 1) to 0L,
            at(2026, 5, 1) to 6L,
            at(2026, 5, 31) to 6L,
            at(2026, 6, 1) to 5L,
            at(2025, 11, 30) to 12L,
        ).forEach { (t, months) -> AppsReport.monthsSince(t, now, zone) shouldBe months }
    }

    @Test
    fun `unused means not opened for 6 calendar months, never a recently installed app`() {
        AppsReport.isUnused(app("a", 1, at(2026, 5, 15)), now, zone) shouldBe true
        AppsReport.isUnused(app("b", 1, at(2026, 6, 1)), now, zone) shouldBe false
        // No usage record at all: unused only if installed long ago.
        AppsReport.isUnused(app("c", 1, null), now, zone) shouldBe true
        AppsReport.isUnused(app("d", 1, null, installed = at(2026, 9, 1)), now, zone) shouldBe false
    }

    @Test
    fun `lists are sorted by size and by cache`() {
        val apps = listOf(app("small", 1, now, cache = 900), app("big", 9, at(2025, 1, 1), cache = 5))
        AppsReport.bySize(apps).map { it.pkg } shouldBe listOf("big", "small")
        AppsReport.byCache(apps).map { it.pkg } shouldBe listOf("small", "big")
        AppsReport.unused(apps, now, zone).map { it.pkg } shouldBe listOf("big")
    }
}
