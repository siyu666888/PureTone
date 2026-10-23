package com.puretone.player.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** 听歌统计聚合查询（只读，写路径走 SongDao / PlayHistoryDao） */
@Dao
interface StatsDao {

    /** 累计播放次数（播放历史条数；每次开始播放计一次） */
    @Query("SELECT COUNT(*) FROM play_history")
    fun observeTotalPlays(): Flow<Long>

    /** 累计播放歌曲数（历史中出现过的不同歌曲数） */
    @Query("SELECT COUNT(DISTINCT song_id) FROM play_history")
    fun observePlayedSongs(): Flow<Int>

    /**
     * 累计听歌时长（毫秒）：按每次播放计入该歌曲时长近似累加。
     * 注意：这是"开始播放即计一次整首"的近似口径，非精确收听秒数。
     */
    @Query("SELECT COALESCE(SUM(duration_ms), 0) FROM play_history")
    fun observeTotalListenMs(): Flow<Long>

    /** 历史播放总条数（近保留窗口内） */
    @Query("SELECT COUNT(*) FROM play_history")
    fun observeHistoryCount(): Flow<Long>

    /**
     * 近 N 天每日听歌时长（毫秒）与播放次数，按日期升序。
     * [sinceMs] 起点时间戳；返回 [dayKey, listenMs, plays]。
     * dayKey 形如 "2026-10-21"（本地时区，由 SQLite date() 计算）。
     */
    @Query(
        """
        SELECT date(played_at / 1000, 'unixepoch', 'localtime') AS dayKey,
               COALESCE(SUM(duration_ms), 0) AS listenMs,
               COUNT(*) AS plays
        FROM play_history
        WHERE played_at >= :sinceMs
        GROUP BY dayKey
        ORDER BY dayKey ASC
        """
    )
    fun observeDailyTrend(sinceMs: Long): Flow<List<DailyTrendRow>>

    /** 最爱歌曲 TOP（按 play_count = 完整播放完成次数，取前 [limit]） */
    @Query(
        """
        SELECT id, title, artist, album, play_count AS playCount, cover_uri AS coverUri
        FROM songs
        WHERE play_count > 0
        ORDER BY play_count DESC, last_played_at DESC
        LIMIT :limit
        """
    )
    fun observeTopSongs(limit: Int = 10): Flow<List<TopSongRow>>

    /**
     * 最爱歌手 TOP：按歌手聚合播放次数（仅完整播放完成），取前 [limit]。
     * 未填艺术家的归入"未知艺术家"。
     */
    @Query(
        """
        SELECT CASE WHEN artist = '' THEN '未知艺术家' ELSE artist END AS name,
               COALESCE(SUM(play_count), 0) AS playCount
        FROM songs
        WHERE play_count > 0
        GROUP BY name
        ORDER BY playCount DESC
        LIMIT :limit
        """
    )
    fun observeTopArtists(limit: Int = 10): Flow<List<NamedCountRow>>

    /**
     * 时段分布（0-23 小时的播放次数），用于「深夜听歌时段」等趣味标签。
     */
    @Query(
        """
        SELECT CAST(strftime('%H', played_at / 1000, 'unixepoch', 'localtime') AS INTEGER) AS hour,
               COUNT(*) AS plays
        FROM play_history
        GROUP BY hour
        ORDER BY hour ASC
        """
    )
    fun observeHourDistribution(): Flow<List<HourCountRow>>
}

/** 每日趋势行（按日期聚合） */
data class DailyTrendRow(
    val dayKey: String,
    val listenMs: Long,
    val plays: Long
)

/** TOP 歌曲行 */
data class TopSongRow(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val playCount: Int,
    val coverUri: String?
)

/** 名称 + 计数（歌手/专辑等通用聚合） */
data class NamedCountRow(
    val name: String,
    val playCount: Long
)

/** 小时 + 计数 */
data class HourCountRow(
    val hour: Int,
    val plays: Long
)
