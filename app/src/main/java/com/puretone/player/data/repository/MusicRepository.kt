package com.puretone.player.data.repository

import com.puretone.player.data.local.PureToneDatabase
import com.puretone.player.data.local.dao.PlayHistoryDao
import com.puretone.player.data.local.dao.PlaylistDao
import com.puretone.player.data.local.dao.PlaylistWithSongs
import com.puretone.player.data.local.dao.DailyTrendRow
import com.puretone.player.data.local.dao.HourCountRow
import com.puretone.player.data.local.dao.NamedCountRow
import com.puretone.player.data.local.dao.SongDao
import com.puretone.player.data.local.dao.StatsDao
import com.puretone.player.data.local.dao.TopSongRow
import com.puretone.player.data.local.entity.PlayHistoryEntity
import com.puretone.player.data.local.entity.PlaylistEntity
import com.puretone.player.data.local.entity.SongEntity
import com.puretone.player.data.media.ScannedSong
import com.puretone.player.data.media.MediaStoreScanner
import com.puretone.player.data.settings.SettingsRepository
import com.puretone.player.util.UnknownText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import androidx.room.withTransaction
import javax.inject.Inject
import javax.inject.Singleton

/** 曲库排序方式（音乐页排序菜单） */
enum class SongSortOrder {
    TITLE,        // 按标题
    DATE_ADDED,   // 按添加时间（最新优先）
    ARTIST,       // 按歌手
    ALBUM,        // 按专辑
    DURATION,     // 按时长（长曲优先）
    PLAY_COUNT    // 按播放次数（常听优先）
}

/** 曲库同步结果 */
data class SyncResult(
    val added: Int,
    val removed: Int,
    val total: Int,
    /** 本次扫描跳过的非音乐文件数（录音 / 铃声 / 提示音等） */
    val skippedNonMusic: Int = 0
)

/**
 * 音乐数据仓库：Room + MediaStore 扫描的唯一收口。
 *
 * - 扫描结果与本地库按 [SongEntity.path] 幂等对账（保留旧 id，避免歌单/历史引用断裂）；
 * - 所有读路径返回 Flow，Room 负责失效推送；
 * - 所有写路径为 suspend，调用方自行切 IO（本仓库方法均为快速本地操作）。
 */
