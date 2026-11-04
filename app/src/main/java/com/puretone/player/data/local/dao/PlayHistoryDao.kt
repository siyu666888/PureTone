package com.puretone.player.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.puretone.player.data.local.entity.PlayHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlayHistoryDao {

    @Insert
    suspend fun insert(history: PlayHistoryEntity)

    /** 最近播放（按时间倒序） */
    @Query("SELECT * FROM play_history ORDER BY played_at DESC LIMIT :limit")
    fun observeRecent(limit: Int = 50): Flow<List<PlayHistoryEntity>>

    /**
     * 最近播放过去重后的歌曲（同首歌多次播放只取最近一次）。
     *
     * P3-11：旧实现在子查询按 song_id 取 MAX(played_at)，再按 (song_id, played_at)
     * 回连——同一首歌若两条历史落在同一毫秒，两行都满足 `played_at = max_at`，
     * 会被重复返回。改用自增主键 id 作为决胜：id 与插入时刻单调对齐，MAX(id)
     * 即「最近一次播放」那一行，回连 (song_id, id) 唯一确定、不再重复。
     */
    @Query(
        """
        SELECT h.* FROM play_history h
        INNER JOIN (
            SELECT song_id, MAX(id) AS max_id FROM play_history GROUP BY song_id
        ) latest ON h.song_id = latest.song_id AND h.id = latest.max_id
        ORDER BY h.played_at DESC LIMIT :limit
        """
    )
    fun observeRecentSongs(limit: Int = 50): Flow<List<PlayHistoryEntity>>

    @Query("DELETE FROM play_history")
    suspend fun clear()

    /** 清理超过保留期的历史（保留约 1 年，具体见 [com.puretone.player.data.repository.MusicRepository.HISTORY_RETENTION_MS]） */
    @Query("DELETE FROM play_history WHERE played_at < :before")
    suspend fun clearOlderThan(before: Long)

    /**
     * 暂停/切歌结算（需求4）：回填最近一条「未结算」(end_at=0) 的该歌曲播放记录。
     *
     * 取 id 最大（最新插入）的开行回填，避免误改历史行；播放中的歌曲始终只有一条
     * 未结算记录，故命中即「本次播放会话」。playedMs<=0 的非法结算由调用方过滤，
     * 这里只负责 UPDATE。
     */
    @Query(
        """
        UPDATE play_history
        SET played_ms = :playedMs, end_at = :endAt
        WHERE id = (
            SELECT id FROM play_history
            WHERE song_id = :songId AND end_at = 0
            ORDER BY id DESC LIMIT 1
        )
        """
    )
    suspend fun settleLastPlayback(songId: Long, playedMs: Long, endAt: Long)

    /**
     * 播放完成结算（需求4）：回填最近一条该歌曲播放记录（无论是否已结算），
     * 标记 completed=1 且 played_ms 记为整曲时长（自然播完即完整收听）。
     */
    @Query(
        """
        UPDATE play_history
        SET played_ms = :durationMs, end_at = :endAt, completed = 1
        WHERE id = (
            SELECT id FROM play_history
            WHERE song_id = :songId
            ORDER BY id DESC LIMIT 1
        )
        """
    )
    suspend fun settleCompletion(songId: Long, durationMs: Long, endAt: Long)
}
