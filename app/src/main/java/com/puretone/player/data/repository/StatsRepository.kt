package com.puretone.player.data.repository

import com.puretone.player.data.local.dao.DailyTrendRow
import com.puretone.player.data.local.dao.HourCountRow
import com.puretone.player.data.local.dao.NamedCountRow
import com.puretone.player.data.local.dao.PlayHistoryDao
import com.puretone.player.data.local.dao.StatsDao
import com.puretone.player.data.local.dao.TopSongRow
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 听歌统计仓库：统计看板所需的只读聚合查询与历史清空的唯一收口。
 *
 * 2026-11-04 从 MusicRepository 拆出。这 9 个方法都是 StatsDao /
 * PlayHistoryDao 的直通转发，与曲库同步、歌单、续播会话等职责无关。
 * 拆出后 MusicRepository 的受管函数数由 41 降到 32
 * （detekt TooManyFunctions 阈值 40），detekt-baseline.xml 的最后一条
 * 存量随之删除，规则恢复全量生效。
 *
 * 纯职责搬移，无任何逻辑改动；调用方仅 StatsViewModel。
 */
@Singleton
class StatsRepository @Inject constructor(
    private val statsDao: StatsDao,
    private val playHistoryDao: PlayHistoryDao
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

    /** 清空播放历史（不含曲库与收藏），供统计页「清空历史」入口调用 */
    suspend fun clearHistory() = playHistoryDao.clear()
}
