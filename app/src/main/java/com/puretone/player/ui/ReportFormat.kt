package com.puretone.player.ui

import android.content.Context
import com.puretone.player.R
import com.puretone.player.data.settings.PureToneSettings
import com.puretone.player.domain.report.PeriodType
import com.puretone.player.domain.report.ReportData
import com.puretone.player.domain.report.ReportInsights
import com.puretone.player.domain.report.ReportTrend
import com.puretone.player.ui.report.PosterContent
import com.puretone.player.ui.report.PosterTheme
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Date
import java.util.Locale

/**
 * 听歌报告相关文案格式化（需求4 阶段2）。
 *
 * 全部走 [Context.getString] + `pt_report_*` 资源键，不依赖 Compose（便于在非 UI 上下文
 * 组装海报内容）。周期边界用 java.time 从 epoch 毫秒还原本地日期，与写入口径一致。
 */
object ReportFormat {

    /** 毫秒 → "X 小时 Y 分" / "Y 分钟" / "X 秒"（复用统计页时长文案） */
    fun formatListenMs(context: Context, ms: Long): String {
        if (ms <= 0) return context.getString(R.string.pt_stats_dur_zero)
        val totalMin = ms / 60000
        return when {
            totalMin >= 60 -> context.getString(R.string.pt_stats_dur_hours, totalMin / 60, totalMin % 60)
            totalMin >= 1 -> context.getString(R.string.pt_stats_dur_min, totalMin)
            else -> context.getString(R.string.pt_stats_dur_sec, ms / 1000)
        }
    }

    /**
     * 周期角标，如「周报 · 2026 第45周」。
     *
     * 周角标的年份是**周所属年**（ISO week-based year），不是日历年：ISO 第 1 周是含当年
     * 第一个周四的那周，它的周一可能落在上一年 12 月（周一 2029-12-31 = ISO 2030-W01）。
     * 年份与周号必须同源，否则跨年周会拼成「周报 · 2029 第 1 周」。月报 / 年报用日历年。
     */
    fun formatPeriodBadge(context: Context, type: PeriodType, startMs: Long): String {
        val d = Instant.ofEpochMilli(startMs).atZone(ZoneId.systemDefault()).toLocalDate()
        return when (type) {
            PeriodType.WEEK -> {
                // 周号是 ISO 周号，属于「周所属年」而不是日历年：跨年周的周一落在上一年 12 月，
                // 但它属于下一年的第 1 周（ISO 第 1 周 = 含当年第一个周四的那周）。年份与周号
                // 必须同源，否则 2024-12-30 会拼成「周报 · 2024 第 1 周」。
                val fields = WeekFields.ISO
                val wk = d.get(fields.weekOfWeekBasedYear())
                val weekBasedYear = d.get(fields.weekBasedYear())
                context.getString(R.string.pt_report_badge_week, weekBasedYear, wk)
            }
            PeriodType.MONTH -> context.getString(R.string.pt_report_badge_month, d.year, d.monthValue)
            PeriodType.YEAR -> context.getString(R.string.pt_report_badge_year, d.year)
        }
    }

    /** 周期范围文案，如「11.03 - 11.09」 */
    fun formatRangeText(context: Context, type: PeriodType, startMs: Long, endMs: Long): String {
        val zone = ZoneId.systemDefault()
        val start = Instant.ofEpochMilli(startMs).atZone(zone).toLocalDate()
        val end = Instant.ofEpochMilli(endMs).atZone(zone).toLocalDate()
        return when (type) {
            PeriodType.WEEK -> {
                val f = DateTimeFormatter.ofPattern("M.dd", Locale.US)
                "${start.format(f)} - ${end.format(f)}"
            }
            PeriodType.MONTH -> DateTimeFormatter.ofPattern("yyyy.MM", Locale.US).format(start)
            PeriodType.YEAR -> DateTimeFormatter.ofPattern("yyyy", Locale.US).format(start)
        }
    }

    /** 趋势区标题按周期切换：每日听歌 / 每周听歌 / 每月听歌（与分桶粒度一致） */
    fun formatTrendTitle(context: Context, type: PeriodType): String =
        context.getString(
            when (type) {
                PeriodType.WEEK -> R.string.pt_report_label_trend
                PeriodType.MONTH -> R.string.pt_report_label_trend_month
                PeriodType.YEAR -> R.string.pt_report_label_trend_year
            }
        )

    /** 空态文案按周期切换，避免月/年报仍提示「这一周」 */
    fun formatEmpty(context: Context, type: PeriodType): String =
        context.getString(R.string.pt_report_empty, context.getString(periodWordRes(type)))

