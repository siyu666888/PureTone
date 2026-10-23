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

    /** 最近播放过去重后的歌曲（同首歌多次播放只取最近一次） */
    @Query(
        """
        SELECT h.* FROM play_history h
        INNER JOIN (
            SELECT song_id, MAX(played_at) AS max_at FROM play_history GROUP BY song_id
        ) latest ON h.song_id = latest.song_id AND h.played_at = latest.max_at
        ORDER BY h.played_at DESC LIMIT :limit
        """
    )
    fun observeRecentSongs(limit: Int = 50): Flow<List<PlayHistoryEntity>>

    @Query("DELETE FROM play_history")
    suspend fun clear()

    /** 清理 7 天前的历史 */
    @Query("DELETE FROM play_history WHERE played_at < :before")
    suspend fun clearOlderThan(before: Long)
}
