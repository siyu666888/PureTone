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

    /** 上次清理过期播放历史的时间戳（P3-19，见 [recordPlay]） */
    @Volatile
    private var lastHistoryCleanupAt: Long = 0L

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

    /**
     * 模糊搜索（标题 / 歌手 / 专辑）。
     * P3-26：查询词里的 `%` `_` `\` 必须先转义，否则 `LIKE '%' || :q || '%'`
     * 会把用户输入当通配符（输入 `_` 匹配任意单字符、`%` 直接全表命中）。
     */
    fun search(query: String): Flow<List<SongEntity>> = songDao.search(escapeLike(query))

    fun observeRecentlyPlayed(limit: Int = 50): Flow<List<SongEntity>> =
        songDao.observeRecentlyPlayed(limit)

    // ---------- 专辑 / 歌手 / 文件夹分组详情 ----------

    /** 某专辑下的歌曲（专辑二级详情页） */
    fun observeSongsByAlbum(albumId: Long, album: String): Flow<List<SongEntity>> =
        songDao.getSongsByAlbum(albumId, album)

    /** 某歌手下的歌曲 */
    fun observeSongsByArtist(artist: String): Flow<List<SongEntity>> =
        songDao.getSongsByArtist(artist)

    /**
     * 某文件夹下的歌曲（文件夹由 path 父目录派生）。
     *
     * P2-11：改为 SQL 侧 `LIKE '<folder>/%'` 过滤——旧实现订阅整库再在内存 filter，
     * 开一次文件夹页（以及曲库每次变化）就要全表遍历一遍，大库掉帧。
     * 文件夹名同样要转义（路径里可能含 `%` `_`）。
     */
    fun observeSongsByFolder(folder: String): Flow<List<SongEntity>> =
        songDao.observeSongsByFolder("${escapeLike(folder)}/%")

    suspend fun getSong(id: Long): SongEntity? = songDao.getById(id)

    /**
     * 按 id 批量取歌（顺序与去重由调用方处理）。
     *
     * 分片原因（P0-1）：Room 的 `IN (:ids)` 把每个 id 绑定成一个 SQL 参数，
     * 而 Android ≤ 11（SQLite < 3.32）`SQLITE_MAX_VARIABLE_NUMBER = 999`，
     * 超限直接抛 SQLiteException——续播恢复大队列（> 999 首）时必崩。
     *
     * P2-6：空列表必须短路——Room 为 `IN (:ids)` 生成的 SQL 是
     * `... IN ()`，SQLite 直接语法错误抛 SQLiteException。当前调用方虽都
     * 自行判空，但这是仓库公开方法，不能把正确性寄托在调用方记得判空上。
     */
    suspend fun getSongs(ids: List<Long>): List<SongEntity> = when {
        ids.isEmpty() -> emptyList()
        ids.size <= ID_CHUNK -> songDao.getByIds(ids)
        else -> ids.chunked(ID_CHUNK).flatMap { songDao.getByIds(it) }
    }

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
    suspend fun syncLibrary(forceFull: Boolean = false): SyncResult = withContext(Dispatchers.IO) {
        val existing = songDao.getAll()
        val sinceMs = if (forceFull) 0L else settings.lastScanMs()

        // 走增量的三个前提：非强制全量、有上次时间戳、库里已有数据。
        // 最后一条是硬要求：若用户清了 App 数据（或被外部清空），时间戳可能还在，
        // 此时增量只会拉到「上次之后的变更行」，库永远补不回来——必须全量。
        val incremental = !forceFull && sinceMs > 0L && existing.isNotEmpty()

        val scanned: List<ScannedSong>
        val currentPaths: Set<String>
        val skippedNonMusic: Int
        if (incremental) {
            val result = scanner.scanChanged(sinceMs)
            scanned = result.changed
            currentPaths = result.currentPaths
            skippedNonMusic = result.skippedNonMusic
        } else {
            val result = scanner.scan()
            scanned = result.songs
            currentPaths = HashSet<String>(scanned.size).apply { scanned.mapTo(this) { it.path } }
            skippedNonMusic = result.skippedNonMusic
        }

        // 安全闸：媒体库里一个有效音乐文件都读不到时，不执行任何删除。
        // 否则一次授权失败/读取异常会把整个曲库清空（历史 bug：删除方向写反，
        // 传 [SongDao.deleteByPathNotIn] 导致"删音乐、留录音"）。
        // 注意判据用的是 currentPaths（当前媒体库全量路径）而不是 scanned：
        // 增量下 scanned 为空是**正常**的（没有变更），不能据此判定异常。
        if (currentPaths.isEmpty()) {
            return@withContext SyncResult(
                added = 0, removed = 0, total = existing.size, skippedNonMusic = skippedNonMusic
            )
        }

        val existingByPath = existing.associateBy { it.path }
        val existingPaths = existingByPath.keys

        val added = scanned.count { it.path !in existingPaths }
        // 仅这些路径应从曲库移除：存在于本地库、但已不在当前媒体库中
        // （文件被删、或被本次过滤规则判定为非音乐，如录音）。
        val removed = existingPaths - currentPaths

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
                        prev.isNotBlank() && !isAlbumArtPlaceholder(prev)
                    }
                )
            }
        }

        db.withTransaction {
            if (toUpsert.isNotEmpty()) {
                // 分片写入（P0-1）：Room 的批量 @Insert/@Update 生成的是单条多值
                // INSERT/UPDATE，参数量 = 行数 × 列数（songs 表除自增 id 外 18 列），
                // API ≤ 30 的 SQLite 上限 999 → 约 55 行即超限崩溃。
                // 大库首次同步/大量文件改写是最容易踩中的路径，故必须分片。
                toUpsert.chunked(UPSERT_CHUNK).forEach { songDao.upsertAll(it) }
            }
            // 分片删除（规避 SQLite 参数上限）——删除集合"之内"的路径
            removed.toList().chunked(500).forEach { chunk ->
                songDao.deleteByPathIn(chunk)
            }
        }
        // 只在整轮同步成功后推进时间戳：中途异常则下次仍从上次时间点增量，
        // 不会把「本次没扫到的变更」永久跳过。
        settings.setLastScanMs(System.currentTimeMillis())

        SyncResult(
            added = added,
            removed = removed.size,
            total = currentPaths.size,
            skippedNonMusic = skippedNonMusic
        )
    }

    /**
     * 扫描行与库内旧行是否有实质变化（P1-3 增量对账）：
     * mediaStoreId 变化（媒体库重扫分配新 id）、文件被改写（dateModified/size）、
     * 或封面从「无/占位」变为「可用」。标题等标签变化一定伴随文件改写，
     * 故无需逐一比对展示字段。
     *
     * P2-1：还有一类「变坏」也要算变化——本次仍解析不出封面（s.coverUri == null），
     * 但库里存的是 albumart 占位 URI。不判变化的话该行被增量逻辑整行跳过，
     * 占位 URI 原样留在 cover_uri（既非 NULL 也非 ''，「补全缺失封面」永远
     * 判不中，坏图常驻 UI）。判为变化后走重写路径，[preservedCoverUri] 会滤掉
     * 占位，封面落为 NULL，之后即可进入补全队列。
     */
    private fun hasChanged(old: SongEntity, s: ScannedSong): Boolean =
        old.mediaStoreId != s.mediaStoreId ||
            old.dateModified != s.dateModified ||
            old.size != s.size ||
            (s.coverUri != null && (old.coverUri == null || isAlbumArtPlaceholder(old.coverUri))) ||
            (s.coverUri == null && isAlbumArtPlaceholder(old.coverUri))

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
        // 历史保留 1 年：足够覆盖月度/年度趋势；「最近播放」查询本身只取最近若干条。
        // P3-19：旧实现每次播放都跑一次全表 DELETE（切歌频繁时写放大+全表扫描），
        // 改为至少间隔 [HISTORY_CLEANUP_INTERVAL_MS] 清理一次——保留时长是以「年」计的，
        // 晚几小时清理没有任何可观察差异。
        val now = System.currentTimeMillis()
        if (now - lastHistoryCleanupAt >= HISTORY_CLEANUP_INTERVAL_MS) {
            lastHistoryCleanupAt = now
            playHistoryDao.clearOlderThan(now - HISTORY_RETENTION_MS)
        }
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

    /**
     * 加歌到歌单末尾（幂等）。
     *
     * P3-25：先过滤掉曲库里已经不存在的 id。playlist_songs 对 songs.id 有外键
     * （v4 起带 CASCADE），插入一条指向已删除歌曲的行会直接触发约束失败；
     * 竞态下（歌单页打开时后台同步刚删掉某首歌）这就是一次崩溃。
     * [getSongs] 内部已按 SQLite 参数上限分片，大歌单也安全。
     */
    suspend fun addToPlaylist(playlistId: Long, songIds: List<Long>) {
        val distinct = songIds.distinct()
        if (distinct.isEmpty()) return
        val validIds = getSongs(distinct).map { it.id }
        if (validIds.isEmpty()) return
        playlistDao.addSongsAtEnd(playlistId, validIds)
    }

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

        /**
         * MediaStore albumart 占位 URI 判定片段。
         *
         * P1-7：不能写死 `content://media/external/...`——API 29+ 扫描写入的 URI 里
         * 卷名是 `external_primary` 或 SD 卡卷名（`content://media/<卷名>/audio/albumart/`），
         * 只匹配 external 会让 SD 卡上的占位 URI 被当成「真实封面」保留下来，
         * 结果是坏图常驻 + 「补全缺失封面」永远判不中。这里只认关键路径片段。
         */
        const val ALBUM_ART_URI_MARK = "/audio/albumart/"

        /** 是否 MediaStore albumart 占位 URI（可能为任意卷） */
        fun isAlbumArtPlaceholder(uri: String?): Boolean =
            uri != null && uri.contains(ALBUM_ART_URI_MARK)

        /**
         * SQL `IN (:ids)` 分片大小（P0-1）。
         * SQLite 变量上限 999（Android ≤ 11），留足余量取 900。
         */
        const val ID_CHUNK = 900

        /**
         * 批量 upsert 分片大小（P0-1）。
         * songs 表除自增 id 外 18 列，多值 INSERT 参数量 = 行数 × 18，
         * 999 / 18 ≈ 55，取 50 留足余量。
         */
        const val UPSERT_CHUNK = 50

        /**
         * LIKE 通配符转义（P3-26）：用户输入或文件路径里可能含 `%` `_` `\`，
         * 直接拼进 LIKE 会匹配到意外结果（甚至全表）。
         * 配套 SQL 必须写 `ESCAPE '\'`。
         */
        fun escapeLike(value: String): String = value
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")

        /** 播放历史保留 1 年（支撑月度/年度听歌趋势统计） */
        const val HISTORY_RETENTION_MS = 365L * 24 * 60 * 60 * 1000

        /** 过期历史清理的最小间隔（P3-19）：1 小时 */
        const val HISTORY_CLEANUP_INTERVAL_MS = 60L * 60 * 1000
    }
}
