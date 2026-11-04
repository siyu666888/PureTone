package com.puretone.player.data.repository

import com.puretone.player.data.local.dao.DailyTrendRow
import com.puretone.player.data.local.dao.HourCountRow
import com.puretone.player.data.local.dao.NamedCountRow
import com.puretone.player.data.local.dao.PlayHistoryDao
import com.puretone.player.data.local.dao.SongDao
import com.puretone.player.data.local.dao.StatsDao
import com.puretone.player.data.local.dao.TopSongRow
import com.puretone.player.domain.report.PeriodType
import com.puretone.player.domain.report.ReportData
import com.puretone.player.domain.report.ReportPeriod
import kotlinx.coroutines.flow.Flow
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 听歌统计仓库：统计看板所需的只读聚合查询与历史清空的唯一收口。
 *
 * 2026-11-04 从 MusicRepository 拆出。只读聚合都是 StatsDao / PlayHistoryDao 的
 * 直通转发，与曲库同步、歌单、续播会话等职责无关；清空动作见 [clearHistory]。
 * 拆出后 MusicRepository 的受管函数数由 41 降到 32
 * （detekt TooManyFunctions 阈值 40），detekt-baseline.xml 的最后一条
 * 存量随之删除，规则恢复全量生效。
 *
 * 纯职责搬移，无任何逻辑改动；调用方仅 StatsViewModel。
 */
@Singleton
class StatsRepository @Inject constructor(
    private val statsDao: StatsDao,
    private val playHistoryDao: PlayHistoryDao,
    private val songDao: SongDao
) {
    // ---------- 听歌统计（只读聚合） ----------

    fun observeTotalPlays(): Flow<Long> = statsDao.observeTotalPlays()
    fun observeTotalListenMs(): Flow<Long> = statsDao.observeTotalListenMs()
    fun observeHistoryCount(): Flow<Long> = statsDao.observeHistoryCount()
    fun observePlayedSongs(): Flow<Int> = statsDao.observePlayedSongs()
    fun observeDailyTrend(sinceMs: Long): Flow<List<DailyTrendRow>> =
        statsDao.observeDailyTrend(sinceMs)
    fun observeTopSongs(limit: Int = 10): Flow<List<TopSongRow>> =
        statsDao.observeTopSongs(limit)
    fun observeTopArtists(limit: Int = 10): Flow<List<NamedCountRow>> =
        statsDao.observeTopArtists(limit)
    fun observeHourDistribution(): Flow<List<HourCountRow>> =
        statsDao.observeHourDistribution()

    /**
     * 生成一份周期听歌报告（需求4 阶段2）。
     *
     * 取「最近一个已结束的完整周期」与「上一个同口径周期」两次聚合，算环比。
     * 周期边界全部来自 [ReportPeriod]（java.time 算 epoch 毫秒），SQL 只做
     * `played_at BETWEEN :start AND :end`，不在查询里跑 localtime，杜绝时区漂移。
     *
     * [now] 默认当前时刻；[zoneId] 默认系统默认时区（与写入时的 day_key 口径一致）。
     */
    suspend fun getReport(
        type: PeriodType,
        now: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): ReportData {
        val ranges = when (type) {
            PeriodType.WEEK -> ReportPeriod.lastCompletedWeeks(2, now, zoneId)
            PeriodType.MONTH -> ReportPeriod.lastCompletedMonths(2, now, zoneId)
            PeriodType.YEAR -> ReportPeriod.lastCompletedYears(2, now, zoneId)
        }
        val cur = ranges[0]
        val prev = ranges[1]
        val sum = statsDao.periodSummary(cur.start, cur.end)
        val prevSum = statsDao.periodSummary(prev.start, prev.end)
        val dailyTrend = statsDao.periodDailyTrend(cur.start, cur.end)
        val topSongs = statsDao.periodTopSongs(cur.start, cur.end, 10)
        val topArtists = statsDao.periodTopArtists(cur.start, cur.end, 10)
        val hours = statsDao.periodHourDistribution(cur.start, cur.end)
        val deltaPct = if (prevSum.listenMs > 0) {
            (sum.listenMs - prevSum.listenMs) * 100.0 / prevSum.listenMs
        } else {
            0.0
        }
        return ReportData(
            type = type,
            rangeStart = cur.start,
            rangeEnd = cur.end,
            listenMs = sum.listenMs,
            hasEstimate = sum.estimateRows > 0,
            plays = sum.plays,
            completed = sum.completed,
            distinctSongs = sum.distinctSongs,
            activeDays = sum.activeDays,
            dailyTrend = dailyTrend,
            topSongs = topSongs,
            topArtists = topArtists,
            hourDistribution = hours,
            prevListenMs = prevSum.listenMs,
            deltaPct = deltaPct
        )
    }

    /**
     * 清空全部听歌数据，供统计页「清空历史」入口调用。
     *
     * 必须同时清 `play_history` 与 `songs.play_count/last_played_at`：后者是
     * 最爱歌曲/歌手与「我的最爱」智能歌单的唯一来源，只删历史会让清空后
     * 榜单仍带着旧数据，与「将删除全部播放记录与统计」的文案不符（隐私语义漏洞）。
     * 曲库、收藏、歌单内容均不动。
     */
    suspend fun clearHistory() {
        playHistoryDao.clear()
        songDao.resetPlayStats()
    }
}
