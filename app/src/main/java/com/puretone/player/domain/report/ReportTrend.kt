package com.puretone.player.domain.report

import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * 报告趋势分桶（需求4 阶段3）：把日粒度 [ReportData.dailyTrend] 折成三周期各自的柱序列。
 *
 * 方案 4.1 的分组粒度：
 * - 周报：7 个自然日（周一…周日），**无数据的日补 0**；
 * - 月报：自 1 日起按 7 天窗口切 W1…W5（自然月内不跨月，避免 ISO 周跨界造成歧义）；
 * - 年报：12 个自然月，**无数据的月补 0**。
 *
 * 为什么必须「补齐」：DAO 的 `periodDailyTrend` 是 `GROUP BY day_key`，只返回有播放的日期。
 * 阶段2 屏幕端直接拿它画柱，会出现「只听 3 天却画 3 根柱」的错位；海报端虽补了 7 天，
 * 但两边口径不一致。这里统一成纯函数，屏幕与分享海报消费**同一份** [TrendBucket] 列表，
 * 保证同图同标签（方案 6.5「屏幕与海报同词」）。
 *
 * 本对象只依赖 java.time，纯 JVM 可单测（跨月、月末、闰年、跨年、空数据）。
 */
object ReportTrend {

    /** 周柱标签用本地化的短星期名（与阶段2 `weekdayLabel` 口径一致） */
    private val WEEKDAY_FMT = DateTimeFormatter.ofPattern("EEE", Locale.CHINA)
    private val ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE

    /** 一根趋势柱：[label] 为可直接显示的短标签，[listenMs] 为该桶听歌时长 */
    data class TrendBucket(val label: String, val listenMs: Long)

    /**
     * 按 [ReportData.type] 分桶。
     *
     * [ReportData.rangeStart] / [ReportData.rangeEnd] 是闭区间 epoch 毫秒：
     * end 为「下一周期 00:00 减 1 毫秒」，转 LocalDate 后即本周期最后一日。
     */
    fun buckets(
        data: ReportData,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): List<TrendBucket> {
        val start = Instant.ofEpochMilli(data.rangeStart).atZone(zoneId).toLocalDate()
        val end = Instant.ofEpochMilli(data.rangeEnd).atZone(zoneId).toLocalDate()
        return when (data.type) {
            PeriodType.WEEK -> weekBuckets(data, start)
            PeriodType.MONTH -> monthBuckets(data, start, end)
            PeriodType.YEAR -> yearBuckets(data, start.year)
        }
    }

    /** 周报：固定 7 根，从周期首日顺推，缺日补 0 */
    private fun weekBuckets(data: ReportData, start: LocalDate): List<TrendBucket> {
        val byDay = byDayMap(data)
        return (0..6).map { i ->
            val day = start.plusDays(i.toLong())
            TrendBucket(day.format(WEEKDAY_FMT), byDay[day.format(ISO_DATE)] ?: 0L)
        }
    }

    /** 月报：列出月内每一天再按 7 天切块，最后一块可能是 1~7 天（月末/闰年二月） */
    private fun monthBuckets(
        data: ReportData,
        start: LocalDate,
        end: LocalDate
    ): List<TrendBucket> {
        val byDay = byDayMap(data)
        val days = generateSequence(start) { day ->
            day.plusDays(1).takeIf { !it.isAfter(end) }
        }.toList()
        return days.chunked(7).mapIndexed { index, chunk ->
            TrendBucket("W${index + 1}", chunk.sumOf { byDay[it.format(ISO_DATE)] ?: 0L })
        }
    }

    /** 年报：固定 12 根，按 dayKey 的 `yyyy-MM` 前缀聚合，缺月补 0 */
    private fun yearBuckets(data: ReportData, year: Int): List<TrendBucket> {
        val byMonth = data.dailyTrend
            .groupBy { it.dayKey.take(7) }
            .mapValues { (_, rows) -> rows.sumOf { it.listenMs } }
        return (1..12).map { month ->
            val key = "%04d-%02d".format(Locale.US, year, month)
            TrendBucket(Month.of(month).getDisplayName(TextStyle.SHORT, Locale.CHINA), byMonth[key] ?: 0L)
        }
    }

    private fun byDayMap(data: ReportData): Map<String, Long> =
        data.dailyTrend.associate { it.dayKey to it.listenMs }
}
