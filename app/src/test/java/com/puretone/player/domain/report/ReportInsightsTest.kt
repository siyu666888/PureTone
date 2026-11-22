package com.puretone.player.domain.report

import com.puretone.player.data.local.dao.DailyTrendRow
import com.puretone.player.data.local.dao.HourCountRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * 报告 V2 派生指标单测（纯 JVM）。
 *
 * 锁定 ReportInsights 的边界行为：时段四段覆盖全天、深夜占比、日均除零、最长连续
 * 断档清零、绝对差可为负、日历热力整月铺满且缺日补 0。这些都是海报/屏幕共用口径，
 * 改坏任一条都会让 V2 图表读错数据。
 */
class ReportInsightsTest {

    private fun data(
        type: PeriodType = PeriodType.WEEK,
        listenMs: Long = 100L,
        activeDays: Int = 2,
        prevListenMs: Long = 40L,
        rangeStart: Long = 0L,
        trend: List<DailyTrendRow> = emptyList(),
        hours: List<HourCountRow> = emptyList()
    ): ReportData = ReportData(
        type = type,
        rangeStart = rangeStart, rangeEnd = 0L,
        listenMs = listenMs, hasEstimate = false,
        plays = 10L, completed = 8L,
        distinctSongs = 3, activeDays = activeDays,
        dailyTrend = trend, topSongs = emptyList(), topArtists = emptyList(),
        hourDistribution = hours,
        prevListenMs = prevListenMs, deltaPct = 0.0
    )

    @Test
    fun `时段四段覆盖全天24小时且占比合计为一`() {
        // 每段各放 1 次播放，合计 4 → 每段占比 0.25
        val d = data(hours = listOf(HourCountRow(7, 1L), HourCountRow(13, 1L), HourCountRow(19, 1L), HourCountRow(1, 1L)))
        val segs = ReportInsights.timeSegments(d)
        assertEquals(4, segs.size)
        assertEquals(listOf(1, 2, 3, 4), segs.map { it.level })
        assertEquals("清晨", segs[0].label)
        assertEquals("深夜", segs[3].label)
        assertEquals(1f, segs.sumOf { it.share.toDouble() }.toFloat(), 0.001f)
    }

    @Test
    fun `无时段数据时四段占比全零不抛异常`() {
        val segs = ReportInsights.timeSegments(data())
        assertEquals(4, segs.size)
        assertTrue(segs.all { it.share == 0f })
        assertEquals(0, ReportInsights.nightSharePct(data()))
    }

    @Test
    fun `深夜占比按23点与0到4点六小时档统计并封顶100`() {
        // B5 口径收敛：深夜档 = {23,0,1,2,3,4}（与 isNightOwl / 时段档同源）
        // 23/0/1 各 2 次 = 6，白天 14 次 → 6/20 = 30%
        val d = data(hours = listOf(HourCountRow(23, 2L), HourCountRow(0, 2L), HourCountRow(1, 2L), HourCountRow(12, 14L)))
        assertEquals(30, ReportInsights.nightSharePct(d))
        // 全在 4 点（属深夜档）→ 100
        assertEquals(100, ReportInsights.nightSharePct(data(hours = listOf(HourCountRow(4, 9L)))))
        // 5 点（清晨档，不属深夜）→ 0
        assertEquals(0, ReportInsights.nightSharePct(data(hours = listOf(HourCountRow(5, 9L)))))
    }

    @Test
    fun `B5 isNightOwl与深夜占比同源且边界小时一致`() {
        // 峰值落在 4 点（深夜档末小时）→ isNightOwl 为真
        val data4 = data(hours = listOf(HourCountRow(4, 9L), HourCountRow(12, 1L)))
        assertTrue("peakHour=4 应判夜猫子", data4.isNightOwl)
        assertEquals("深夜 9 / 总 10 = 90%", 90, ReportInsights.nightSharePct(data4))
        // 峰值落在 5 点（清晨档）→ 非夜猫子
        assertFalse("peakHour=5 不应判夜猫子", data(hours = listOf(HourCountRow(5, 9L))).isNightOwl)
        // 权威集合含 23 点、不含 5 点
        assertTrue(23 in ReportData.NIGHT_HOURS)
        assertFalse(5 in ReportData.NIGHT_HOURS)
    }

    @Test
    fun `日均听歌除以活跃天数且活跃天数为零返回零`() {
        assertEquals(50L, ReportInsights.dailyAverageMs(data(listenMs = 100L, activeDays = 2)))
        assertEquals(0L, ReportInsights.dailyAverageMs(data(listenMs = 100L, activeDays = 0)))
    }

    @Test
    fun `最长连续按日期断档清零且取最大段`() {
        val dates = listOf("2026-11-02", "2026-11-03", "2026-11-04", "2026-11-08", "2026-11-09")
        val trend = dates.map { DailyTrendRow(it, 100L, 1L) }
        assertEquals(3, ReportInsights.longestStreak(data(trend = trend)))
        // 全断档 → 每段 1，最长 1
        val broken = listOf(DailyTrendRow("2026-11-01", 1L, 1L), DailyTrendRow("2026-11-10", 1L, 1L))
        assertEquals(1, ReportInsights.longestStreak(data(trend = broken)))
        assertEquals(0, ReportInsights.longestStreak(data()))
    }

