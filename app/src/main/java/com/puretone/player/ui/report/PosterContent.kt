package com.puretone.player.ui.report

import com.puretone.player.domain.report.ReportData
import com.puretone.player.domain.report.ReportInsights
import com.puretone.player.domain.report.ReportTrend

/**
 * 海报主题（需求4 阶段2 6.4）。
 *
 * 与 [com.puretone.player.ui.theme.PureToneColorSchemes] 的 Light/Dark/Black 一一对应；
 * 默认跟随 App 当前主题，分享前用户可在报告页切换。具体色板（android.graphics 颜色 int）
 * 由 [PosterRenderer] 持有，避免 Compose Color 与 Canvas 绘制混用。
 */
enum class PosterTheme {
    LIGHT, DARK, BLACK
}

/**
 * 渲染一份分享海报所需的全部内容。
 *
 * 设计为「纯字符串 + 结构化数据」：所有本地化文案由 ViewModel 经 stringResource 预先拼好，
 * [PosterRenderer.render] 只负责把内容画到 Canvas，**不查任何资源、不依赖 Compose**，
 * 因此可在任意线程生成、易于写纯 JVM 单测（断言尺寸/不抛异常）。
 *
 * 结构化部分（[data]）供图表与 Top 列表绘制：日趋势柱、Top 歌曲等；文案部分供文字图层。
 */
data class PosterContent(
    val data: ReportData,
    /** 周期角标，如「周报 · 2026 第45周」 */
    val periodBadge: String,
    /** 引导语，如「这一周，你听了」 */
    val leadText: String,
    /** 主数字（听歌时长），已本地化，如「12 小时 30 分」 */
    val mainNumber: String,
    /** 周期范围文案，如「11.03 - 11.09」 */
    val rangeText: String,
    /** 环比文案，如「较上周 ↑ 12%」或「上周无数据」 */
    val deltaText: String,
    /** 「含历史估算值」注记，无估算时为空串 */
    val estimateNote: String,
    /** 胶囊：播放次数（含单位），如「48 次」 */
    val capsulePlays: String,
    /** 胶囊：收听歌曲（含单位），如「23 首」 */
    val capsuleSongs: String,
    /** 胶囊：活跃天数（含单位），如「6 天」 */
    val capsuleActiveDays: String,
    /** Top 歌曲区标题，如「本周最爱」 */
    val topSongsTitle: String,
    /** 趋势区标题，按周期切换：每日听歌 / 每周听歌 / 每月听歌 */
    val trendTitle: String,
    /**
     * 趋势柱（阶段3）：由 [ReportTrend.buckets] 一次性折好，屏幕与海报共用同一份，
     * 避免两边分别处理日粒度导致的柱数/标签口径不一致。周 7 根 / 月 W1…W5 / 年 12 根。
     */
    val trendBuckets: List<ReportTrend.TrendBucket>,
    /** 趣味标签（可多行，用 \n 分隔） */
    val funTag: String,
    /** 趣味标签区标题，如「趣味标签」 */
    val funTagTitle: String,
    /** 页脚品牌口令，如「PureTone·纯净之音」 */
    val footer: String,
    /** 生成日期文案，如「2026-11-10 生成」 */
    val generatedOn: String,

    // ---- V2「声色叙事」派生字段（全部由 ReportInsights 从 data 计算，无新数据源） ----

    /**
     * 时段分布条四段（清晨/午后/晚间/深夜），[share] 为占比、[level] 为光谱档位。
     * 屏幕与海报共用同一份，宽度即占比。
     */
    val timeSegments: List<ReportInsights.TimeSegment>,
    /** 时段区标题，如「你的一周在几点」（年报保持空串不展示） */
    val timeTitle: String,
    /** 时段峰值注记，如「峰值 深夜」 */
    val timePeakText: String,
    /** 行为画像金句：深夜听众占比或白天主场，指向行为而非数量 */
    val goldenText: String,
    /** 绝对环比差文案，如「比上周多 3 小时 12 分」；上一周期无数据时为空串 */
    val deltaAbsoluteText: String,
    /**
     * 日均听歌文案，如「日均 2 小时 32 分」（年报为空串）。
     * B3：仅屏幕端 ReportHero 展示；海报为紧凑子集不渲染此字段（见 PosterRenderer 顶部注释）。
     */
    val dailyAverageText: String,
    /**
     * 最长连续天数文案，如「最长连续 11 天」（年报为空串）。
     * B3：仅屏幕端 ReportHero 展示；海报为紧凑子集不渲染此字段。
     */
    val longestStreakText: String,
    /** 月报声音日历热力格（整月铺满，无数据日 level=0；非月报为空表） */
    val calendarCells: List<ReportInsights.CalendarCell>,
    /** 热力区标题，如「声音日历」（非月报为空串） */
    val heatTitle: String,
    /** 热力色阶说明，如「颜色越暖 = 听得越多」（非月报为空串） */
    val heatHint: String
)