@Singleton
class MusicRepository @Inject constructor(
    private val db: PureToneDatabase,
    private val songDao: SongDao,
    private val playlistDao: PlaylistDao,
    private val playHistoryDao: PlayHistoryDao,
    private val statsDao: StatsDao,
    private val scanner: MediaStoreScanner,
    private val settings: SettingsRepository
) {

    // ---------- 曲库 ----------

    fun observeLibrary(): Flow<List<SongEntity>> = songDao.observeAll()

    /** 按指定排序方式观察曲库（音乐页排序菜单） */
    fun observeLibrarySorted(order: SongSortOrder): Flow<List<SongEntity>> = when (order) {
        SongSortOrder.TITLE -> songDao.observeAll()
        SongSortOrder.DATE_ADDED -> songDao.observeAllByDateAdded()
        SongSortOrder.ARTIST -> songDao.observeAllByArtist()
        SongSortOrder.ALBUM -> songDao.observeAllByAlbum()
        SongSortOrder.DURATION -> songDao.observeAllByDuration()
        SongSortOrder.PLAY_COUNT -> songDao.observeAllByPlayCount()
    }

    fun search(query: String): Flow<List<SongEntity>> = songDao.search(query)

    fun observeRecentlyPlayed(limit: Int = 50): Flow<List<SongEntity>> =
        songDao.observeRecentlyPlayed(limit)

    // ---------- 专辑 / 歌手 / 文件夹分组详情 ----------

    /** 某专辑下的歌曲（专辑二级详情页） */
    fun observeSongsByAlbum(albumId: Long, album: String): Flow<List<SongEntity>> =
        songDao.getSongsByAlbum(albumId, album)

    /** 某歌手下的歌曲 */
    fun observeSongsByArtist(artist: String): Flow<List<SongEntity>> =
        songDao.getSongsByArtist(artist)

    /** 某文件夹下的歌曲（文件夹由 path 父目录派生） */
    fun observeSongsByFolder(folder: String): Flow<List<SongEntity>> =
        observeLibrary().map { list -> list.filter { folderOf(it.path) == folder } }

    suspend fun getSong(id: Long): SongEntity? = songDao.getById(id)

    suspend fun getSongs(ids: List<Long>): List<SongEntity> = songDao.getByIds(ids)

    // ---------- 收藏 ----------

    suspend fun setFavorite(songId: Long, favorite: Boolean) =
        songDao.setFavorite(songId, favorite)

    suspend fun isFavorite(songId: Long): Boolean = songDao.isFavorite(songId)

    /** 单曲收藏状态实时观察（曲库里没有该行时返回 false） */
    fun observeFavorite(songId: Long): Flow<Boolean> =
        songDao.observeFavorite(songId).map { it == 1 }

    /** 「我喜欢」列表（Room 失效推送） */
    fun observeFavorites(): Flow<List<SongEntity>> = songDao.observeFavorites()

    /**
     * 全量同步：扫描系统媒体库并与本地 Room 对账。
     * 返回新增 / 移除 / 总数，供 UI 展示同步结果。
     *
     * 性能与一致性（P1-3）：
     * - 整体在 [Dispatchers.IO] 执行：旧实现只把 scanner.scan() 切 IO，
     *   之后的 associateBy / map / toEntity / 差集都在调用线程（Main）跑，
     *   大库同步时直接掉帧；
     * - 增量对账：与旧行比较 mediaStoreId / dateModified / size / 封面，
     *   只写入「新增或变化」的行。旧实现对全部扫描结果 REPLACE upsert，
     *   即使文件没变也重写整表 → Room 全表失效、所有 Flow 重发、UI 重新分组；
     * - upsert + 删除包在同一事务：中途失败不会留下半套数据。
     */
    suspend fun syncLibrary(): SyncResult = withContext(Dispatchers.IO) {
        val scan = scanner.scan()
        val scanned = scan.songs
        val existing = songDao.getAll()

        // 安全闸：扫描结果为空但库里已有歌曲时，不执行任何删除。
        // 否则一次授权失败/读取异常会把整个曲库清空（历史 bug：删除方向写反，
        // 传 [SongDao.deleteByPathNotIn] 导致"删音乐、留录音"）。
        if (scanned.isEmpty()) {
            return@withContext SyncResult(
                added = 0, removed = 0, total = existing.size, skippedNonMusic = scan.skippedNonMusic
            )
        }

        val existingByPath = existing.associateBy { it.path }
        val existingPaths = existingByPath.keys
        val scannedPaths = scanned.map { it.path }.toSet()

        val added = scanned.count { it.path !in existingPaths }
        // 仅这些路径应从曲库移除：存在于本地库、但已不在本次扫描结果中
        // （文件被删、或被本次过滤规则判定为非音乐，如录音）。
        val removed = existingPaths - scannedPaths

        // 保留已存在歌曲的本地 id（维持歌单 / 历史引用）、收藏与统计字段
        // （upsert 为 INSERT OR IGNORE + UPDATE 两段式，冲突行走 UPDATE，
        // 不删行；但调用方仍显式携带这些字段，避免任何写路径丢用户数据）；
        // 用户手动编辑过信息的行（user_edited）整行保留，避免 MediaStore 覆盖修正结果；
        // 封面同样要保留：本次扫描没解析到本地封面时，沿用库里已有的远程封面
        // （联网补全写入的 iTunes 封面等）——否则每次重扫都会把补全的封面清掉，
        // 表现为「补全缺失封面功能像没实现」。旧版扫描写入的 albumart 占位 URI
        // （实际解析不出图）不保留，借此清理。
        val toUpsert = scanned.mapNotNull { s ->
            val old = existingByPath[s.path]
            // 用户编辑行整行保留；未变化的行完全不写库（增量）
            if (old != null && (old.userEdited || !hasChanged(old, s))) {
                null
            } else {
                s.toEntity(
                    existingId = old?.id ?: 0L,
                    favorite = old?.favorite ?: false,
                    playCount = old?.playCount ?: 0,
                    lastPlayedAt = old?.lastPlayedAt ?: 0L,
                    preservedCoverUri = old?.coverUri?.takeIf { prev ->
                        prev.isNotBlank() && !prev.startsWith(ALBUM_ART_URI_PREFIX)
                    }
                )
            }
        }

        db.withTransaction {
            if (toUpsert.isNotEmpty()) {
                songDao.upsertAll(toUpsert)
            }
            // 分片删除（规避 SQLite 参数上限）——删除集合"之内"的路径
            removed.toList().chunked(500).forEach { chunk ->
                songDao.deleteByPathIn(chunk)
            }
        }
        SyncResult(
            added = added,
            removed = removed.size,
            total = scanned.size,
            skippedNonMusic = scan.skippedNonMusic
        )
    }

    /**
     * 扫描行与库内旧行是否有实质变化（P1-3 增量对账）：
     * mediaStoreId 变化（媒体库重扫分配新 id）、文件被改写（dateModified/size）、
     * 或封面从「无/占位」变为「可用」。标题等标签变化一定伴随文件改写，
     * 故无需逐一比对展示字段。
     */
    private fun hasChanged(old: SongEntity, s: ScannedSong): Boolean =
        old.mediaStoreId != s.mediaStoreId ||
            old.dateModified != s.dateModified ||
            old.size != s.size ||
            (s.coverUri != null && (old.coverUri == null || old.coverUri.startsWith(ALBUM_ART_URI_PREFIX)))

    // ---------- 播放历史 ----------

    suspend fun recordPlay(songId: Long) {
        // 取歌曲时长，供"累计听歌时长"估算（找不到则不记历史）
        val song = songDao.getById(songId)
        if (song != null) {
            playHistoryDao.insert(
                PlayHistoryEntity(songId = songId, playedAt = System.currentTimeMillis(), durationMs = song.duration)
            )
        }
        // 注意：此处不再累加 play_count。播放次数只统计「完整播放完成」，
        // 见 recordCompletion()——否则跳过/拖动的部分播放会污染“最爱歌曲/歌手”。
        // 历史保留 1 年：足够覆盖月度/年度趋势；「最近播放」查询本身只取最近若干条
        val yearAgo = System.currentTimeMillis() - HISTORY_RETENTION_MS
        playHistoryDao.clearOlderThan(yearAgo)
    }

    /**
     * 歌曲完整播放完成（自然播完 / 循环回到同曲）：累加 play_count。
     * 这是「最爱歌手 / 最爱歌曲」排行榜的唯一计数来源。
     */
    suspend fun recordCompletion(songId: Long) {
        songDao.incrementPlayCount(songId, System.currentTimeMillis())
    }

    // ---------- 歌曲信息编辑 / 联网匹配 ----------

    /** 更新歌曲展示信息（标题/歌手/专辑/封面/年份），保留 path 与统计字段 */
    suspend fun updateSongInfo(
        id: Long,
        title: String,
        artist: String,
        album: String,
        coverUri: String?,
        year: Int = 0
    ) = songDao.updateSongInfo(
        id = id,
        title = title.trim().ifBlank { "未知歌曲" },
        artist = artist.trim(),
        album = album.trim(),
        coverUri = coverUri,
        year = year
    )

    suspend fun updateCover(id: Long, coverUri: String?) = songDao.updateCover(id, coverUri)

    suspend fun getSongsMissingCover(limit: Int = 200): List<SongEntity> =
        songDao.getSongsMissingCover(limit)

    suspend fun countSongs(): Int = songDao.countSongs()

    // ---------- 听歌统计（只读聚合） ----------

    fun observeTotalPlays(): Flow<Long> = statsDao.observeTotalPlays()
    fun observeTotalListenMs(): Flow<Long> = statsDao.observeTotalListenMs()
    fun observeHistoryCount(): Flow<Long> = statsDao.observeHistoryCount()
    fun observePlayedSongs(): Flow<Int> = statsDao.observePlayedSongs()
    fun observeDailyTrend(sinceMs: Long): Flow<List<DailyTrendRow>> = statsDao.observeDailyTrend(sinceMs)
    fun observeTopSongs(limit: Int = 10): Flow<List<TopSongRow>> = statsDao.observeTopSongs(limit)
    fun observeTopArtists(limit: Int = 10): Flow<List<NamedCountRow>> = statsDao.observeTopArtists(limit)
    fun observeHourDistribution(): Flow<List<HourCountRow>> = statsDao.observeHourDistribution()

    suspend fun clearHistory() = playHistoryDao.clear()

    // ---------- 歌单 ----------

    fun observePlaylists(): Flow<List<PlaylistWithSongs>> =
        playlistDao.observeAllWithSongs()

    fun observePlaylist(playlistId: Long): Flow<PlaylistWithSongs?> =
        playlistDao.observeWithSongs(playlistId)

    suspend fun createPlaylist(name: String): Long =
        playlistDao.insert(PlaylistEntity(name = name.trim()))

    suspend fun renamePlaylist(playlistId: Long, name: String) =
        playlistDao.rename(playlistId, name.trim())

    /**
     * 删除歌单。playlist_songs 对 playlist_id 有 CASCADE 外键，
     * 关联行随歌单一并清除（旧版只删 playlists 行，junction 永久残留）。
     */
    suspend fun deletePlaylist(playlistId: Long) {
        playlistDao.getById(playlistId)?.let { playlistDao.delete(it) }
    }

    suspend fun addToPlaylist(playlistId: Long, songIds: List<Long>) =
        playlistDao.addSongsAtEnd(playlistId, songIds.distinct())

    suspend fun removeFromPlaylist(playlistId: Long, songId: Long) =
        playlistDao.removeSongAndTouch(playlistId, songId)

    // ---------- 续播会话 ----------

    /** 读取上次播放会话快照（可能为 null） */
    suspend fun loadPlaybackSession(): SettingsRepository.PlaybackSession? =
        settings.playbackSession.firstOrNull()

    /** 持久化当前播放会话（队列 + 索引 + 位置） */
    suspend fun savePlaybackSession(songIds: List<Long>, index: Int, positionMs: Long) =
        settings.savePlaybackSession(songIds, index, positionMs)

    /** 清空续播会话（用户主动停止/清空队列时调用，避免下次冷启动恢复已停队列） */
    suspend fun clearPlaybackSession() = settings.clearPlaybackSession()

    private fun ScannedSong.toEntity(
        existingId: Long,
        favorite: Boolean,
        playCount: Int = 0,
        lastPlayedAt: Long = 0L,
        userEdited: Boolean = false,
        preservedCoverUri: String? = null
    ) = SongEntity(
        id = existingId,
        mediaStoreId = mediaStoreId,
        title = title.ifBlank { path.substringAfterLast('/').substringBeforeLast('.') },
        // P2-9：扫描器已归一 <unknown> 等占位符；此处再兜底一次
        // （M3U 导入等其它入口可能带入占位符），空串才换「未知××」
        artist = UnknownText.normalizeArtist(artist).ifBlank { UNKNOWN_ARTIST },
        album = UnknownText.normalizeAlbum(album).ifBlank { UNKNOWN_ALBUM },
        albumId = albumId,
        duration = duration,
        path = path,
        size = size,
        // 本次扫描解析到本地封面则用之；否则沿用保留值（联网补全的远程封面）
        coverUri = coverUri ?: preservedCoverUri,
        mimeType = mimeType,
        year = year,
        trackNumber = trackNumber,
        dateAdded = dateAdded,
        dateModified = dateModified,
        favorite = favorite,
        playCount = playCount,
        lastPlayedAt = lastPlayedAt,
        userEdited = userEdited
    )

    companion object {
        const val UNKNOWN_ARTIST = "未知艺术家"
        const val UNKNOWN_ALBUM = "未知专辑"

        /**
         * 由文件绝对路径取所在文件夹（父目录）。
         * Android MediaStore 路径统一用 '/' 分隔，故按最后一次 '/' 切分即可，
         * 与平台无关（不依赖 File 的 separator）。
         */
        fun folderOf(path: String): String {
            val idx = path.lastIndexOf('/')
            return if (idx > 0) path.substring(0, idx) else path
        }

        /** MediaStore albumart 占位 URI 前缀（无内嵌封面的专辑也会生成，实际解析不出图） */
        const val ALBUM_ART_URI_PREFIX = "content://media/external/audio/albumart/"

        /** 播放历史保留 1 年（支撑月度/年度听歌趋势统计） */
        const val HISTORY_RETENTION_MS = 365L * 24 * 60 * 60 * 1000
    }
}
