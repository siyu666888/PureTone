package com.puretone.player.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.puretone.player.data.local.entity.SongEntity
import com.puretone.player.data.repository.MusicRepository
import com.puretone.player.data.repository.SongSortOrder
import com.puretone.player.data.repository.SyncResult
import com.puretone.player.domain.grouping.AlbumSummary
import com.puretone.player.domain.grouping.ArtistSummary
import com.puretone.player.domain.grouping.FolderSummary
import com.puretone.player.domain.grouping.groupAlbums
import com.puretone.player.domain.grouping.groupArtists
import com.puretone.player.domain.grouping.groupFolders
import com.puretone.player.domain.usecase.CreatePlaylistWithSongsUseCase
import com.puretone.player.domain.usecase.ExportPlaylistUseCase
import com.puretone.player.domain.usecase.ImportM3uPlaylistUseCase
import com.puretone.player.playback.PlaybackManager
import android.net.Uri
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject

/**
 * 曲库视图模型：暴露歌曲/最近播放/歌单流，封装扫描与播放动作。
 */
/** 智能歌单默认取前 N 首 */
private const val SMART_PLAYLIST_LIMIT = 50

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repo: MusicRepository,
    private val playback: PlaybackManager,
    private val importM3uUseCase: ImportM3uPlaylistUseCase,
    private val exportPlaylistUseCase: ExportPlaylistUseCase,
    private val createPlaylistWithSongsUseCase: CreatePlaylistWithSongsUseCase
) : ViewModel() {

    /** 音乐页排序方式（默认按标题） */
    private val _sortOrder = MutableStateFlow(SongSortOrder.TITLE)
    val sortOrder: StateFlow<SongSortOrder> = _sortOrder

    fun setSortOrder(order: SongSortOrder) { _sortOrder.value = order }

    /** 曲库（随排序方式动态切换） */
    val library: StateFlow<List<SongEntity>> = _sortOrder
        .flatMapLatest { repo.observeLibrarySorted(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentlyPlayed: StateFlow<List<SongEntity>> = repo.observeRecentlyPlayed(50)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 智能歌单：最近添加（最近入库的 N 首，按下库时间倒序） */
    val recentlyAdded: StateFlow<List<SongEntity>> = repo
        .observeLibrarySorted(SongSortOrder.DATE_ADDED)
        .map { it.take(SMART_PLAYLIST_LIMIT) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 智能歌单：最常播放 Top N（按 play_count 倒序） */
    val topSongs: StateFlow<List<SongEntity>> = repo
        .observeLibrarySorted(SongSortOrder.PLAY_COUNT)
        .map { it.take(SMART_PLAYLIST_LIMIT) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val playlists = repo.observePlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val isScanning = MutableStateFlow(false)
    val lastSync = MutableStateFlow<SyncResult?>(null)

    /** 扫描互斥：避免自动扫描与手动扫描并发跑两遍全库同步（P0-6） */
    private val scanMutex = Mutex()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    /**
     * 搜索结果（P1-3：走 SQL LIKE 查询 + 250ms 防抖）。
     * 旧实现在 Main 上对全库内存 filter 且无防抖：每敲一个字符都遍历整库，
     * 大库输入时明显掉帧；SongDao.search 反而没人用。
     */
    val searchResults: StateFlow<List<SongEntity>> = _searchQuery
        .debounce(250)
        .flatMapLatest { q ->
            if (q.isBlank()) flowOf(emptyList()) else repo.search(q.trim())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 「我喜欢」收藏列表 */
    val favorites = repo.observeFavorites()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 专辑分组（封面网格墙 / 二级详情页）。flowOn(Default)：分组计算不占主线程（P1-3） */
    val albums: StateFlow<List<AlbumSummary>> = repo.observeLibrary()
        .map { groupAlbums(it) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 歌手分组 */
    val artists: StateFlow<List<ArtistSummary>> = repo.observeLibrary()
        .map { groupArtists(it) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 文件夹分组 */
    val folders: StateFlow<List<FolderSummary>> = repo.observeLibrary()
        .map { groupFolders(it) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * 分组详情页取歌：type ∈ {album, artist, folder}，key 为分组标识。
     * album 的 key 形如 "volumeName|albumId|album"（P2-4：卷名隔离同名 album_id）。
     */
    fun observeGroupSongs(type: String, key: String): Flow<List<SongEntity>> = when (type) {
        "album" -> {
            val seg = key.split("|", limit = 3)
            val volumeName = seg.getOrNull(0) ?: ""
            val albumId = seg.getOrNull(1)?.toLongOrNull() ?: 0L
            val album = seg.getOrNull(2) ?: ""
            repo.observeSongsByAlbum(albumId, album, volumeName)
        }
        "artist" -> repo.observeSongsByArtist(key)
        else -> repo.observeSongsByFolder(key)
    }

    fun setSearchQuery(q: String) { _searchQuery.value = q }

    /**
     * 同步曲库。互斥保护（P0-6）：冷启动自动扫描与用户手动扫描可能几乎同时触发，
     * 两次 syncLibrary 交错的 upsertAll / deleteByPathIn 会留下中间态；
     * 已在扫描时直接返回（isScanning 只用于 UI 展示，不作为唯一防线）。
     */
    fun scan() = viewModelScope.launch {
        if (!scanMutex.tryLock()) return@launch
        try {
            isScanning.value = true
            runCatching { repo.syncLibrary() }.onSuccess { lastSync.value = it }
            // ReplayGain 回填：同步后挑一批未分析歌曲跑 ffprobe（ffprobe 较重，分批进行，
            // 多次扫描逐步收敛，不会阻塞 UI）；失败/无标签的歌曲留待下次重试。
            runCatching { repo.analyzeReplayGainBatch() }
        } finally {
            isScanning.value = false
            scanMutex.unlock()
        }
    }

    /**
     * 冷启动自动扫描：仅当曲库确为空（直接查库计数，不用 library StateFlow——
     * 它的初始值是 emptyList，「尚未加载」会被误判为空库，导致每次冷启动都全库重扫，
     * 既慢又会反复重写数据）且本次进程还没同步过时才扫（授权后首次进入即建库）。
     */
    fun autoScanIfEmpty() = viewModelScope.launch {
        if (!isScanning.value && lastSync.value == null && repo.countSongs() == 0) scan()
    }

    /** 播放整张列表，从 index 开始 */
    fun playSongs(songs: List<SongEntity>, index: Int) = viewModelScope.launch {
        playback.setQueueAndPlay(songs, index)
    }

    fun playFromLibrary(index: Int) = playSongs(library.value, index)

    fun enqueue(songs: List<SongEntity>) = playback.enqueue(songs)

    /** 任意歌曲的收藏开关（列表长按菜单用；Room 失效自动刷新列表） */
    fun setFavorite(songId: Long, favorite: Boolean) = viewModelScope.launch {
        repo.setFavorite(songId, favorite)
    }

    fun createPlaylist(name: String) = viewModelScope.launch {
        createPlaylistWithSongsUseCase(name)
    }

    fun deletePlaylist(id: Long) = viewModelScope.launch { repo.deletePlaylist(id) }

    /** 把一批歌曲加入指定歌单（幂等，自动排到末尾） */
    fun addToPlaylist(playlistId: Long, songIds: List<Long>) = viewModelScope.launch {
        if (songIds.isNotEmpty()) repo.addToPlaylist(playlistId, songIds)
    }

    /** 新建歌单并立即把一批歌曲加入（"新建并加入"） */
    fun createPlaylistAndAdd(name: String, songIds: List<Long>) = viewModelScope.launch {
        createPlaylistWithSongsUseCase(name, songIds)
    }

    // ---------- M3U 导入 / 导出（P2：歌单 M3U 导入导出）----------

    /** M3U 操作结果提示（导入/导出成功或失败），UI 消费后清空 */
    private val _m3uMessage = MutableStateFlow<String?>(null)
    val m3uMessage: StateFlow<String?> = _m3uMessage
    fun consumeM3uMessage() { _m3uMessage.value = null }

    /**
     * 从 SAF 选中的 .m3u 文件导入：解析 → 与曲库匹配 → 新建歌单。
     * 匹配不到任何歌曲时仍给出明确提示，不静默失败。
     */
    fun importM3u(uri: Uri) = viewModelScope.launch {
        _m3uMessage.value = importM3uUseCase(uri)
    }

    /** 把歌单导出为 .m3u 到应用外部目录（免权限） */
    fun exportPlaylist(playlistId: Long) = viewModelScope.launch {
        _m3uMessage.value = exportPlaylistUseCase(playlistId)
    }
}
