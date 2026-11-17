package com.puretone.player.domain.report

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.abs

/**
 * 听歌报告 V2「声色叙事」派生指标（方案稿第 7 节数据侧口径）。
 *
 * 全部从已有 [ReportData] 纯计算，**不新增 DAO 查询、不联网、不引入新数据**：
 * - 时段分布条：把 0-23 时播放次数折成 4 段（清晨/午后/晚间/深夜），宽度即占比；
 * - 深夜占比：23/0/1 点播放数占总播放的百分比，供「行为画像」金句使用；
 * - 日均 / 最长连续：听歌时长除以活跃天数、日趋势里连续有播放的最长天数；
 * - 绝对环比差：本周期与上周期听歌时长之差（毫秒，可为负）；
 * - 日历热力：整月每日按听歌时长相对峰值的 4 档色阶（0=无播放）。
 *
 * 纯 JVM 对象（只依赖 java.time），便于按边界单测；零播放均返回空/0，不抛异常。
 */
object ReportInsights {

    /** 时段条一段：[label] 可直接展示，[share] 为 0..1 占比，[level] 为光谱档位 1..4 */
    data class TimeSegment(val label: String, val share: Float, val level: Int)

    /** 日历热力一格：[day] 为 1..31，[level] 为 0..4（0=当天无播放） */
    data class CalendarCell(val day: Int, val level: Int)

    /** 时段分档：清晨 5-10 / 午后 11-16 / 晚间 17-22 / 深夜 23-4，覆盖全天 24 小时 */
    private data class Band(val level: Int, val hours: Set<Int>)

    private val BANDS = listOf(
        Band(1, (5..10).toSet()),
        Band(2, (11..16).toSet()),
        Band(3, (17..22).toSet()),
        Band(4, ((23..23) + (0..4)).toSet())
    )

    private val NIGHT_HOURS = setOf(23, 0, 1)

    /** 4 段时段占比；无播放时每段 share=0（调用方据此画等宽空条） */
    fun timeSegments(data: ReportData): List<TimeSegment> {
        val total = data.hourDistribution.sumOf { it.plays }.coerceAtLeast(0L)
        return BANDS.map { band ->
            val plays = data.hourDistribution
                .filter { it.hour in band.hours }
                .sumOf { it.plays }
                .coerceAtLeast(0L)
            val share = if (total > 0) plays.toFloat() / total.toFloat() else 0f
            TimeSegment(LABELS[band.level - 1], share, band.level)
        }
    }

    private val LABELS = listOf("清晨", "午后", "晚间", "深夜")

    /** 深夜（23/0/1 点）播放占比的整数百分比 0..100 */
    fun nightSharePct(data: ReportData): Int {
        val total = data.hourDistribution.sumOf { it.plays }
        if (total <= 0) return 0
        val night = data.hourDistribution.filter { it.hour in NIGHT_HOURS }.sumOf { it.plays }
        return ((night.toDouble() / total.toDouble()) * 100).toInt().coerceIn(0, 100)
    }

    /** 峰值时段所在档位的标签（深夜占比最高即「深夜」，供金句用） */
    fun peakSegmentLabel(data: ReportData): String =
        timeSegments(data).maxByOrNull { it.share }?.label.orEmpty()

    /** 日均听歌毫秒；活跃天数为 0 时返回 0，避免除零 */
    fun dailyAverageMs(data: ReportData): Long =
        if (data.activeDays > 0) data.listenMs / data.activeDays else 0L

    /** 最长连续有播放天数：按 dayKey 日期排序，逐日累加，断档清零 */
    fun longestStreak(data: ReportData): Int {
        val activeDates = data.dailyTrend
            .filter { it.plays > 0 }
            .mapNotNull { runCatching { LocalDate.parse(it.dayKey) }.getOrNull() }
            .sorted()
            .distinct()
        var best = 0
        var cur = 0
        var prev: LocalDate? = null
        for (date in activeDates) {
            val last = prev
            cur = if (last != null && date == last.plusDays(1)) cur + 1 else 1
            if (cur > best) best = cur
            prev = date
        }
        return best
    }

    /** 绝对环比差（本周期 − 上周期），可为负；上一周期为 0 时返回 0 */
    fun deltaAbsoluteMs(data: ReportData): Long =
        if (data.prevListenMs > 0) data.listenMs - data.prevListenMs else 0L

    /** 绝对差的呈现方向：up / down / flat */
    fun deltaDirection(data: ReportData): String {
        val d = deltaAbsoluteMs(data)
        return when {
            d > 0 -> "up"
            d < 0 -> "down"
            else -> "flat"
        }
    }

    /**
     * 整月日历热力（月报专属），从周期起点所在月 1 日铺到月末，无数据的日补 level=0。
     * 非月报返回空表（热力是月报专属符号）。4 档按「相对本月单日峰值的比例」切分：
     * >0~25% → 1，>25~50% → 2，>50~75% → 3，>75% → 4。
     */
    fun calendarCells(data: ReportData, zoneId: ZoneId = ZoneId.systemDefault()): List<CalendarCell> {
        if (data.type != PeriodType.MONTH) return emptyList()
        val first = Instant.ofEpochMilli(data.rangeStart).atZone(zoneId).toLocalDate()
        val ym = YearMonth.of(first.year, first.month)
        val byDay = data.dailyTrend.mapNotNull { row ->
            val day = runCatching { LocalDate.parse(row.dayKey).dayOfMonth }.getOrNull()
            day?.let { it to row.listenMs }
        }.toMap()
        val peak = byDay.values.maxOrNull()?.takeIf { it > 0 } ?: 0L
        return (1..ym.lengthOfMonth()).map { day ->
            val ms = byDay[day] ?: 0L
            val level = if (ms <= 0 || peak <= 0) {
                0
            } else {
                val ratio = ms.toDouble() / peak.toDouble()
                when {
                    ratio <= 0.25 -> 1
                    ratio <= 0.50 -> 2
                    ratio <= 0.75 -> 3
                    else -> 4
                }
            }
            CalendarCell(day, level)
        }
    }

    /** 绝对差毫秒数取绝对值，供文案层套用「多/少」后拼接 */
    fun absDeltaMs(data: ReportData): Long = abs(deltaAbsoluteMs(data))
}