    @Test
    fun `最长连续忽略零播放的日期`() {
        val trend = listOf(
            DailyTrendRow("2026-11-02", 100L, 1L),
            DailyTrendRow("2026-11-03", 0L, 0L),
            DailyTrendRow("2026-11-04", 100L, 1L)
        )
        // 11-03 播放为 0 被过滤，02 与 04 不连续 → 最长 1
        assertEquals(1, ReportInsights.longestStreak(data(trend = trend)))
    }

    @Test
    fun `绝对差可正可负且上周期为零时返回零`() {
        assertEquals(60L, ReportInsights.deltaAbsoluteMs(data(listenMs = 100L, prevListenMs = 40L)))
        assertEquals("up", ReportInsights.deltaDirection(data(listenMs = 100L, prevListenMs = 40L)))
        assertEquals(-60L, ReportInsights.deltaAbsoluteMs(data(listenMs = 40L, prevListenMs = 100L)))
        assertEquals("down", ReportInsights.deltaDirection(data(listenMs = 40L, prevListenMs = 100L)))
        assertEquals(60L, ReportInsights.absDeltaMs(data(listenMs = 40L, prevListenMs = 100L)))
        assertEquals(0L, ReportInsights.deltaAbsoluteMs(data(listenMs = 100L, prevListenMs = 0L)))
        assertEquals("flat", ReportInsights.deltaDirection(data(listenMs = 100L, prevListenMs = 100L)))
    }

    @Test
    fun `日历热力只在月报铺满整月且缺日补零`() {
        val zone = ZoneId.systemDefault()
        val start = LocalDate.of(2026, 11, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val m = data(
            type = PeriodType.MONTH,
            rangeStart = start,
            trend = listOf(DailyTrendRow("2026-11-01", 100L, 1L), DailyTrendRow("2026-11-15", 50L, 1L))
        )
        val cells = ReportInsights.calendarCells(m, zone)
        assertEquals(30, cells.size) // 11 月 30 天
        assertEquals(1, cells.first().day)
        assertEquals(30, cells.last().day)
        assertEquals(4, cells.first().level) // 峰值日
        assertEquals(2, cells[14].level) // 50/100 = 0.5 → level 2
        assertTrue(cells.filter { it.day !in setOf(1, 15) }.all { it.level == 0 })
        // 周报/年报不铺热力
        assertTrue(ReportInsights.calendarCells(data(PeriodType.WEEK), zone).isEmpty())
        assertTrue(ReportInsights.calendarCells(data(PeriodType.YEAR), zone).isEmpty())
    }

    @Test
    fun `日历热力对闰年二月铺29格`() {
        val zone = ZoneId.systemDefault()
        val start = LocalDate.of(2024, 2, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val cells = ReportInsights.calendarCells(
            data(PeriodType.MONTH, rangeStart = start), zone
        )
        assertEquals(29, cells.size)
    }

    @Test
    fun `B4 peakSegmentLabel 取占比最高时段且全零有确定口径`() {
        // 深夜 23 点占绝对多数 → 标签为「深夜」
        val night = data(hours = listOf(HourCountRow(23, 9L), HourCountRow(12, 1L)))
        assertEquals("深夜", ReportInsights.peakSegmentLabel(night))
        // 白天主场
        val day = data(hours = listOf(HourCountRow(12, 9L), HourCountRow(23, 1L)))
        assertEquals("午后", ReportInsights.peakSegmentLabel(day)) // hour=12 属午后段(11..16)
        // 全零：timeSegments 四段全 0，maxByOrNull 取首段「清晨」，锁定该确定口径
        assertEquals("清晨", ReportInsights.peakSegmentLabel(data()))
    }

    @Test
    fun `B7 日历热力四档边界0_25与0_75精确落档`() {
        val zone = ZoneId.systemDefault()
        val start = LocalDate.of(2026, 11, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        // 峰值 400：100/400=0.25→档1；300/400=0.75→档3；400→档4
        val trend = listOf(
            DailyTrendRow("2026-11-01", 100L, 1L),
            DailyTrendRow("2026-11-02", 300L, 1L),
            DailyTrendRow("2026-11-03", 400L, 1L)
        )
        val cells = ReportInsights.calendarCells(
            data(PeriodType.MONTH, rangeStart = start, trend = trend), zone
        )
        assertEquals(1, cells[0].level) // 0.25 → 1
        assertEquals(3, cells[1].level) // 0.75 → 3
        assertEquals(4, cells[2].level) // 峰值 → 4
    }

    @Test
    fun `B7 nightSharePct 封顶100不越界`() {
        val allNight = data(hours = listOf(HourCountRow(23, 100L), HourCountRow(0, 100L)))
        assertEquals(100, ReportInsights.nightSharePct(allNight))
    }
}
