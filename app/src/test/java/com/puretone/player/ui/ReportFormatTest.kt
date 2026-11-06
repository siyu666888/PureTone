package com.puretone.player.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.puretone.player.data.local.dao.DailyTrendRow
import com.puretone.player.data.local.dao.HourCountRow
import com.puretone.player.data.local.dao.NamedCountRow
import com.puretone.player.domain.report.PeriodType
import com.puretone.player.domain.report.ReportData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

/**
 * 报告文案周期化单测（需求4 阶段3）。
 *
 * 锁住阶段2 的遗留问题：环比 / 趣味标签 / 空态写死「本周」「上周」，
 * 切到月报、年报后文案仍说「这一周」。同时验证屏幕与海报共用的趋势分桶数量。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReportFormatTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /**
     * 造数据必须用**系统默认时区**：toPosterContent 内部的 ReportTrend.buckets(data)
     * 默认按 ZoneId.systemDefault() 把 rangeStart / rangeEnd 折算回 LocalDate。这里若固定
     * Asia/Shanghai，UTC 的 CI 会把「2026-01-01 00:00 +08」回推成 2025-12-31，年报按
     * 2025 年聚合、与 dayKey=2026-01-01 对不上，趋势总和变 0（本地 GMT+8 恰好掩盖）。
     * 项目里其它报告测试能固定 Asia/Shanghai，是因为被测函数都接受显式 zoneId 入参。
     */
    private fun buildData(type: PeriodType, start: String, endExclusive: String): ReportData {
        val zone = ZoneId.systemDefault()
        return ReportData(
            type = type,
            rangeStart = LocalDate.parse(start).atStartOfDay(zone).toInstant().toEpochMilli(),
            rangeEnd = LocalDate.parse(endExclusive).atStartOfDay(zone).toInstant().toEpochMilli() - 1,
            listenMs = 150_000L,
            hasEstimate = false,
            plays = 3L,
            completed = 2L,
            distinctSongs = 2,
            activeDays = 2,
            dailyTrend = listOf(DailyTrendRow(start, 150_000L, 3L)),
            topSongs = emptyList(),
            topArtists = listOf(NamedCountRow("ArtistX", 3L)),
            hourDistribution = listOf(HourCountRow(12, 3L)),
            prevListenMs = 100_000L,
            deltaPct = 50.0
        )
    }

    private fun ranges(type: PeriodType): Pair<String, String> = when (type) {
        PeriodType.WEEK -> "2026-11-02" to "2026-11-09"
        PeriodType.MONTH -> "2026-11-01" to "2026-12-01"
        PeriodType.YEAR -> "2026-01-01" to "2027-01-01"
    }

    @Test
    fun `趋势标题按周期切换为每日每周每月`() {
        assertEquals("每日听歌", ReportFormat.formatTrendTitle(context, PeriodType.WEEK))
        assertEquals("每周听歌", ReportFormat.formatTrendTitle(context, PeriodType.MONTH))
        assertEquals("每月听歌", ReportFormat.formatTrendTitle(context, PeriodType.YEAR))
    }

    @Test
    fun `环比文案按周期带上周上月去年`() {
        val cases = mapOf(
            PeriodType.WEEK to "上周",
            PeriodType.MONTH to "上月",
            PeriodType.YEAR to "去年"
        )
        cases.forEach { (type, word) ->
            val text = ReportFormat.formatDelta(context, 50.0, 100_000L, type)
            assertTrue("type=$type text=$text", text.contains(word))
        }
        assertTrue(
            ReportFormat.formatDelta(context, 0.0, 0L, PeriodType.MONTH).contains("上月无数据")
        )
    }

    @Test
    fun `趣味标签与空态按周期带本周本月今年`() {
        val words = mapOf(
            PeriodType.WEEK to "本周",
            PeriodType.MONTH to "本月",
            PeriodType.YEAR to "今年"
        )
        words.forEach { (type, word) ->
            val (start, end) = ranges(type)
            val data = buildData(type, start, end)
            val funTag = ReportFormat.buildFunTag(context, data)
            assertTrue("type=$type funTag=$funTag", funTag.contains(word))
            assertTrue(
                "type=$type empty=${ReportFormat.formatEmpty(context, type)}",
                ReportFormat.formatEmpty(context, type).startsWith(word)
            )
        }
        val yearFun = ReportFormat.buildFunTag(
            context,
            buildData(PeriodType.YEAR, "2026-01-01", "2027-01-01")
        )
        assertFalse("年报趣味标签不应残留「本周」", yearFun.contains("本周"))
    }

    @Test
    fun `toPosterContent 的趋势分桶数量按周期为 7 5 12`() {
        val expected = mapOf(
            PeriodType.WEEK to 7,
            PeriodType.MONTH to 5,
            PeriodType.YEAR to 12
        )
        expected.forEach { (type, count) ->
            val (start, end) = ranges(type)
            val content = ReportFormat.toPosterContent(context, buildData(type, start, end))
            assertEquals("type=$type", count, content.trendBuckets.size)
            assertEquals(150_000L, content.trendBuckets.sumOf { it.listenMs })
        }
    }
}
