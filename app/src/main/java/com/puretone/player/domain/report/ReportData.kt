package com.puretone.player.domain.report

import com.puretone.player.data.local.dao.DailyTrendRow
import com.puretone.player.data.local.dao.HourCountRow
import com.puretone.player.data.local.dao.NamedCountRow
import com.puretone.player.data.local.dao.TopSongRow

/**
 * 听歌报告周期类型（需求4 阶段2 定义，阶段3 全部上线）。
 *
 * 三种周期共用同一套 `played_at BETWEEN` 聚合（见
 * [com.puretone.player.data.repository.StatsRepository.getReport]），差异只在周期边界与
 * 趋势分桶粒度（见 [ReportTrend]）：周 7 日柱 / 月 W1…W5 周柱 / 年 12 月柱。
 */
enum class PeriodType {
    WEEK, MONTH, YEAR
}

/**
 * 一份周期听歌报告（纯数据，不含任何本地化文案）。
 *
 * 全部指标口径见方案 4.2，时间窗由 [com.puretone.player.domain.report.ReportPeriod]
 * 在生成期用 java.time 算成 epoch 毫秒闭区间，SQL 只做 `BETWEEN`，不在查询里跑 localtime。
 *
 * @param type            周期类型
 * @param rangeStart      周期起点 epoch 毫秒（含）
 * @param rangeEnd        周期终点 epoch 毫秒（含）
 * @param listenMs        听歌时长（毫秒）；老数据 `played_ms=0` 时回退 `duration_ms`
 * @param hasEstimate     窗内是否包含「回退估算」的条数（用于标注「含估算」）
 * @param plays           周期窗内播放次数（历史条数）
 * @param completed       完整播放次数 `SUM(completed)`
 * @param distinctSongs   收听歌曲数 `COUNT(DISTINCT song_id)`
 * @param activeDays      活跃天数 `COUNT(DISTINCT day_key)`
 * @param dailyTrend      每日听歌趋势（按 [day_key] 升序；周报即 7 根柱）
 * @param topSongs        周期 Top 歌曲（按播放次数，已 JOIN songs 取展示信息）
 * @param topArtists      周期 Top 歌手（按播放次数）
 * @param hourDistribution 周期时段分布（0-23 时播放次数）
 * @param prevListenMs    上一完整同口径周期的听歌时长（用于环比）
 * @param deltaPct        环比百分比；[prevListenMs]==0 时为 0.0
 */
data class ReportData(
    val type: PeriodType,
    val rangeStart: Long,
    val rangeEnd: Long,
    val listenMs: Long,
    val hasEstimate: Boolean,
    val plays: Long,
    val completed: Long,
    val distinctSongs: Int,
    val activeDays: Int,
    val dailyTrend: List<DailyTrendRow>,
    val topSongs: List<TopSongRow>,
    val topArtists: List<NamedCountRow>,
    val hourDistribution: List<HourCountRow>,
    val prevListenMs: Long,
    val deltaPct: Double
) {
    /** 报告是否为空（零播放）——空时禁止生成海报，改显空态 */
    val isEmpty: Boolean get() = plays <= 0L

    /** 峰值时段（0-23），无数据返回 -1 */
    val peakHour: Int
        get() = hourDistribution.maxByOrNull { it.plays }?.takeIf { it.plays > 0 }?.hour ?: -1

    /** 是否「深夜听众」（峰值落在深夜档）——第七轮 B5：与深夜占比、时段档共用同一权威口径 */
    val isNightOwl: Boolean get() = peakHour in NIGHT_HOURS

    companion object {
        /**
         * 深夜小时集合（23 点 + 0-4 点，共 6 小时）——「深夜档 / 夜猫子」唯一权威口径。
         * 第七轮 B5 前，时段档用 {23,0..4}、nightSharePct 用 {23,0,1}、isNightOwl 用 0..5，
         * 三套并存导致同一份报告「夜猫子」与「主场深夜」自相矛盾；现统一于此。
         */
        val NIGHT_HOURS: Set<Int> = ((23..23) + (0..4)).toSet()
    }
}
