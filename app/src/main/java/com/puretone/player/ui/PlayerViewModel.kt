package com.puretone.player.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.puretone.player.data.local.entity.SongEntity
import com.puretone.player.data.repository.MusicRepository
import com.puretone.player.data.settings.SettingsRepository
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
import kotlinx.coroutines.flow.first
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
    private val lyricsStore: LyricsStore,
    private val settings: SettingsRepository
) : ViewModel() {

    val state: StateFlow<PlaybackUiState> = playback.uiState

    /**
     * 是否持有当前曲目（离散字段，供根部决定迷你条显隐）。
     *
     * [state] 每 500ms tick 一次 position，根部直接订阅会以 2Hz 重组整棵导航树。
     * 根部只收本字段（仅切歌/停止时变化），position 等高频字段的收集下沉到
     * MiniPlayer / 播放页各自子树内部，把重组限制在迷你条范围内（P1-10）。
     */
    val hasCurrentSong: StateFlow<Boolean> = playback.uiState
        .map { it.currentSongId != null }
        .distinctUntilChanged()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            playback.uiState.value.currentSongId != null
        )

    /**
     * 不含高频进度字段的状态快照（P2-10）。
     *
     * [state] 每 500ms 会因 positionMs tick 发射一次；播放页根部直接订阅它，
     * 会让整屏（封面、按钮、队列）跟着 2Hz 重组——和 P1-10 在应用根部修掉的是同一个反模式。
     * 这里把 positionMs 抹平成 0 再 distinctUntilChanged：其余字段都是离散的
     * （切歌、播放/暂停、转码中、错误、队列增删、循环/随机），只在真正变化时发射。
     */
    val discreteState: StateFlow<PlaybackUiState> = playback.uiState
        .map { it.copy(positionMs = 0L) }
        .distinctUntilChanged()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            playback.uiState.value.copy(positionMs = 0L)
        )

    /**
     * 播放进度（毫秒，2Hz 高频）。
     * 只应由「真的需要逐帧推进」的叶子节点收集：进度条与歌词高亮。
     */
    val positionMs: StateFlow<Long> = playback.uiState
        .map { it.positionMs }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

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

    /** 恢复会话只尝试一次（避免配置变化/重复组合时反复恢复） */
    private var restoreAttempted = false

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
        // P0-3：「启动时续播」接线。等曲库首帧到达（Room 已可查，无论空与否），
        // 仅当设置开启时恢复上次会话；本实例只尝试一次。
        //
        // 注意（Bugfix：进播放页被自动暂停）：本类会被创建多次——根部一个，
        // 每个导航目的地各自一个（hiltViewModel() 是 NavBackStackEntry 作用域），
        // 每个实例的 init 都会走到这里。仅靠实例级 restoreAttempted 挡不住跨实例
        // 的重复恢复，而恢复是以 autoPlay=false 重建队列并 pause() 的，会打断播放。
        // 真正的权威守卫在 PlaybackManager.restoreLastSession()（canRestoreSession：
        // 播放器已有内容/正在播放时拒绝恢复）。此处只做「本实例不重复调用」的廉价去重。
        // 各目的地统一复用根部实例见 PureToneApp 的 NavHost。
        viewModelScope.launch {
            runCatching { repo.observeLibrary().first() }
            if (!restoreAttempted) {
                restoreAttempted = true
                if (settings.settings.first().resumeOnStart) {
                    playback.restoreLastSession()
                }
            }
        }
    }

    fun togglePlay() = playback.togglePlayPause()
    // P4：删除无调用方的 play()/pause()——UI 一律走 togglePlay()（播放/暂停互斥），
    // 留着两个裸入口反而容易被误用
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

    /** 清空播放队列（P2-4：队列浮层「清空」入口；保留播放模式/倍速等用户设置） */
    fun clearQueue() = playback.clearQueue()
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

    /**
     * 保存歌曲信息编辑（标题/歌手/专辑/封面/年份）。
     *
     * @param songId 被编辑歌曲的 id——必须显式传入。旧实现用 [state].currentSongId：
     *   在音乐页编辑 A 而正在播放 B 时，会把 B 覆盖成 A 的内容（P0-1 数据写错）；
     *   无播放时又静默丢弃用户输入。现在调用方（SongInfoSheet）传弹窗里那首歌的 id。
     * @return true = 写入成功；false = 失败（调用方应保留弹窗并提示，避免丢失编辑）。
     */
    suspend fun saveSongInfo(
        songId: Long,
        title: String,
        artist: String,
        album: String,
        coverUri: String?,
        year: Int
    ): Boolean {
        val ok = runCatching {
            repo.updateSongInfo(songId, title, artist, album, coverUri, year)
        }.isSuccess
        // 编辑的正是当前播放曲时，顺手刷新弹窗上方的只读摘要（Room Flow 会驱动列表/播放页）
        if (ok && songId == state.value.currentSongId) {
            _currentSong.value = runCatching { repo.getSong(songId) }.getOrNull()
        }
        return ok
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
     * 在完整曲库上下文中播放指定歌曲（使上一首/下一首可用）。
     * 供统计页「最爱歌曲」跳转使用。
     */
    fun playSongById(id: Long) {
        viewModelScope.launch {
            val library = runCatching { repo.observeLibrary().first() }.getOrNull() ?: return@launch
            // P2-18：歌曲已从曲库删除时 indexOfFirst 返回 -1——旧实现
            // `.coerceAtLeast(0)` 把它压成 0，静默播放第一首歌（统计页
            // 「最爱歌曲」点到已删歌就中招）。找不到就应该无操作。
            val index = library.indexOfFirst { it.id == id }
            if (index < 0) return@launch
            playSongs(library, index)
        }
    }

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
}
