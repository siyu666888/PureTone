package com.puretone.player.domain.grouping

import com.puretone.player.data.local.dao.DailyTrendRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 统计趋势纯函数单测（domain 层，无需数据库/上下文）。
 *
 * 趋势补全算法从 [com.puretone.player.ui.StatsViewModel] 抽到
 * [com.puretone.player.domain.grouping]，此处锁死其不可变口径：
 * - 稀疏每日记录补全为连续 N 天序列，缺失日 listenMs/plays=0；
 * - 自 sinceMs 当天 0 点起、直到今天（含）；
 * - dayKey 格式 "YYYY-MM-DD"；
 * - 上限 400 天兜底，不会无限生成。
 */
class TrendBuilderTest {

    /** 生成指向「今天 - offset 天」0 点的时间戳 */
    private fun dayStartMinus(offsetDays: Int): Long {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_MONTH, -offsetDays)
        }
        return cal.timeInMillis
    }

    @Test
    fun `buildFullTrend 缺失日补 0，且长度不超过窗口`() {
        // 仅提供今天与前天，昨天缺失
        val todayKey = dayKey(Calendar.getInstance())
        val rows = listOf(
            DailyTrendRow(dayKey(todayMinus(2)), 1000L, 2L),
            DailyTrendRow(todayKey, 500L, 1L)
        )
        val trend = buildFullTrend(rows, dayStartMinus(TREND_DAYS - 1))
        // 窗口起点为 (今天-29) 0 点，到今天共 30 天
        assertEquals(TREND_DAYS, trend.size)
        // 昨天（中间缺的一天）应为 0/0
        val yesterday = trend.first { it.dayKey == dayKey(todayMinus(1)) }
        assertEquals(0L, yesterday.listenMs)
        assertEquals(0L, yesterday.plays)
        // 有数据的两天应保留
        assertEquals(2L, trend.first { it.dayKey == dayKey(todayMinus(2)) }.plays)
        assertEquals(1L, trend.first { it.dayKey == todayKey }.plays)
    }

    @Test
    fun `dayKey 格式为 YYYY-MM-DD 且补零`() {
        val cal = Calendar.getInstance().apply {
            set(2026, Calendar.MARCH, 5, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        assertEquals("2026-03-05", dayKey(cal))
    }

    @Test
    fun `buildFullTrend 自 sinceMs 当天 0 点起算，含今天`() {
        val trend = buildFullTrend(emptyList(), dayStartMinus(TREND_DAYS - 1))
        assertEquals(TREND_DAYS, trend.size)
        // 首日 key 应等于 sinceMs 当天
        assertEquals(dayKey(dayStartCal(TREND_DAYS - 1)), trend.first().dayKey)
        // 末日 key 应等于今天
        assertEquals(dayKey(Calendar.getInstance()), trend.last().dayKey)
    }

    @Test
    fun `buildFullTrend 上限 400 天兜底`() {
        // 给一个极早的 sinceMs（早年），应被 400 天上限截断而非无限生成
        val trend = buildFullTrend(emptyList(), dayStartMinus(5000))
        assertTrue(trend.size <= 400)
        assertEquals(400, trend.size)
    }

    private fun todayMinus(offsetDays: Int): Calendar = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_MONTH, -offsetDays)
    }

    private fun dayStartCal(offsetDays: Int): Calendar = todayMinus(offsetDays)
}
