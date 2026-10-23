package com.puretone.player.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.puretone.player.data.local.entity.SongEntity
import com.puretone.player.data.repository.MusicRepository
import com.puretone.player.lyrics.LrcParser
import com.puretone.player.lyrics.LyricsStore
import com.puretone.player.lyrics.ParsedLyrics
import com.puretone.player.playback.PlaybackManager
import com.puretone.player.playback.PlaybackUiState
import com.puretone.player.playback.PlayMode
import com.puretone.player.playback.RepeatMode
import com.puretone.player.playback.next
import com.puretone.player.playback.toPlayMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * 播放控制视图模型：桥接 UI 与 [PlaybackManager] 单例。
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val playback: PlaybackManager,
    private val repo: MusicRepository,
    private val lyricsStore: LyricsStore
) : ViewModel() {

    val state: StateFlow<PlaybackUiState> = playback.uiState

    /** 当前歌曲的解析后歌词（无歌词文件时为 null） */
    private val _lyrics = MutableStateFlow<ParsedLyrics?>(null)
    val lyrics: StateFlow<ParsedLyrics?> = _lyrics.asStateFlow()

    /**
     * 当前歌曲是否已收藏（「我喜欢」）。
     *
     * 由 Room 实时派生而非「切歌时查一次」：这样播放页按钮、MiniPlayer 按钮、
     * 音乐页长按菜单任意入口改动收藏都能同步（Room 表失效自动重发）。
     */
    val favorite: StateFlow<Boolean> = playback.uiState
        .map { it.currentSongId }
        .distinctUntilChanged()
        .flatMapLatest { id ->
            if (id == null) flowOf(false)
            else repo.observeFavorite(id).catch { emit(false) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** 当前歌曲完整实体（供歌曲信息弹窗查看/编辑；无则为 null） */
    private val _currentSong = MutableStateFlow<SongEntity?>(null)
    val currentSong: StateFlow<SongEntity?> = _currentSong.asStateFlow()

    init {
        // 只在当前歌曲变化时查库（uiState 每 500ms  tick 一次，必须去重）
        viewModelScope.launch {
            playback.uiState
                .map { it.currentSongId }
                .distinctUntilChanged()
                .collect { id ->
                    _currentSong.value = id?.let { runCatching { repo.getSong(it) }.getOrNull() }
                }
        }
    }

    fun togglePlay() = playback.togglePlayPause()
    fun play() = playback.play()
    fun pause() = playback.pause()
    fun next() = playback.next()
    fun prev() = playback.previous()
    fun seek(positionMs: Long) = playback.seekTo(positionMs)
    fun setRepeatMode(mode: RepeatMode) = playback.setRepeatMode(mode)
    fun setShuffle(enabled: Boolean) = playback.setShuffle(enabled)

    /**
     * 播放顺序三态循环：顺序播放 → 随机播放 → 单曲循环 → 顺序播放。
     * 播放页与迷你条共用。
     */
    fun cyclePlayMode() {
        val next = state.value.toPlayMode().next()
        when (next) {
            PlayMode.SEQUENTIAL -> { playback.setRepeatMode(RepeatMode.ALL); playback.setShuffle(false) }
            PlayMode.SHUFFLE -> { playback.setRepeatMode(RepeatMode.ALL); playback.setShuffle(true) }
            PlayMode.REPEAT_ONE -> { playback.setRepeatMode(RepeatMode.ONE); playback.setShuffle(false) }
        }
    }
    fun setSpeed(speed: Float) = playback.setSpeed(speed)
    fun setSleepTimer(minutes: Int) = playback.setSleepTimer(minutes)
    fun dismissError() = playback.dismissError()
    fun playAtIndex(index: Int) = playback.playAtIndex(index)
    fun removeFromQueue(index: Int) = playback.removeFromQueue(index)
    fun enqueue(songs: List<SongEntity>) = playback.enqueue(songs)

    /** 插到当前曲目之后优先播放 */
    fun playNext(song: SongEntity) = playback.playNext(listOf(song))

    /**
     * 收藏 / 取消收藏当前歌曲。
     *
     * Room 是唯一真源，写库后由 [favorite] 的观察管线推送到 UI，无需乐观更新 / 回滚。
     */
    fun toggleFavorite() {
        val id = state.value.currentSongId ?: return
        viewModelScope.launch {
            runCatching { repo.setFavorite(id, !favorite.value) }
        }
    }

    /** 保存歌曲信息编辑（标题/歌手/专辑/封面/年份），成功后刷新当前实体 */
    fun saveSongInfo(title: String, artist: String, album: String, coverUri: String?, year: Int) {
        val id = state.value.currentSongId ?: return
        viewModelScope.launch {
            runCatching { repo.updateSongInfo(id, title, artist, album, coverUri, year) }
            _currentSong.value = runCatching { repo.getSong(id) }.getOrNull()
        }
    }

    /** 重新拉取当前歌曲实体（联网匹配应用后刷新用） */
    fun refreshCurrentSong() {
        val id = state.value.currentSongId ?: return
        viewModelScope.launch {
            _currentSong.value = runCatching { repo.getSong(id) }.getOrNull()
        }
    }

    /**
     * 替换当前队列并从指定索引开始播放。
     * 浏览页点击歌曲、我的页点击歌单/最近播放均走此路径。
     */
    fun playSongs(songs: List<SongEntity>, startIndex: Int = 0) =
        playback.setQueueAndPlay(songs, startIndex.coerceIn(0, (songs.size - 1).coerceAtLeast(0)))

    /**
     * 加载歌词。查找顺序：
     * 1. 与音频同目录、同名（去扩展名）的 .lrc（用户手动放置，优先）；
     * 2. App 外部目录中下载的歌词（[LyricsStore]）。
     * 找不到或解析失败时置空，UI 据此显示「暂无歌词」。
     */
    fun loadLyrics(audioPath: String?) {
        _lyrics.value = null
        if (audioPath.isNullOrBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val file = File(audioPath)
            val dir = file.parentFile
            val base = file.nameWithoutExtension
            val candidates = buildList {
                if (dir != null) {
                    add(File(dir, "$base.lrc"))
                    add(File(dir, "$base.LRC"))
                }
                lyricsStore.existing(audioPath)?.let { add(it) }
            }
            val lrc = candidates.firstOrNull { it.exists() && it.isFile }
            _lyrics.value = if (lrc != null) {
                runCatching { LrcParser.parse(LrcParser.decode(lrc.readBytes())) }
                    .getOrDefault(ParsedLyrics.Empty)
            } else {
                null
            }
        }
    }

    /** 启动时尝试恢复上次播放会话 */
    fun tryRestore() = viewModelScope.launch { playback.restoreLastSession() }
}
