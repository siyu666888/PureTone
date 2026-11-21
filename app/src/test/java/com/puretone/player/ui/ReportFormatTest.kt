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
    fun `formatPeriodBadge 的周角标年份用周所属年而不是日历年`() {
        // 跨年周：周区间起点是 ISO 周一（ReportPeriod.lastCompletedWeeks），落点上一年 12 月、
        // 却属于下一年的第 1 周（ISO 第 1 周 = 含当年第一个周四的那周）——年份必须跟周号同源。
        // 旧实现拼日历年，这里会输出「周报 · 2024 第 1 周」。
        assertEquals(
            "周报 · 2025 第 1 周",
            ReportFormat.formatPeriodBadge(context, PeriodType.WEEK, epochAtNoon(LocalDate.parse("2024-12-30")))
        )
        // 下一个即将发生的同类周（周一 2029-12-31 = ISO 2030-W01）
        assertEquals(
            "周报 · 2030 第 1 周",
            ReportFormat.formatPeriodBadge(context, PeriodType.WEEK, epochAtNoon(LocalDate.parse("2029-12-31")))
        )
        // 非周一的落点（仍在 ISO 2026-W53 内）：角标函数对任意 startMs 都按周所属年
        assertEquals(
            "周报 · 2026 第 53 周",
            ReportFormat.formatPeriodBadge(context, PeriodType.WEEK, epochAtNoon(LocalDate.parse("2027-01-01")))
        )
        // 年内周不受影响（回归护栏）
        assertEquals(
            "周报 · 2026 第 47 周",
            ReportFormat.formatPeriodBadge(context, PeriodType.WEEK, epochAtNoon(LocalDate.parse("2026-11-16")))
        )
        assertEquals(
            "周报 · 2026 第 53 周",
            ReportFormat.formatPeriodBadge(context, PeriodType.WEEK, epochAtNoon(LocalDate.parse("2026-12-28")))
        )
        // 月报 / 年报用日历年，语义正确，不随周口径一起变
        assertEquals(
            "月报 · 2024 年 12 月",
            ReportFormat.formatPeriodBadge(context, PeriodType.MONTH, epochAtNoon(LocalDate.parse("2024-12-30")))
        )
        assertEquals(
            "年报 · 2024 年",
            ReportFormat.formatPeriodBadge(context, PeriodType.YEAR, epochAtNoon(LocalDate.parse("2024-12-30")))
        )
    }

    @Test
    fun `formatRangeText 按周期给出区间年月`() {
        // 生产口径：周区间 end 是「下周一 00:00 - 1ms」→ 落在周日，不是周一
        val start = epochAtNoon(LocalDate.parse("2026-11-02"))
        val end = epochAtNoon(LocalDate.parse("2026-11-08"))
        assertEquals(
            "11.02 - 11.08",
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
    fun `formatDeltaAbsolute 用绝对时长差并区分多寡与无数据`() {
        // 150min vs 100min → 多 50 分钟
        val up = buildData(PeriodType.WEEK, "2026-11-02", "2026-11-09").copy(
            listenMs = 9_000_000L, prevListenMs = 6_000_000L
        )
        assertTrue(ReportFormat.formatDeltaAbsolute(context, up).contains("上周"))
        assertTrue(ReportFormat.formatDeltaAbsolute(context, up).contains("多"))
        assertTrue(ReportFormat.formatDeltaAbsolute(context, up).contains("50 分钟"))
        // 反向 → 少
        val down = up.copy(listenMs = 6_000_000L, prevListenMs = 9_000_000L)
        assertTrue(ReportFormat.formatDeltaAbsolute(context, down).contains("少"))
        assertTrue(ReportFormat.formatDeltaAbsolute(context, down).contains("50 分钟"))
        // 上一周期无数据 / 持平 → 空串
        assertEquals("", ReportFormat.formatDeltaAbsolute(context, up.copy(prevListenMs = 0L)))
        assertEquals("", ReportFormat.formatDeltaAbsolute(context, up.copy(listenMs = 6_000_000L))) // 持平
    }

    @Test
    fun `buildGoldenText 深夜占比高用夜猫子句否则用主场句`() {
        // 深夜 60/100 = 60% ≥ 30 → 夜猫子句带百分比
        val night = buildData(PeriodType.WEEK, "2026-11-02", "2026-11-09").copy(
            hourDistribution = listOf(HourCountRow(23, 60L), HourCountRow(12, 40L))
        )
        val nightText = ReportFormat.buildGoldenText(context, night)
        assertTrue("夜猫子句应含百分比：${nightText}", nightText.contains("60%"))
        // 全在白天 → 主场句，不含百分比
        val day = night.copy(hourDistribution = listOf(HourCountRow(12, 100L)))
        val dayText = ReportFormat.buildGoldenText(context, day)
        assertFalse("主场句不应含百分比：${dayText}", dayText.contains("%"))
        assertTrue("主场句应有时段名：${dayText}", dayText.contains("午后"))
    }

    @Test
    fun `toPosterContent V2 派生字段按周期正确填充`() {
        val week = ReportFormat.toPosterContent(context, buildData(PeriodType.WEEK, "2026-11-02", "2026-11-09"))
        assertEquals(4, week.timeSegments.size)
        assertTrue(week.goldenText.isNotBlank())
        assertTrue(week.deltaAbsoluteText.isNotBlank())
        assertEquals("", week.heatTitle)
        assertTrue(week.calendarCells.isEmpty())

        val month = ReportFormat.toPosterContent(context, buildData(PeriodType.MONTH, "2026-11-01", "2026-12-01"))
        assertTrue(month.heatTitle.isNotBlank())
        assertTrue(month.dailyAverageText.isNotBlank())
        assertTrue(month.longestStreakText.isNotBlank())

        val year = ReportFormat.toPosterContent(context, buildData(PeriodType.YEAR, "2026-01-01", "2027-01-01"))
        assertTrue("年报不启 V2 新模块", year.timeSegments.isEmpty())
        assertTrue(year.goldenText.isEmpty())
    }

    @Test
    fun `effectivePosterTheme 显式模式按 App 判据映射`() {
        assertEquals(
            PosterTheme.LIGHT,
            ReportFormat.effectivePosterTheme(PureToneSettings.THEME_LIGHT, systemDark = true, amoledBlack = true)
        )
        assertEquals(
            PosterTheme.DARK,
            ReportFormat.effectivePosterTheme(PureToneSettings.THEME_DARK, systemDark = false, amoledBlack = false)
        )
        // DARK + AMOLED 开 → 纯黑；BLACK 模式无论 AMOLED 与否都是纯黑
        assertEquals(
            PosterTheme.BLACK,
            ReportFormat.effectivePosterTheme(PureToneSettings.THEME_DARK, systemDark = false, amoledBlack = true)
        )
        assertEquals(
            PosterTheme.BLACK,
            ReportFormat.effectivePosterTheme(PureToneSettings.THEME_BLACK, systemDark = false, amoledBlack = false)
        )
    }

    @Test
    fun `effectivePosterTheme 的 SYSTEM 跟随系统深色与 AMOLED`() {
        assertEquals(
            PosterTheme.LIGHT,
            ReportFormat.effectivePosterTheme(PureToneSettings.THEME_SYSTEM, systemDark = false, amoledBlack = false)
        )
        assertEquals(
            PosterTheme.DARK,
            ReportFormat.effectivePosterTheme(PureToneSettings.THEME_SYSTEM, systemDark = true, amoledBlack = false)
        )
        assertEquals(
            PosterTheme.BLACK,
            ReportFormat.effectivePosterTheme(PureToneSettings.THEME_SYSTEM, systemDark = true, amoledBlack = true)
        )
        // SYSTEM 但系统浅色时即使开了 AMOLED 也不该纯黑（与 PureToneTheme 判据一致）
        assertEquals(
            PosterTheme.LIGHT,
            ReportFormat.effectivePosterTheme(PureToneSettings.THEME_SYSTEM, systemDark = false, amoledBlack = true)
        )
    }

    @Test
    fun `effectivePosterTheme 未知值与 App 一样按系统深色回落`() {
        // 未知值不再固定浅色：与 PureToneTheme 的 else -> SYSTEM 判据同源，跟随系统
        assertEquals(
            PosterTheme.LIGHT,
            ReportFormat.effectivePosterTheme("", systemDark = false, amoledBlack = false)
        )
        assertEquals(
            PosterTheme.DARK,
            ReportFormat.effectivePosterTheme("UNKNOWN_THEME", systemDark = true, amoledBlack = false)
        )
    }

    @Test
    fun `formatRangeText 跨年周给两端补年份而同年区间不变`() {
        // 年末完整周：2026-12-28（周一）~ 2027-01-03（周日），ISO 2026-W53
        assertEquals(
            "2026.12.28 - 2027.01.03",
            ReportFormat.formatRangeText(
                context, PeriodType.WEEK,
                epochAtNoon(LocalDate.parse("2026-12-28")),
                epochAtNoon(LocalDate.parse("2027-01-03"))
            )
        )
        // 另一跨年样本：ISO 2019-W01 的周一落在上一年 12 月
        assertEquals(
            "2018.12.31 - 2019.01.06",
            ReportFormat.formatRangeText(
                context, PeriodType.WEEK,
                epochAtNoon(LocalDate.parse("2018-12-31")),
                epochAtNoon(LocalDate.parse("2019-01-06"))
            )
        )
        // 同年跨月不补年，防止把「仅跨年补年」写宽成「永远补年」
        assertEquals(
            "11.30 - 12.06",
            ReportFormat.formatRangeText(
                context, PeriodType.WEEK,
                epochAtNoon(LocalDate.parse("2026-11-30")),
                epochAtNoon(LocalDate.parse("2026-12-06"))
            )
        )
        // 月报 / 年报不受跨年影响：仍按起始日的日历年输出
        val crossStart = epochAtNoon(LocalDate.parse("2026-12-28"))
        val crossEnd = epochAtNoon(LocalDate.parse("2027-01-03"))
        assertEquals(
            "2026.12",
            ReportFormat.formatRangeText(context, PeriodType.MONTH, crossStart, crossEnd)
        )
        assertEquals(
            "2026",
            ReportFormat.formatRangeText(context, PeriodType.YEAR, crossStart, crossEnd)
        )
    }

    @Test
    fun `formatRangeText 同年周报的月份补零到两位`() {
        // 1-9 月补零后与跨年分支 yyyy.MM.dd、月报 yyyy.MM 的格式一致
        assertEquals(
            "01.05 - 01.11",
            ReportFormat.formatRangeText(
                context, PeriodType.WEEK,
                epochAtNoon(LocalDate.parse("2026-01-05")),
                epochAtNoon(LocalDate.parse("2026-01-11"))
            )
        )
        assertEquals(
            "09.06 - 09.12",
            ReportFormat.formatRangeText(
                context, PeriodType.WEEK,
                epochAtNoon(LocalDate.parse("2026-09-06")),
                epochAtNoon(LocalDate.parse("2026-09-12"))
            )
        )
        // 10/11/12 月本就是两位，补零不改变输出
        assertEquals(
            "11.02 - 11.08",
            ReportFormat.formatRangeText(
                context, PeriodType.WEEK,
                epochAtNoon(LocalDate.parse("2026-11-02")),
                epochAtNoon(LocalDate.parse("2026-11-08"))
            )
        )
    }

    @Test
    fun `B3 formatDelta 下跌与持平分支按上一周期有数据正确出文案`() {
        val prev = context.getString(com.puretone.player.R.string.pt_report_delta_period_week)
        // 下跌：-50% 命中 down 分支，取绝对百分比，周期词仍为「上周」
        val down = ReportFormat.formatDelta(context, -50.0, 100_000L, PeriodType.WEEK)
        // formatPct 去掉 .0 → "50"；百分比符号由文案资源自带 %%
        assertEquals(context.getString(com.puretone.player.R.string.pt_report_delta_down, prev, "50"), down)
        // 持平：prev>0 且 delta 在 ±0.5 内命中 flat，不含上涨/下跌箭头
        val flat = ReportFormat.formatDelta(context, 0.0, 100_000L, PeriodType.WEEK)
        assertEquals(context.getString(com.puretone.player.R.string.pt_report_delta_flat, prev), flat)
        assertFalse(flat.contains("↑"))
        assertFalse(flat.contains("↓"))
    }

    @Test
    fun `B3 formatDelta 的 ±0_5 临界点落在持平分支`() {
        val prev = context.getString(com.puretone.player.R.string.pt_report_delta_period_week)
        val flatRes = context.getString(com.puretone.player.R.string.pt_report_delta_flat, prev)
        // 判据是严格 >0.5 / <-0.5，恰好 ±0.5 必须判持平
        assertEquals(flatRes, ReportFormat.formatDelta(context, 0.5, 100_000L, PeriodType.WEEK))
        assertEquals(flatRes, ReportFormat.formatDelta(context, -0.5, 100_000L, PeriodType.WEEK))
        // 微超阈值则分别落到 up / down
        assertFalse(flatRes == ReportFormat.formatDelta(context, 0.51, 100_000L, PeriodType.WEEK))
        assertFalse(flatRes == ReportFormat.formatDelta(context, -0.51, 100_000L, PeriodType.WEEK))
    }
}
