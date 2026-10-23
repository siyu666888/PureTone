package com.puretone.player.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.puretone.player.data.local.entity.SongEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SongDao {

    /** 全库歌曲（按标题排序，UI 层可再排序） */
    @Query("SELECT * FROM songs ORDER BY title COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<SongEntity>>

    /** 按添加时间倒序（最新优先） */
    @Query("SELECT * FROM songs ORDER BY date_added DESC, id DESC")
    fun observeAllByDateAdded(): Flow<List<SongEntity>>

    /** 按歌手（再按标题）排序 */
    @Query("SELECT * FROM songs ORDER BY artist COLLATE NOCASE ASC, title COLLATE NOCASE ASC")
    fun observeAllByArtist(): Flow<List<SongEntity>>

    /** 按专辑（再按标题）排序 */
    @Query("SELECT * FROM songs ORDER BY album COLLATE NOCASE ASC, title COLLATE NOCASE ASC")
    fun observeAllByAlbum(): Flow<List<SongEntity>>

    /** 按时长倒序（长曲优先） */
    @Query("SELECT * FROM songs ORDER BY duration DESC, title COLLATE NOCASE ASC")
    fun observeAllByDuration(): Flow<List<SongEntity>>

    /** 按播放次数倒序（常听优先，同次数看最近播放） */
    @Query("SELECT * FROM songs ORDER BY play_count DESC, last_played_at DESC, title COLLATE NOCASE ASC")
    fun observeAllByPlayCount(): Flow<List<SongEntity>>

    /** 全库歌曲（一次性快照，用于扫描对账） */
    @Query("SELECT * FROM songs")
    suspend fun getAll(): List<SongEntity>

    /** 模糊搜索：标题 / 艺术家 / 专辑 */
    @Query(
        """
        SELECT * FROM songs
        WHERE title LIKE '%' || :query || '%'
           OR artist LIKE '%' || :query || '%'
           OR album LIKE '%' || :query || '%'
        ORDER BY title COLLATE NOCASE ASC
        """
    )
    fun search(query: String): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE id = :id")
    suspend fun getById(id: Long): SongEntity?

    @Query("SELECT * FROM songs WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<SongEntity>

    /** 收藏 / 取消收藏 */
    @Query("UPDATE songs SET favorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean)

    /** 播放计数 +1 并刷新最近播放时间（听歌统计） */
    @Query("UPDATE songs SET play_count = play_count + 1, last_played_at = :now WHERE id = :id")
    suspend fun incrementPlayCount(id: Long, now: Long)

    /**
     * 更新歌曲信息（手动编辑 / 联网匹配结果）。
     * 仅改展示字段，不动 path / mediaStoreId / play_count / favorite。
     */
    @Query(
        """
        UPDATE songs
        SET title = :title, artist = :artist, album = :album,
            cover_uri = :coverUri, year = :year, user_edited = 1
        WHERE id = :id
        """
    )
    suspend fun updateSongInfo(
        id: Long,
        title: String,
        artist: String,
        album: String,
        coverUri: String?,
        year: Int
    )

    /** 仅更新封面（批量补全封面用） */
    @Query("UPDATE songs SET cover_uri = :coverUri WHERE id = :id")
    suspend fun updateCover(id: Long, coverUri: String?)

    @Query("SELECT favorite FROM songs WHERE id = :id")
    suspend fun isFavorite(id: Long): Boolean

    /** 单曲收藏状态实时观察（表失效自动重发；用于播放栏红心与其它入口的同步） */
    @Query("SELECT favorite FROM songs WHERE id = :id")
    fun observeFavorite(id: Long): Flow<Int>

    /** 「我喜欢」列表（按标题排序） */
    @Query("SELECT * FROM songs WHERE favorite = 1 ORDER BY title COLLATE NOCASE ASC")
    fun observeFavorites(): Flow<List<SongEntity>>

    /** 幂等 upsert：按 path 冲突则替换（mediaStoreId 可能变化） */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(songs: List<SongEntity>)

    /**
     * 删除给定路径集合内的歌曲（这些文件已不在本次扫描结果中，如被过滤的录音）。
     * 注意是 IN 而非 NOT IN：调用方传入的正是"应删除"的路径集合。
     */
    @Query("DELETE FROM songs WHERE path IN (:paths)")
    suspend fun deleteByPathIn(paths: List<String>)

    @Query("DELETE FROM songs WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM songs")
    suspend fun count(): Int

    /** 缺少封面的歌曲（批量补全封面用，最多 [limit] 首） */
    @Query(
        """
        SELECT * FROM songs
        WHERE cover_uri IS NULL OR cover_uri = ''
        ORDER BY title COLLATE NOCASE ASC
        LIMIT :limit
        """
    )
    suspend fun getSongsMissingCover(limit: Int = 200): List<SongEntity>

    /** 曲库歌曲总数（冷启动判断是否空库，避免把「尚未加载」误判为空） */
    @Query("SELECT COUNT(*) FROM songs")
    suspend fun countSongs(): Int

    /** 最近播放的歌曲（同一首歌取最近一次，倒序去重） */
    @Query(
        """
        SELECT songs.* FROM play_history h
        INNER JOIN songs ON songs.id = h.song_id
        GROUP BY songs.id
        ORDER BY MAX(h.played_at) DESC
        LIMIT :limit
        """
    )
    fun observeRecentlyPlayed(limit: Int = 50): Flow<List<SongEntity>>
}
