package com.puretone.player.domain.report

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [ReportPeriod] 周期边界纯函数单测（需求4 阶段1）。
 *
 * 覆盖：本地时区日期键、周一始完整周、闰年二月、跨年月份、完整年、尾部窗口、
 * 相邻周期首尾严丝合缝、count 入参校验。固定 Asia/Shanghai 时区，规避 CI 机器时区漂移。
 */
class ReportPeriodTest {

    private val shanghai = ZoneId.of("Asia/Shanghai")

    /** 把 "2024-01-15T10:00:00+08:00" 这种 ISO 串转成 epoch 毫秒 */
    private fun at(isoWithOffset: String): Long =
        ZonedDateTime.parse(isoWithOffset).toInstant().toEpochMilli()

    @Test
    fun `dayKeyFromEpoch 按本地时区算日期键`() {
        val epoch = at("2024-01-15T10:00:00+08:00")
        assertEquals("2024-01-15", ReportPeriod.dayKeyFromEpoch(epoch, shanghai))
        // 跨日边界：UTC 已是 1/16 02:30，但上海仍是 1/16
        val lateNight = at("2024-01-16T02:30:00+08:00")
        assertEquals("2024-01-16", ReportPeriod.dayKeyFromEpoch(lateNight, shanghai))
    }

    @Test
    fun `lastCompletedWeeks 返回已结束完整周（周一始）`() {
        // 2024-01-15 是周一；本周一到下周一前的那一周即「最近完整周」
        val now = at("2024-01-15T10:00:00+08:00")
        val weeks = ReportPeriod.lastCompletedWeeks(1, now, shanghai)
        assertEquals(1, weeks.size)
        val w = weeks.first()
        val start = Instant.ofEpochMilli(w.start).atZone(shanghai)
        assertEquals(LocalDate.of(2024, 1, 8), start.toLocalDate())
        assertEquals(0, start.hour); assertEquals(0, start.minute); assertEquals(0, start.second)
        val end = Instant.ofEpochMilli(w.end).atZone(shanghai)
        assertEquals(LocalDate.of(2024, 1, 14), end.toLocalDate())
        assertEquals(23, end.hour); assertEquals(59, end.minute); assertEquals(59, end.second)
        assertEquals("2024-01-08", w.label)
    }

    @Test
    fun `lastCompletedWeeks 多周按时间倒序且首尾严丝合缝`() {
        val now = at("2024-01-15T10:00:00+08:00")
        val weeks = ReportPeriod.lastCompletedWeeks(3, now, shanghai)
        assertEquals(3, weeks.size)
        assertEquals("2024-01-08", weeks[0].label)
        assertEquals("2024-01-01", weeks[1].label)
        assertEquals("2023-12-25", weeks[2].label)
        // 上一段 start 恰等于下一段 end + 1ms（无重叠、无空洞）
        assertEquals(weeks[0].start, weeks[1].end + 1)
        assertEquals(weeks[1].start, weeks[2].end + 1)
    }

    @Test
    fun `lastCompletedMonths 闰年二月正确收尾`() {
        val now = at("2024-03-15T12:00:00+08:00") // 2024 闰年，2 月 29 天
        val months = ReportPeriod.lastCompletedMonths(1, now, shanghai)
        assertEquals(1, months.size)
        val feb = months.first()
        val end = Instant.ofEpochMilli(feb.end).atZone(shanghai)
        assertEquals(LocalDate.of(2024, 2, 29), end.toLocalDate())
        assertEquals(23, end.hour)
        assertEquals("2024-02-01", feb.label)
    }

    @Test
    fun `lastCompletedMonths 跨年与多个月`() {
        val now = at("2024-01-15T12:00:00+08:00")
        val months = ReportPeriod.lastCompletedMonths(2, now, shanghai)
        assertEquals(2, months.size)
        assertEquals("2023-12-01", months[0].label)
        assertEquals("2023-11-01", months[1].label)
        assertEquals(months[0].start, months[1].end + 1)
    }

    @Test
    fun `lastCompletedYears 返回已结束完整年`() {
        val now = at("2024-06-01T12:00:00+08:00")
        val years = ReportPeriod.lastCompletedYears(1, now, shanghai)
        assertEquals(1, years.size)
        val y = years.first()
        val start = Instant.ofEpochMilli(y.start).atZone(shanghai)
        val end = Instant.ofEpochMilli(y.end).atZone(shanghai)
        assertEquals(LocalDate.of(2023, 1, 1), start.toLocalDate())
        assertEquals(LocalDate.of(2023, 12, 31), end.toLocalDate())
        assertEquals(23, end.hour)
        assertEquals("2023-01-01", y.label)
    }

    @Test
    fun `trailingWindow 按天数回推且为闭区间`() {
        val now = at("2024-06-01T08:00:00+08:00")
        val win = ReportPeriod.trailingWindow(400, now, shanghai)
        val expectedStart = now - 400L * 24 * 60 * 60 * 1000
        assertEquals(expectedStart, win.start)
        assertEquals(now, win.end)
        assertTrue(win.contains(now))
        assertTrue(win.contains(expectedStart))
        assertFalse("起点前一毫秒不应包含", win.contains(expectedStart - 1))
    }

    @Test
    fun `count 必须为正否则抛 IllegalArgumentException`() {
        val now = at("2024-01-15T10:00:00+08:00")
        try {
            ReportPeriod.lastCompletedWeeks(0, now, shanghai)
            fail("应抛出 IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        try {
            ReportPeriod.lastCompletedMonths(-1, now, shanghai)
            fail("应抛出 IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }
}
