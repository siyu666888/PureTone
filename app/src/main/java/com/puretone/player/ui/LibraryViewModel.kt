package com.puretone.player.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.puretone.player.data.local.entity.SongEntity
import com.puretone.player.data.repository.MusicRepository
import com.puretone.player.data.repository.SongSortOrder
import com.puretone.player.data.repository.SyncResult
import com.puretone.player.playback.PlaybackManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 曲库视图模型：暴露歌曲/最近播放/歌单流，封装扫描与播放动作。
 */
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repo: MusicRepository,
    private val playback: PlaybackManager
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

    val playlists = repo.observePlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val isScanning = MutableStateFlow(false)
    val lastSync = MutableStateFlow<SyncResult?>(null)

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    /** 搜索结果（标题/艺术家/专辑） */
    val searchResults: StateFlow<List<SongEntity>> = combine(
        repo.observeLibrary(),
        _searchQuery
    ) { all, q ->
        if (q.isBlank()) emptyList()
        else all.filter {
            it.title.contains(q, ignoreCase = true) ||
                it.artist.contains(q, ignoreCase = true) ||
                it.album.contains(q, ignoreCase = true)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val librarySize = library.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 「我喜欢」收藏列表 */
    val favorites = repo.observeFavorites()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setSearchQuery(q: String) { _searchQuery.value = q }

    fun scan() = viewModelScope.launch {
        isScanning.value = true
        runCatching { repo.syncLibrary() }.onSuccess { lastSync.value = it }
        isScanning.value = false
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
        repo.createPlaylist(name.trim().ifBlank { "新建歌单" })
    }

    fun deletePlaylist(id: Long) = viewModelScope.launch { repo.deletePlaylist(id) }

    /** 把一批歌曲加入指定歌单（幂等，自动排到末尾） */
    fun addToPlaylist(playlistId: Long, songIds: List<Long>) = viewModelScope.launch {
        if (songIds.isNotEmpty()) repo.addToPlaylist(playlistId, songIds)
    }

    /** 新建歌单并立即把一批歌曲加入（"新建并加入"） */
    fun createPlaylistAndAdd(name: String, songIds: List<Long>) = viewModelScope.launch {
        val id = repo.createPlaylist(name.trim().ifBlank { "新建歌单" })
        if (songIds.isNotEmpty()) repo.addToPlaylist(id, songIds)
    }
}