    /** 环比文案：上涨/下跌/持平/上一周期无数据（周期词随 type 变化） */
    fun formatDelta(
        context: Context,
        deltaPct: Double,
        prevListenMs: Long,
        type: PeriodType
    ): String {
        val prev = context.getString(deltaPeriodRes(type))
        if (prevListenMs <= 0) return context.getString(R.string.pt_report_delta_none, prev)
        return when {
            deltaPct > 0.5 -> context.getString(R.string.pt_report_delta_up, prev, formatPct(deltaPct))
            deltaPct < -0.5 -> context.getString(R.string.pt_report_delta_down, prev, formatPct(-deltaPct))
            else -> context.getString(R.string.pt_report_delta_flat, prev)
        }
    }

    /** 百分比格式化：去掉多余的 .0 */
    fun formatPct(pct: Double): String {
        val s = "%.1f".format(Locale.US, kotlin.math.abs(pct))
        return if (s.endsWith(".0")) s.dropLast(2) else s
    }

    /** 趣味标签（可多行，用 \n 分隔）；阶段3 起带「本周/本月/今年」周期词，不再写死本周 */
    fun buildFunTag(context: Context, data: ReportData): String {
        val periodWord = context.getString(periodWordRes(data.type))
        val lines = mutableListOf<String>()
        val favArtist = data.topArtists.firstOrNull()?.name
        if (favArtist != null && data.plays > 0) {
            lines += context.getString(R.string.pt_report_fun_artist, periodWord, favArtist)
        }
        if (data.peakHour >= 0) {
            if (data.isNightOwl) lines += context.getString(R.string.pt_report_fun_night, data.peakHour)
            else lines += context.getString(R.string.pt_report_fun_day, data.peakHour)
        }
        if (data.activeDays > 0) {
            lines += context.getString(R.string.pt_report_fun_active, periodWord, data.activeDays)
        }
        return lines.joinToString("\n")
    }

    /**
     * 报告主题自动跟随 App 实际生效主题，与 PureToneTheme 的判据同源：
     * - SYSTEM 跟随系统深色（[systemDark]），不再固定浅色；
     * - 纯黑既可由 THEME_BLACK 触发，也可由 DARK/SYSTEM(深色) + amoledBlack 触发。
     *
     * @param themeMode App 主题模式字符串（SYSTEM/LIGHT/DARK/BLACK）
     * @param systemDark 系统当前是否深色，仅 SYSTEM 模式消费
     * @param amoledBlack 深色模式下是否启用纯黑表面
     */
    fun effectivePosterTheme(
        themeMode: String,
        systemDark: Boolean,
        amoledBlack: Boolean
    ): PosterTheme {
        val dark = when (themeMode) {
            PureToneSettings.THEME_LIGHT -> false
            PureToneSettings.THEME_DARK, PureToneSettings.THEME_BLACK -> true
            else -> systemDark // SYSTEM 及未知值：跟随系统，与 PureToneTheme 一致
        }
        val useBlack = amoledBlack || themeMode == PureToneSettings.THEME_BLACK
        return when {
            dark && useBlack -> PosterTheme.BLACK
            dark -> PosterTheme.DARK
            else -> PosterTheme.LIGHT
        }
    }

    /** 时段档位 level 1..4 → 本地化标签 */
    private fun timeLabelRes(level: Int): Int = when (level) {
        1 -> R.string.pt_report_time_dawn
        2 -> R.string.pt_report_time_noon
        3 -> R.string.pt_report_time_dusk
        else -> R.string.pt_report_time_night
    }

    /** 时段条：ReportInsights 只给 level/share，文案在文案层本地化 */
    private fun buildTimeSegments(context: Context, data: ReportData): List<ReportInsights.TimeSegment> =
        ReportInsights.timeSegments(data).map { it.copy(label = context.getString(timeLabelRes(it.level))) }

    /** 绝对环比差文案；上一周期无数据或持平返回空串（V2 主打绝对差，与百分比并存） */
    fun formatDeltaAbsolute(context: Context, data: ReportData): String {
        val d = ReportInsights.deltaAbsoluteMs(data)
        if (data.prevListenMs <= 0 || d == 0L) return ""
        val res = when (ReportInsights.deltaDirection(data)) {
            "up" -> R.string.pt_report_delta_abs_up
            "down" -> R.string.pt_report_delta_abs_down
            else -> return ""
        }
        return context.getString(
            res,
            context.getString(deltaPeriodRes(data.type)),
            formatListenMs(context, ReportInsights.absDeltaMs(data))
        )
    }

    /**
     * 行为金句：深夜占比 >= 30% 用「夜猫子」句，否则用峰值时段「主场」句。
     * 指向行为而非数量（设计稿标注 4），只看本机时段数据，不造对比锚点。
     */
    fun buildGoldenText(context: Context, data: ReportData): String {
        val pct = ReportInsights.nightSharePct(data)
        return if (pct >= 30) {
            context.getString(R.string.pt_report_golden_night, pct)
        } else {
            val peak = ReportInsights.timeSegments(data).maxByOrNull { it.share }
            val label = if (peak != null && peak.share > 0f) {
                context.getString(timeLabelRes(peak.level))
            } else {
                context.getString(R.string.pt_report_time_dusk)
            }
            context.getString(R.string.pt_report_golden_day, label)
        }
    }

