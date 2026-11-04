package com.puretone.player.domain.report

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 听歌报告周期边界纯函数（需求4 阶段1：数据基建）。
 *
 * 设计要点：所有「周期起止」在生成期用 java.time 算成 epoch 毫秒区间，SQL 查询只做
 * `played_at BETWEEN :start AND :end`（BETWEEN 含两端）。**不在查询里跑 localtime 转换**——
 * history 的 [com.puretone.player.data.local.entity.PlayHistoryEntity.playedAt] 是 UTC epoch，
 * 若查询时用设备时区 `localtime` 换算，会因时区/夏令时漂移导致跨日、跨周边界错乱。
 *
 * 只生成「已结束的完整周期」：本周/本月/本年若尚未走完，不在报告窗口内（避免半截数据误导）。
 * 周起始日固定 ISO 周一（决策点①）。
 */
object ReportPeriod {

    /** 播放历史保留天数（决策点④：原 365 → 放宽到 400 天，覆盖更长年度趋势） */
    const val HISTORY_RETENTION_DAYS = 400L

    private val DAY_KEY_FMT = DateTimeFormatter.ISO_LOCAL_DATE

    /**
     * 由 epoch 毫秒算本地日期键 yyyy-MM-dd（插入历史时一次性算好，查询聚合直接复用）。
     */
    fun dayKeyFromEpoch(epochMillis: Long, zoneId: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(epochMillis).atZone(zoneId).toLocalDate().format(DAY_KEY_FMT)

    /** 最近 [count] 个「已结束」的周（周一 00:00:00.000 ~ 下周一 00:00:00.000 前一毫秒，ISO 周一始） */
    fun lastCompletedWeeks(
        count: Int,
        now: Long,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): List<PeriodRange> {
        require(count > 0) { "count 必须为正" }
        val today = Instant.ofEpochMilli(now).atZone(zoneId).toLocalDate()
        // 本周一（含 today 所在周的周一）；最近一个完整周是「上周一 ~ 本周一前」
        var weekStart = today.with(DayOfWeek.MONDAY).minusDays(7)
        return List(count) {
            val range = rangeOf(weekStart, weekStart.plusWeeks(1), zoneId)
            weekStart = weekStart.minusWeeks(1)
            range
        }
    }

    /** 最近 [count] 个「已结束」的月（自然月 1 日 00:00 ~ 次月 1 日 00:00 前一毫秒） */
    fun lastCompletedMonths(
        count: Int,
        now: Long,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): List<PeriodRange> {
        require(count > 0) { "count 必须为正" }
        val today = Instant.ofEpochMilli(now).atZone(zoneId).toLocalDate()
        var monthStart = today.withDayOfMonth(1).minusMonths(1)
        return List(count) {
            val range = rangeOf(monthStart, monthStart.plusMonths(1), zoneId)
            monthStart = monthStart.minusMonths(1)
            range
        }
    }

    /** 最近 [count] 个「已结束」的年（1/1 00:00 ~ 次年 1/1 00:00 前一毫秒） */
    fun lastCompletedYears(
        count: Int,
        now: Long,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): List<PeriodRange> {
        require(count > 0) { "count 必须为正" }
        val today = Instant.ofEpochMilli(now).atZone(zoneId).toLocalDate()
        var yearStart = today.withDayOfYear(1).minusYears(1)
        return List(count) {
            val range = rangeOf(yearStart, yearStart.plusYears(1), zoneId)
            yearStart = yearStart.minusYears(1)
            range
        }
    }

    /**
     * 尾部窗口（决策点④ 年报口径）：从 [now] 往前推 [days] 天到 [now] 的闭区间，
     * 用于「过去 N 天」聚合（年报默认取 [HISTORY_RETENTION_DAYS]）。
     */
    fun trailingWindow(
        days: Long,
        now: Long,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): PeriodRange {
        val start = now - days * 24 * 60 * 60 * 1000L
        return PeriodRange(start, now, dayKeyFromEpoch(start, zoneId))
    }

    /**
     * 由起始 LocalDate 与「下一个周期起点 LocalDate」生成 [PeriodRange]
     * （[endExclusive] 当天的 00:00:00.000 减 1 毫秒 = 上一周期最后一刻）。
     */
    private fun rangeOf(start: LocalDate, endExclusive: LocalDate, zoneId: ZoneId): PeriodRange {
        val startMs = start.atStartOfDay(zoneId).toEpochSecond() * 1000
        val endMs = endExclusive.atStartOfDay(zoneId).toEpochSecond() * 1000 - 1
        return PeriodRange(startMs, endMs, dayKeyFromEpoch(startMs, zoneId))
    }

    /** 周期边界：[start]（含）~ [end]（含）的 epoch 毫秒闭区间；[label] 取起始日键，便于 UI 展示 */
    data class PeriodRange(val start: Long, val end: Long, val label: String) {
        fun contains(epochMillis: Long): Boolean = epochMillis in start..end
    }
}
