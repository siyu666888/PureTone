package com.puretone.player.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.puretone.player.data.local.entity.PlaylistEntity
import com.puretone.player.data.local.entity.PlaylistSongEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {

    @Insert
    suspend fun insert(playlist: PlaylistEntity): Long

    @Update
    suspend fun update(playlist: PlaylistEntity)

    @Delete
    suspend fun delete(playlist: PlaylistEntity)

    @Query("SELECT * FROM playlists ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun getById(id: Long): PlaylistEntity?

    @Query("UPDATE playlists SET name = :name, updated_at = :now WHERE id = :id")
    suspend fun rename(id: Long, name: String, now: Long = System.currentTimeMillis())

    /** 歌单及其歌曲（按 position 排序） */
    @Transaction
    @Query("SELECT * FROM playlists WHERE id = :playlistId")
    fun observeWithSongs(playlistId: Long): Flow<PlaylistWithSongs?>

    @Transaction
    @Query("SELECT * FROM playlists")
    fun observeAllWithSongs(): Flow<List<PlaylistWithSongs>>

    // ---------- 关联 ----------

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addSongs(entries: List<PlaylistSongEntity>)

    @Query("DELETE FROM playlist_songs WHERE playlist_id = :playlistId AND song_id = :songId")
    suspend fun removeSong(playlistId: Long, songId: Long)

    @Query("SELECT MAX(position) FROM playlist_songs WHERE playlist_id = :playlistId")
    suspend fun maxPosition(playlistId: Long): Int?

    @Query("DELETE FROM playlist_songs WHERE playlist_id = :playlistId")
    suspend fun clearSongs(playlistId: Long)

    /** 新增歌曲到歌单（自动排到末尾，幂等） */
    @Transaction
    suspend fun addSongsAtEnd(playlistId: Long, songIds: List<Long>) {
        val start = (maxPosition(playlistId) ?: -1) + 1
        addSongs(
            songIds.mapIndexed { index, songId ->
                PlaylistSongEntity(
                    playlistId = playlistId,
                    songId = songId,
                    position = start + index
                )
            }
        )
    }
}
