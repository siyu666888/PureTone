package com.puretone.player.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.puretone.player.data.local.dao.DailyTrendRow
import com.puretone.player.data.local.dao.HourCountRow
import com.puretone.player.data.local.dao.NamedCountRow
import com.puretone.player.data.settings.PureToneSettings
import com.puretone.player.domain.report.PeriodType
import com.puretone.player.domain.report.ReportData
import com.puretone.player.ui.report.PosterTheme
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

    // ------------------------------------------------------------------
    // 2026-11-16：补 ReportFormat 五个纯函数的**直接**断言。
    //
    // 此前它们只被 toPosterContent / formatDelta 的间接路径覆盖：改了
    // formatListenMs 的分支、formatPct 的去 .0、formatPeriodBadge 的周号，
    // 只要 toPosterContent 仍然能拼出字符串，单测就全绿——海报上的数字悄悄
    // 变了也没人拦。这里按「函数 + 分支」逐条钉死，并补负向断言（负值、
    // 未知枚举、跨零临界），让改坏必变红。
    // ------------------------------------------------------------------

    /**
     * 把 [LocalDate] 折成 epoch 毫秒。
     *
     * 用**正午**而不是零点：零点落在少数时区的夏令时缺口里时，
     * `atStartOfDay` 会前移一小时，epoch 回折时日期就差一天，测试在 CI
     * （UTC）与本地（GMT+8）会给出不同结论。正午永不在缺口里。
     */
    private fun epochAtNoon(date: LocalDate): Long =
        date.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun `formatListenMs 覆盖零秒分时四个分支并把负值兜底为零`() {
        // ms <= 0 走零分支（负值也走这里，绝不能出现负数文案）
        assertEquals("0 分钟", ReportFormat.formatListenMs(context, 0L))
        assertEquals("0 分钟", ReportFormat.formatListenMs(context, -1L))
        assertEquals("0 分钟", ReportFormat.formatListenMs(context, Long.MIN_VALUE))
        // 秒分支：不足 1 分钟
        assertEquals("1 秒", ReportFormat.formatListenMs(context, 1_500L))
        // 分分支：1 ~ 59 分钟（向下取整，2.5 分钟显示 2 分钟）
        assertEquals("2 分钟", ReportFormat.formatListenMs(context, 150_000L))
        // 时分支：60 分钟以上，拆成「X 小时 Y 分」
        assertEquals("1 小时 0 分", ReportFormat.formatListenMs(context, 3_600_000L))
        assertEquals("1 小时 30 分", ReportFormat.formatListenMs(context, 5_400_000L))
    }

    @Test
    fun `formatListenMs 在秒与分与小时的临界点上切换分支`() {
        assertEquals("59 秒", ReportFormat.formatListenMs(context, 59_999L))
        assertEquals("1 分钟", ReportFormat.formatListenMs(context, 60_000L))
        assertEquals("59 分钟", ReportFormat.formatListenMs(context, 3_599_999L))
        assertEquals("1 小时 0 分", ReportFormat.formatListenMs(context, 3_600_000L))
        assertEquals("2 小时 1 分", ReportFormat.formatListenMs(context, 7_260_000L))
    }

    @Test
    fun `formatPct 去掉多余的点零并对负数取绝对值`() {
        assertEquals("50", ReportFormat.formatPct(50.0))
        assertEquals("100", ReportFormat.formatPct(100.0))
        assertEquals("0", ReportFormat.formatPct(0.0))
        assertEquals("12.3", ReportFormat.formatPct(12.34))
        // 负数取绝对值：涨跌方向由调用方用文案区分，数字本身不带负号
        assertEquals("50", ReportFormat.formatPct(-50.0))
        assertEquals("12.3", ReportFormat.formatPct(-12.34))
    }

    @Test
    fun `formatPct 在零点附近与四舍五入边界上表现稳定`() {
        assertEquals("0", ReportFormat.formatPct(0.04))
        assertEquals("0.1", ReportFormat.formatPct(0.05))
        assertEquals("0.3", ReportFormat.formatPct(0.25))
        assertEquals("0.8", ReportFormat.formatPct(0.75))
        assertEquals("100", ReportFormat.formatPct(99.99))
        assertEquals("1234.6", ReportFormat.formatPct(1234.56))
    }

    @Test
    fun `formatPeriodBadge 按周期给出年与周号月号`() {
        val day = epochAtNoon(LocalDate.parse("2026-11-16"))
        assertEquals(
            "周报 · 2026 第 47 周",
            ReportFormat.formatPeriodBadge(context, PeriodType.WEEK, day)
        )
        assertEquals(
            "月报 · 2026 年 11 月",
            ReportFormat.formatPeriodBadge(context, PeriodType.MONTH, day)
        )
        assertEquals(
            "年报 · 2026 年",
            ReportFormat.formatPeriodBadge(context, PeriodType.YEAR, day)
        )
    }

    @Test
    fun `formatPeriodBadge 的周号随日期变化而不是写死`() {
        assertEquals(
            "周报 · 2026 第 10 周",
            ReportFormat.formatPeriodBadge(context, PeriodType.WEEK, epochAtNoon(LocalDate.parse("2026-03-05")))
        )
        assertEquals(
            "周报 · 2026 第 25 周",
            ReportFormat.formatPeriodBadge(context, PeriodType.WEEK, epochAtNoon(LocalDate.parse("2026-06-15")))
        )
        assertEquals(
            "月报 · 2026 年 3 月",
            ReportFormat.formatPeriodBadge(context, PeriodType.MONTH, epochAtNoon(LocalDate.parse("2026-03-05")))
        )
        assertEquals(
            "年报 · 2027 年",
            ReportFormat.formatPeriodBadge(context, PeriodType.YEAR, epochAtNoon(LocalDate.parse("2027-01-01")))
        )
    }

    @Test
    fun `formatRangeText 按周期给出区间年月`() {
        val start = epochAtNoon(LocalDate.parse("2026-11-02"))
        val end = epochAtNoon(LocalDate.parse("2026-11-09"))
        assertEquals(
            "11.02 - 11.09",
            ReportFormat.formatRangeText(context, PeriodType.WEEK, start, end)
        )
        assertEquals(
            "2026.11",
            ReportFormat.formatRangeText(context, PeriodType.MONTH, start, end)
        )
        assertEquals(
            "2026",
            ReportFormat.formatRangeText(context, PeriodType.YEAR, start, end)
        )
    }

    @Test
    fun `defaultPosterTheme 按主题模式映射并在未知值回落浅色`() {
        assertEquals(PosterTheme.LIGHT, ReportFormat.defaultPosterTheme(PureToneSettings.THEME_LIGHT))
        assertEquals(PosterTheme.DARK, ReportFormat.defaultPosterTheme(PureToneSettings.THEME_DARK))
        assertEquals(PosterTheme.BLACK, ReportFormat.defaultPosterTheme(PureToneSettings.THEME_BLACK))
        // 未知值（含空串、大小写不符）必须回落浅色，不能崩也不能错配
        assertEquals(PosterTheme.LIGHT, ReportFormat.defaultPosterTheme(""))
        assertEquals(PosterTheme.LIGHT, ReportFormat.defaultPosterTheme("system"))
        assertEquals(PosterTheme.LIGHT, ReportFormat.defaultPosterTheme("UNKNOWN_THEME"))
    }
}
