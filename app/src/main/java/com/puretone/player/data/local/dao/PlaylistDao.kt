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
import kotlinx.coroutines.flow.combine

@Dao
interface PlaylistDao {

    @Insert
    suspend fun insert(playlist: PlaylistEntity): Long

    @Update
    suspend fun update(playlist: PlaylistEntity)

    @Delete
    suspend fun delete(playlist: PlaylistEntity)

    @Query("SELECT * FROM playlists ORDER BY updated_at DESC")
    fun observePlaylists(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun getById(id: Long): PlaylistEntity?

    @Query("UPDATE playlists SET name = :name, updated_at = :now WHERE id = :id")
    suspend fun rename(id: Long, name: String, now: Long = System.currentTimeMillis())

    // ---------- 歌单 + 歌曲（显式 JOIN，按 position 排序） ----------

    @Query("SELECT * FROM playlists WHERE id = :playlistId")
    fun observePlaylistById(playlistId: Long): Flow<PlaylistEntity?>

    @Query(
        """
        SELECT ps.playlist_id AS playlist_id, ps.position AS position,
               songs.id AS song_id,
               songs.media_store_id AS song_media_store_id,
               songs.title AS song_title,
               songs.artist AS song_artist,
               songs.album AS song_album,
               songs.album_id AS song_album_id,
               songs.duration AS song_duration,
               songs.path AS song_path,
               songs.size AS song_size,
               songs.cover_uri AS song_cover_uri,
               songs.mimeType AS song_mimeType,
               songs.year AS song_year,
               songs.track_number AS song_track_number,
               songs.date_added AS song_date_added,
               songs.date_modified AS song_date_modified,
               songs.favorite AS song_favorite,
               songs.play_count AS song_play_count,
               songs.last_played_at AS song_last_played_at,
               songs.user_edited AS song_user_edited
        FROM playlist_songs AS ps
        INNER JOIN songs ON songs.id = ps.song_id
        ORDER BY ps.playlist_id, ps.position
        """
    )
    fun observePlaylistSongRows(): Flow<List<PlaylistSongRow>>

    @Query(
        """
        SELECT ps.playlist_id AS playlist_id, ps.position AS position,
               songs.id AS song_id,
               songs.media_store_id AS song_media_store_id,
               songs.title AS song_title,
               songs.artist AS song_artist,
               songs.album AS song_album,
               songs.album_id AS song_album_id,
               songs.duration AS song_duration,
               songs.path AS song_path,
               songs.size AS song_size,
               songs.cover_uri AS song_cover_uri,
               songs.mimeType AS song_mimeType,
               songs.year AS song_year,
               songs.track_number AS song_track_number,
               songs.date_added AS song_date_added,
               songs.date_modified AS song_date_modified,
               songs.favorite AS song_favorite,
               songs.play_count AS song_play_count,
               songs.last_played_at AS song_last_played_at,
               songs.user_edited AS song_user_edited
        FROM playlist_songs AS ps
        INNER JOIN songs ON songs.id = ps.song_id
        WHERE ps.playlist_id = :playlistId
        ORDER BY ps.position
        """
    )
    fun observePlaylistSongRowsOf(playlistId: Long): Flow<List<PlaylistSongRow>>

    /**
     * 全部歌单及其歌曲（按 position 排序）。
     *
     * 两条 Room Flow 组合：歌单元数据 + 关联行。Room 失效追踪覆盖
     * playlists / playlist_songs / songs 三张表，任一变化都会重算；
     * 空歌单保留（关联行为空），「我的」页显示 0 首。
     */
    fun observeAllWithSongs(): Flow<List<PlaylistWithSongs>> =
        observePlaylists().combine(observePlaylistSongRows()) { playlists, rows ->
            val byPlaylist = rows.groupBy { it.playlistId }
            playlists.map { playlist ->
                PlaylistWithSongs(
                    playlist = playlist,
                    songs = byPlaylist[playlist.id]?.map { it.song } ?: emptyList()
                )
            }
        }

    /** 单个歌单及其歌曲（按 position 排序；歌单不存在时发 null） */
    fun observeWithSongs(playlistId: Long): Flow<PlaylistWithSongs?> =
        observePlaylistById(playlistId).combine(observePlaylistSongRowsOf(playlistId)) { playlist, rows ->
            playlist?.let { p -> PlaylistWithSongs(p, rows.map { it.song }) }
        }

    // ---------- 关联 ----------

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addSongs(entries: List<PlaylistSongEntity>): List<Long>

    @Query("DELETE FROM playlist_songs WHERE playlist_id = :playlistId AND song_id = :songId")
    suspend fun removeSong(playlistId: Long, songId: Long)

    @Query("SELECT MAX(position) FROM playlist_songs WHERE playlist_id = :playlistId")
    suspend fun maxPosition(playlistId: Long): Int?

    @Query("DELETE FROM playlist_songs WHERE playlist_id = :playlistId")
    suspend fun clearSongs(playlistId: Long)

    /** 刷新歌单更新时间（增删歌曲后调用，「我的」按更新时间排序） */
    @Query("UPDATE playlists SET updated_at = :now WHERE id = :id")
    suspend fun touch(id: Long, now: Long = System.currentTimeMillis())

    /**
     * 新增歌曲到歌单（自动排到末尾，幂等）。
     * 真正写入后才刷新 updated_at——重复加入已存在的歌不改变排序。
     */
    @Transaction
    suspend fun addSongsAtEnd(playlistId: Long, songIds: List<Long>) {
        val start = (maxPosition(playlistId) ?: -1) + 1
        val rowIds = addSongs(
            songIds.mapIndexed { index, songId ->
                PlaylistSongEntity(
                    playlistId = playlistId,
                    songId = songId,
                    position = start + index
                )
            }
        )
        if (rowIds.any { it != -1L }) touch(playlistId)
    }

    /** 移除单曲并刷新更新时间 */
    @Transaction
    suspend fun removeSongAndTouch(playlistId: Long, songId: Long) {
        removeSong(playlistId, songId)
        touch(playlistId)
    }

    /** 清空歌单并刷新更新时间 */
    @Transaction
    suspend fun clearSongsAndTouch(playlistId: Long) {
        clearSongs(playlistId)
        touch(playlistId)
    }
}
