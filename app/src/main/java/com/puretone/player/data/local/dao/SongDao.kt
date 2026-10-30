package com.puretone.player.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
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

    /**
     * 模糊搜索：标题 / 艺术家 / 专辑。
     * P3-26：`ESCAPE '\'` + 调用方转义，避免用户输入里的 `%`/`_` 被当通配符。
     */
    @Query(
        """
        SELECT * FROM songs
        WHERE title LIKE '%' || :query || '%' ESCAPE '\'
           OR artist LIKE '%' || :query || '%' ESCAPE '\'
           OR album LIKE '%' || :query || '%' ESCAPE '\'
        ORDER BY title COLLATE NOCASE ASC
        """
    )
    fun search(query: String): Flow<List<SongEntity>>

    /**
     * 某文件夹下的歌曲（P2-11：SQL 侧前缀过滤，替代全库内存 filter）。
     * @param prefix 形如 `<已转义的文件夹路径>/%`
     */
    @Query(
        """
        SELECT * FROM songs
        WHERE path LIKE :prefix ESCAPE '\'
        ORDER BY title COLLATE NOCASE ASC
        """
    )
    fun observeSongsByFolder(prefix: String): Flow<List<SongEntity>>

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

    /**
     * 某专辑下的全部歌曲（用于专辑二级详情页）。
     * 专辑标识为 (volumeName, albumId) 复合键（P2-4，跨卷隔离）。
     *
     * P3-25：旧实现用 `((:albumId > 0 AND album_id=:albumId) OR (:albumId<=0 AND album=:album))`
     * 的 OR 双分支参数化条件，SQLite 无法对带 OR 的复合条件使用 album_id 索引，
     * 退化为全表扫描。拆成两条查询：albumId>0 走精确 id 匹配（命中 album_id 索引），
     * albumId<=0 退化为按专辑名匹配（未知专辑，极少数）。由调用方按条件分支选择。
     */
    @Query(
        """
        SELECT * FROM songs
        WHERE volume_name = :volumeName AND album_id = :albumId
        ORDER BY track_number ASC, title COLLATE NOCASE ASC
        """
    )
    fun getSongsByAlbumId(albumId: Long, volumeName: String): Flow<List<SongEntity>>

    @Query(
        """
        SELECT * FROM songs
        WHERE volume_name = :volumeName AND album = :album
        ORDER BY track_number ASC, title COLLATE NOCASE ASC
        """
    )
    fun getSongsByAlbumName(album: String, volumeName: String): Flow<List<SongEntity>>

    /** 某歌手下的全部歌曲（用于歌手详情） */
    @Query(
        """
        SELECT * FROM songs WHERE artist = :artist
        ORDER BY album COLLATE NOCASE ASC, track_number ASC, title COLLATE NOCASE ASC
        """
    )
    fun getSongsByArtist(artist: String): Flow<List<SongEntity>>

    /**
     * 幂等 upsert（P1-4 起改用 INSERT OR IGNORE + UPDATE 两段式）。
     *
     * 为什么不能再 `@Insert(onConflict = REPLACE)`：playlist_songs /
     * play_history 已对 songs.id 加 CASCADE 外键，REPLACE 的语义是
     * 「先 DELETE 再 INSERT」，会触发级联，把该歌曲的歌单成员与播放历史
     * 一并清掉（表现为"同步一次，歌单里的歌全没了"）。
     * INSERT OR IGNORE + UPDATE 不删行，子表引用完好保留。
     *
     * 调用方传入的行已合并本地字段（id / favorite / play_count /
     * last_played_at / 封面），UPDATE 整列写回不丢数据。
     */
    @Transaction
    suspend fun upsertAll(songs: List<SongEntity>) {
        val rowIds = insertIgnoreAll(songs)
        val conflicted = songs.filterIndexed { index, _ -> rowIds.getOrNull(index) == -1L }
        if (conflicted.isNotEmpty()) updateAll(conflicted)
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoreAll(songs: List<SongEntity>): List<Long>

    @Update
    suspend fun updateAll(songs: List<SongEntity>)

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

    /**
     * 缺少封面的歌曲（批量补全封面用，最多 [limit] 首）。
     *
     * P2-1：占位 URI（content://media/<任意卷>/audio/albumart/<id>，实际解析
     * 不出图）同样算「缺失」——旧版扫描会写入这种 URI，它既非 NULL 也非 ''，
     * 旧判据下这些歌永远进不了补全队列。片段与
     * [com.puretone.player.data.repository.MusicRepository.ALBUM_ART_URI_MARK]
     * 保持一致（LIKE 串里无 `%` `_` 通配符，无需 ESCAPE）。
     */
    @Query(
        """
        SELECT * FROM songs
        WHERE cover_uri IS NULL OR cover_uri = '' OR cover_uri LIKE '%/audio/albumart/%'
        ORDER BY title COLLATE NOCASE ASC
        LIMIT :limit
        """
    )
    suspend fun getSongsMissingCover(limit: Int = 200): List<SongEntity>

    /** 曲库歌曲总数（冷启动判断是否空库，避免把「尚未加载」误判为空） */
    @Query("SELECT COUNT(*) FROM songs")
    suspend fun countSongs(): Int

    /**
     * 尚未分析 ReplayGain 的歌曲（同步时按需挑一批让 ffprobe 回填）。
     * LIMIT 由调用方控制，避免一次扫描卡太久。
     */
    @Query("SELECT * FROM songs WHERE rg_analyzed = 0 LIMIT :limit")
    suspend fun getSongsNotAnalyzed(limit: Int): List<SongEntity>

    /**
     * 回填单首歌的 ReplayGain 标签并标记已分析。
     * [trackGain] / [albumGain] 可空（文件无对应标签时按 NULL 写入）。
     */
    @Query(
        """
        UPDATE songs
        SET rg_track_gain = :trackGain, rg_album_gain = :albumGain, rg_analyzed = 1
        WHERE id = :id
        """
    )
    suspend fun updateReplayGain(id: Long, trackGain: Float?, albumGain: Float?)

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
