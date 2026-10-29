package com.puretone.player.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
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
}
