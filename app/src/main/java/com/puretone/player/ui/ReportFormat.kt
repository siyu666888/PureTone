package com.puretone.player.ui

import android.content.Context
import com.puretone.player.R
import com.puretone.player.data.settings.PureToneSettings
import com.puretone.player.domain.report.PeriodType
import com.puretone.player.domain.report.ReportData
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

    /** 周期角标，如「周报 · 2026 第45周」 */
    fun formatPeriodBadge(context: Context, type: PeriodType, startMs: Long): String {
        val d = Instant.ofEpochMilli(startMs).atZone(ZoneId.systemDefault()).toLocalDate()
        return when (type) {
            PeriodType.WEEK -> {
                val wk = d.get(WeekFields.ISO.weekOfWeekBasedYear())
                context.getString(R.string.pt_report_badge_week, d.year, wk)
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

    /** 环比文案：上涨/下跌/持平/上周无数据 */
    fun formatDelta(context: Context, deltaPct: Double, prevListenMs: Long): String {
        if (prevListenMs <= 0) return context.getString(R.string.pt_report_delta_none)
        return when {
            deltaPct > 0.5 -> context.getString(R.string.pt_report_delta_up, formatPct(deltaPct))
            deltaPct < -0.5 -> context.getString(R.string.pt_report_delta_down, formatPct(-deltaPct))
            else -> context.getString(R.string.pt_report_delta_flat)
        }
    }

    /** 百分比格式化：去掉多余的 .0 */
    fun formatPct(pct: Double): String {
        val s = "%.1f".format(Locale.US, kotlin.math.abs(pct))
        return if (s.endsWith(".0")) s.dropLast(2) else s
    }

    /** 趣味标签（可多行，用 \n 分隔） */
    fun buildFunTag(context: Context, data: ReportData): String {
        val lines = mutableListOf<String>()
        val favArtist = data.topArtists.firstOrNull()?.name
        if (favArtist != null && data.plays > 0) {
            lines += context.getString(R.string.pt_report_fun_artist, favArtist)
        }
        if (data.peakHour >= 0) {
            if (data.isNightOwl) lines += context.getString(R.string.pt_report_fun_night, data.peakHour)
            else lines += context.getString(R.string.pt_report_fun_day, data.peakHour)
        }
        if (data.activeDays > 0) {
            lines += context.getString(R.string.pt_report_fun_active, data.activeDays)
        }
        return lines.joinToString("\n")
    }

    /** 由 App 主题模式推导海报默认主题（默认跟随 App 当前主题） */
    fun defaultPosterTheme(themeMode: String): PosterTheme = when (themeMode) {
        PureToneSettings.THEME_LIGHT -> PosterTheme.LIGHT
        PureToneSettings.THEME_DARK -> PosterTheme.DARK
        PureToneSettings.THEME_BLACK -> PosterTheme.BLACK
        else -> PosterTheme.LIGHT
    }

    /** 把 [ReportData] 组装成 [PosterContent]（所有文案本地化完成） */
    fun toPosterContent(context: Context, data: ReportData): PosterContent {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        return PosterContent(
            data = data,
            periodBadge = formatPeriodBadge(context, data.type, data.rangeStart),
            leadText = context.getString(leadRes(data.type)),
            mainNumber = formatListenMs(context, data.listenMs),
            rangeText = formatRangeText(context, data.type, data.rangeStart, data.rangeEnd),
            deltaText = formatDelta(context, data.deltaPct, data.prevListenMs),
            estimateNote = if (data.hasEstimate) context.getString(R.string.pt_report_estimate) else "",
            capsulePlays = context.getString(R.string.pt_report_capsule_plays, data.plays),
            capsuleSongs = context.getString(R.string.pt_report_capsule_songs, data.distinctSongs),
            capsuleActiveDays = context.getString(R.string.pt_report_capsule_active, data.activeDays),
            topSongsTitle = context.getString(topTitleRes(data.type)),
            trendTitle = context.getString(R.string.pt_report_label_trend),
            funTag = buildFunTag(context, data),
            funTagTitle = context.getString(R.string.pt_report_label_fun),
            footer = context.getString(R.string.pt_report_footer),
            generatedOn = context.getString(R.string.pt_report_generated, today)
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
}
