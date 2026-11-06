package com.puretone.player.domain.report

import com.puretone.player.data.local.dao.DailyTrendRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * [ReportTrend] 分桶纯函数单测（需求4 阶段3，方案 5 阶段3 验收项）。
 *
 * 覆盖：周报补齐 7 日柱、月报 7 天窗口切 W1…W5（31 天月末 / 28 天平月 / 29 天闰年二月）、
 * 年报固定 12 月柱、相邻周期数据不串入、空数据全 0。
 * 固定 Asia/Shanghai 时区，规避 CI 机器时区漂移（沿用 [ReportPeriodTest] 的做法）。
 */
class ReportTrendTest {

    private val shanghai = ZoneId.of("Asia/Shanghai")

    /** 构造一份只含趋势数据的报告；[endExclusive] 为下一周期首日 00:00 */
    private fun report(
        type: PeriodType,
        start: LocalDate,
        endExclusive: LocalDate,
        trend: List<DailyTrendRow>
    ): ReportData = ReportData(
        type = type,
        rangeStart = start.atStartOfDay(shanghai).toInstant().toEpochMilli(),
        rangeEnd = endExclusive.atStartOfDay(shanghai).toInstant().toEpochMilli() - 1,
        listenMs = trend.sumOf { it.listenMs },
        hasEstimate = false,
        plays = trend.sumOf { it.plays },
        completed = 0,
        distinctSongs = 0,
        activeDays = trend.size,
        dailyTrend = trend,
        topSongs = emptyList(),
        topArtists = emptyList(),
        hourDistribution = emptyList(),
        prevListenMs = 0L,
        deltaPct = 0.0
    )

    private fun row(day: String, listenMs: Long): DailyTrendRow =
        DailyTrendRow(day, listenMs, 1L)

    private fun buckets(
        type: PeriodType,
        start: String,
        endExclusive: String,
        trend: List<DailyTrendRow> = emptyList()
    ) = ReportTrend.buckets(
        report(type, LocalDate.parse(start), LocalDate.parse(endExclusive), trend),
        shanghai
    )

    @Test
    fun `周报固定 7 根且没听歌的日子补 0`() {
        val b = buckets(
            PeriodType.WEEK, "2026-11-02", "2026-11-09",
            listOf(row("2026-11-02", 100), row("2026-11-04", 300), row("2026-11-08", 50))
        )
        assertEquals(7, b.size)
        assertEquals(listOf(100L, 0L, 300L, 0L, 0L, 0L, 50L), b.map { it.listenMs })
        assertEquals(450L, b.sumOf { it.listenMs })
    }

    @Test
    fun `周报 7 根标签是互异的本地化短星期`() {
        val b = buckets(PeriodType.WEEK, "2026-11-02", "2026-11-09")
        assertEquals(7, b.map { it.label }.toSet().size)
        assertTrue(b.all { it.label.isNotBlank() })
    }

    @Test
    fun `月报 31 天切成 W1 到 W5 且末桶只含月末三天`() {
        val b = buckets(
            PeriodType.MONTH, "2026-08-01", "2026-09-01",
            listOf(
                row("2026-08-01", 10), row("2026-08-07", 5), // W1: 1-7 日
                row("2026-08-08", 20),                        // W2: 8-14 日
                row("2026-08-15", 30),                        // W3: 15-21 日
                row("2026-08-22", 40),                        // W4: 22-28 日
                row("2026-08-29", 1), row("2026-08-31", 2)    // W5: 29-31 日
            )
        )
        assertEquals(5, b.size)
        assertEquals(listOf("W1", "W2", "W3", "W4", "W5"), b.map { it.label })
        assertEquals(listOf(15L, 20L, 30L, 40L, 3L), b.map { it.listenMs })
    }

    @Test
    fun `月报平年二月 28 天恰好 4 桶`() {
        val b = buckets(PeriodType.MONTH, "2026-02-01", "2026-03-01")
        assertEquals(4, b.size)
        assertTrue(b.all { it.listenMs == 0L })
    }

    @Test
    fun `月报闰年二月 29 天为 5 桶且 W5 仅含 29 日`() {
        val b = buckets(
            PeriodType.MONTH, "2028-02-01", "2028-03-01",
            listOf(row("2028-02-01", 9), row("2028-02-29", 7))
        )
        assertEquals(5, b.size)
        assertEquals(listOf(9L, 0L, 0L, 0L, 7L), b.map { it.listenMs })
    }

    @Test
    fun `月报不串入相邻月份的数据`() {
        val b = buckets(
            PeriodType.MONTH, "2026-11-01", "2026-12-01",
            listOf(row("2026-10-31", 999), row("2026-12-01", 888))
        )
        assertEquals(5, b.size)
        assertTrue("越界数据不应计入月报桶", b.all { it.listenMs == 0L })
    }

    @Test
    fun `年报固定 12 根且没听歌的月份补 0`() {
        val b = buckets(
            PeriodType.YEAR, "2026-01-01", "2027-01-01",
            listOf(row("2026-01-05", 100), row("2026-12-31", 200))
        )
        assertEquals(12, b.size)
        assertEquals(100L, b[0].listenMs)
        assertEquals(200L, b[11].listenMs)
        assertEquals(0L, b[5].listenMs)
        assertTrue(b.all { it.label.isNotBlank() })
    }

    @Test
    fun `年报不串入前后相邻年份的数据`() {
        val b = buckets(
            PeriodType.YEAR, "2026-01-01", "2027-01-01",
            listOf(
                row("2025-12-31", 999), row("2026-03-02", 60), row("2027-01-01", 888)
            )
        )
        assertEquals(12, b.size)
        assertEquals(60L, b.sumOf { it.listenMs })
        assertEquals(60L, b[2].listenMs)
    }

    @Test
    fun `三周期空数据的桶数固定且全为 0`() {
        assertEquals(7, buckets(PeriodType.WEEK, "2026-11-02", "2026-11-09").size)
        assertEquals(5, buckets(PeriodType.MONTH, "2026-11-01", "2026-12-01").size)
        assertEquals(12, buckets(PeriodType.YEAR, "2026-01-01", "2027-01-01").size)
        listOf(
            buckets(PeriodType.WEEK, "2026-11-02", "2026-11-09"),
            buckets(PeriodType.MONTH, "2026-11-01", "2026-12-01"),
            buckets(PeriodType.YEAR, "2026-01-01", "2027-01-01")
        ).forEach { assertTrue(it.all { bucket -> bucket.listenMs == 0L }) }
    }
}
