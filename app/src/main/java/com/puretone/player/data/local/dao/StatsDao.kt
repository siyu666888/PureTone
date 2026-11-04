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

    // ---------- 周期聚合（需求4 阶段2：周/月/年报）----------

    /**
     * 指定周期窗的汇总指标。
     *
     * 口径（方案 4.2，全部基于 `played_at BETWEEN :start AND :end`，不含 SQLite localtime 转换）：
     * - [PeriodSummaryRow.listenMs]：实际收听毫秒 `SUM(played_ms)`；老数据 `played_ms=0` 回退 `duration_ms`。
     * - [PeriodSummaryRow.estimateRows]：窗内「用了回退估算」的条数（用于标注「含估算」）。
     * - [PeriodSummaryRow.plays]：窗内历史条数（每次「开始播放」计 1）。
     * - [PeriodSummaryRow.completed]：`SUM(completed)`，完整播放次数。
     * - [PeriodSummaryRow.distinctSongs]：`COUNT(DISTINCT song_id)`。
     * - [PeriodSummaryRow.activeDays]：`COUNT(DISTINCT day_key)`（空 day_key 不计，避免老数据空串干扰）。
     */
    @Query(
        """
        SELECT
            COALESCE(SUM(CASE WHEN played_ms = 0 THEN duration_ms ELSE played_ms END), 0) AS listenMs,
            SUM(CASE WHEN played_ms = 0 AND duration_ms > 0 THEN 1 ELSE 0 END) AS estimateRows,
            COUNT(*) AS plays,
            COALESCE(SUM(completed), 0) AS completed,
            COUNT(DISTINCT song_id) AS distinctSongs,
            COUNT(DISTINCT CASE WHEN day_key <> '' THEN day_key END) AS activeDays
        FROM play_history
        WHERE played_at BETWEEN :start AND :end
        """
    )
    suspend fun periodSummary(start: Long, end: Long): PeriodSummaryRow

    /**
     * 周期窗内每日听歌时长与播放次数（按预存 [day_key] 聚合，升序）。
     * 复用 [DailyTrendRow]（dayKey/listenMs/plays）。周报即 7 根柱。
     */
    @Query(
        """
        SELECT day_key AS dayKey,
               COALESCE(SUM(CASE WHEN played_ms = 0 THEN duration_ms ELSE played_ms END), 0) AS listenMs,
               COUNT(*) AS plays
        FROM play_history
        WHERE played_at BETWEEN :start AND :end
        GROUP BY day_key
        ORDER BY day_key ASC
        """
    )
    suspend fun periodDailyTrend(start: Long, end: Long): List<DailyTrendRow>

    /**
     * 周期窗内 Top 歌曲：按 `song_id` 聚合**播放次数**（决策点③），JOIN [songs] 取展示信息。
     * 排序：播放次数降序，次数相同看最近一次播放（更「当期」的歌优先）。
     */
    @Query(
        """
        SELECT s.id AS id, s.title AS title, s.artist AS artist, s.album AS album,
               COUNT(*) AS playCount, s.cover_uri AS coverUri
        FROM play_history h
        INNER JOIN songs s ON s.id = h.song_id
        WHERE h.played_at BETWEEN :start AND :end
        GROUP BY h.song_id
        ORDER BY playCount DESC, MAX(h.played_at) DESC
        LIMIT :limit
        """
    )
    suspend fun periodTopSongs(start: Long, end: Long, limit: Int = 10): List<TopSongRow>

    /**
     * 周期窗内 Top 歌手：按 `artist` 聚合**播放次数**，JOIN [songs] 取歌手名。
     */
    @Query(
        """
        SELECT CASE WHEN s.artist = '' THEN '未知艺术家' ELSE s.artist END AS name,
               COUNT(*) AS playCount
        FROM play_history h
        INNER JOIN songs s ON s.id = h.song_id
        WHERE h.played_at BETWEEN :start AND :end
        GROUP BY name
        ORDER BY playCount DESC
        LIMIT :limit
        """
    )
    suspend fun periodTopArtists(start: Long, end: Long, limit: Int = 10): List<NamedCountRow>

    /**
     * 周期窗内时段分布（0-23 时播放次数），用于周期专属趣味标签。
     */
    @Query(
        """
        SELECT CAST(strftime('%H', played_at / 1000, 'unixepoch', 'localtime') AS INTEGER) AS hour,
               COUNT(*) AS plays
        FROM play_history
        WHERE played_at BETWEEN :start AND :end
        GROUP BY hour
        ORDER BY hour ASC
        """
    )
    suspend fun periodHourDistribution(start: Long, end: Long): List<HourCountRow>
}

/** 周期汇总行（见 [periodSummary] 口径注释） */
data class PeriodSummaryRow(
    val listenMs: Long,
    val estimateRows: Long,
    val plays: Long,
    val completed: Long,
    val distinctSongs: Int,
    val activeDays: Int
)

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