    /** 把 [ReportData] 组装成 [PosterContent]（所有文案本地化完成） */
    fun toPosterContent(context: Context, data: ReportData): PosterContent {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        // 年报维持 V1 骨架，V2 新模块（时段条/热力/绝对差）只对周/月生效
        val isV2 = data.type != PeriodType.YEAR
        val timeSegments = if (isV2) buildTimeSegments(context, data) else emptyList()
        val peakSeg = timeSegments.maxByOrNull { it.share }
        val timeTitle = when (data.type) {
            PeriodType.WEEK -> context.getString(
                R.string.pt_report_time_title,
                context.getString(R.string.pt_report_time_span_week)
            )
            PeriodType.MONTH -> context.getString(
                R.string.pt_report_time_title,
                context.getString(R.string.pt_report_time_span_month)
            )
            PeriodType.YEAR -> ""
        }
        return PosterContent(
            data = data,
            periodBadge = formatPeriodBadge(context, data.type, data.rangeStart),
            leadText = context.getString(leadRes(data.type)),
            mainNumber = formatListenMs(context, data.listenMs),
            rangeText = formatRangeText(context, data.type, data.rangeStart, data.rangeEnd),
            deltaText = formatDelta(context, data.deltaPct, data.prevListenMs, data.type),
            estimateNote = if (data.hasEstimate) context.getString(R.string.pt_report_estimate) else "",
            capsulePlays = context.getString(R.string.pt_report_capsule_plays, data.plays),
            capsuleSongs = context.getString(R.string.pt_report_capsule_songs, data.distinctSongs),
            capsuleActiveDays = context.getString(R.string.pt_report_capsule_active, data.activeDays),
            topSongsTitle = context.getString(topTitleRes(data.type)),
            trendTitle = formatTrendTitle(context, data.type),
            trendBuckets = ReportTrend.buckets(data),
            funTag = buildFunTag(context, data),
            funTagTitle = context.getString(R.string.pt_report_label_fun),
            footer = context.getString(R.string.pt_report_footer),
            generatedOn = context.getString(R.string.pt_report_generated, today),
            timeSegments = timeSegments,
            timeTitle = timeTitle,
            timePeakText = if (peakSeg != null && peakSeg.share > 0f) {
                context.getString(R.string.pt_report_time_peak, peakSeg.label)
            } else {
                ""
            },
            goldenText = if (isV2) buildGoldenText(context, data) else "",
            deltaAbsoluteText = if (isV2) formatDeltaAbsolute(context, data) else "",
            dailyAverageText = if (data.type == PeriodType.MONTH) {
                context.getString(
                    R.string.pt_report_daily_avg,
                    formatListenMs(context, ReportInsights.dailyAverageMs(data))
                )
            } else {
                ""
            },
            longestStreakText = if (data.type == PeriodType.MONTH) {
                context.getString(R.string.pt_report_streak, ReportInsights.longestStreak(data))
            } else {
                ""
            },
            calendarCells = ReportInsights.calendarCells(data),
            heatTitle = if (data.type == PeriodType.MONTH) {
                context.getString(R.string.pt_report_heat_title)
            } else {
                ""
            },
            heatHint = if (data.type == PeriodType.MONTH) {
                context.getString(R.string.pt_report_heat_hint)
            } else {
                ""
            }
        )
    }

    private fun leadRes(type: PeriodType): Int = when (type) {
        PeriodType.WEEK -> R.string.pt_report_lead_week
        PeriodType.MONTH -> R.string.pt_report_lead_month
        PeriodType.YEAR -> R.string.pt_report_lead_year
    }

    private fun topTitleRes(type: PeriodType): Int = when (type) {
        PeriodType.WEEK -> R.string.pt_report_top_week
        PeriodType.MONTH -> R.string.pt_report_top_month
        PeriodType.YEAR -> R.string.pt_report_top_year
    }

    /** 当前周期词：本周 / 本月 / 今年（趣味标签、空态复用） */
    private fun periodWordRes(type: PeriodType): Int = when (type) {
        PeriodType.WEEK -> R.string.pt_report_period_week
        PeriodType.MONTH -> R.string.pt_report_period_month
        PeriodType.YEAR -> R.string.pt_report_period_year
    }

    /** 上一周期词：上周 / 上月 / 去年（环比文案复用） */
    private fun deltaPeriodRes(type: PeriodType): Int = when (type) {
        PeriodType.WEEK -> R.string.pt_report_delta_period_week
        PeriodType.MONTH -> R.string.pt_report_delta_period_month
        PeriodType.YEAR -> R.string.pt_report_delta_period_year
    }
}
