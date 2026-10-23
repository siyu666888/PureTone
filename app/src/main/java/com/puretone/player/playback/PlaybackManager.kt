package com.puretone.player.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import com.puretone.player.data.local.entity.SongEntity
import com.puretone.player.data.repository.MusicRepository
import com.puretone.player.data.settings.PureToneSettings
import com.puretone.player.data.settings.SettingsRepository
import com.puretone.player.effect.AudioEffectController
import com.puretone.player.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** 播放循环模式 */
enum class RepeatMode { OFF, ALL, ONE }

/** 队列条目（UI 直接消费） */
data class QueueEntry(
    val songId: Long,
    val title: String,
    val artist: String,
    val coverUri: Uri?,
    val durationMs: Long,
    val isApe: Boolean,
    /** 文件绝对路径（APE 转码源；非 APE 项为空，播放走 MediaStore content URI） */
    val filePath: String
)

/** 播放 UI 状态快照 */
data class PlaybackUiState(
    val currentSongId: Long? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    /** APE 转码中（首次播放 APE 时） */
    val isTranscoding: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val shuffle: Boolean = false,
    val speed: Float = 1.0f,
    /** 睡眠定时剩余毫秒（0 = 未设置/已到期） */
    val sleepRemainingMs: Long = 0L,
    val queue: List<QueueEntry> = emptyList(),
    val queueIndex: Int = -1,
    val error: String? = null
) {
    val currentEntry: QueueEntry?
        get() = queue.getOrNull(queueIndex)
}

/** 待绑定服务后执行的播放请求（解决首播时 player 尚未创建的问题） */
private data class PendingPlay(
    val songs: List<SongEntity>,
    val startIndex: Int,
    val startPositionMs: Long
)

/**
 * 播放管理器（进程级单例，UI 层唯一接触点）。
 *
 * 设计权衡（与 MediaSessionService 生命周期对齐）：
 * - ExoPlayer 与 MediaSession 由 [PlaybackService] 创建并持有（基类 onDestroy 会 release）；
 * - 服务在 onCreate 通过 [bind] 注入实例，onDestroy 通过 [unbind] 解绑；
 * - 本单例持有 [uiState] 与命令方法，存活于整个进程，UI 仅消费它、不直接触碰 player。
 * - 首播时服务可能尚未绑定（player 为 null）：命令写入 [pendingPlay]/[pendingRestore]，
 *   待 [bind] 后自动执行，避免静默失败。
 */
@Singleton
@OptIn(UnstableApi::class)
class PlaybackManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val musicRepository: MusicRepository,
    private val apeTranscoder: ApeTranscoder,
    private val audioEffectController: AudioEffectController,
    private val settings: SettingsRepository
) {

    private var player: ExoPlayer? = null
    private var session: MediaSession? = null

    private val _uiState = MutableStateFlow(PlaybackUiState())
    val uiState: StateFlow<PlaybackUiState> = _uiState.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var sleepTimerJob: Job? = null
    private var settingsJob: Job? = null
    private var apeRetryIndex: Int = -1

    /** 「完整播放完成」去重窗口：自动切歌（AUTO/REPEAT）与末首播完（STATE_ENDED）
     *  两条路径可能指向同一首歌，5s 内只计一次，避免统计页播放次数虚高。 */
    private var lastCompletionId: Long = -1L
    private var lastCompletionAt: Long = 0L

    /** 当前无缝播放开关（来自设置，bind 时订阅） */
    private var gaplessEnabled: Boolean = true

    /** 最近一次设置快照（session 重建后重新灌入音效/播放模式用） */
    private var lastSettings: PureToneSettings = PureToneSettings()

    private var pendingPlay: PendingPlay? = null
    private var pendingEnqueue: List<SongEntity>? = null
    private var pendingRestore: Boolean = false

    private val playerListener = object : Player.Listener {
        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            audioEffectController.attachToSession(audioSessionId)
            // session 重建后把当前设置重新灌一遍（音效/播放模式不因换会话丢失）
            audioEffectController.applySettings(lastSettings)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _uiState.update { it.copy(isPlaying = isPlaying) }
            // 注意：此处绝不可调用 ensureServiceStarted()——播放器由服务创建，
            // 能收到本回调即说明服务已在运行；且历史上的 startForegroundService
            // 调用正是「播放约 30s 后闪退」的直接触发点（见 ensureServiceStarted 注释）。
            saveSessionDebounced()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            _uiState.update { it.copy(isBuffering = playbackState == Player.STATE_BUFFERING) }
            if (playbackState == Player.STATE_ENDED) handlePlaybackEnded()
            if (playbackState == Player.STATE_IDLE && (player?.mediaItemCount ?: 0) == 0) {
                stopServiceIfIdle()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val prevId = _uiState.value.currentSongId
            val songId = mediaItem?.mediaId?.toLongOrNull()
            // 上一首「完整播放完成」判定：自动切歌（自然播完）或循环回到开头（repeat-one）。
            // 用户手动上/下一首（SEEK）、替换队列（PLAYLIST_CHANGED）不计入完成。
            // recordCompletionOnce 做 5s 去重：STATE_ENDED 与过渡回调可能同时指向同一首。
            if (prevId != null && songId != null &&
                (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                    reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)
            ) {
                recordCompletionOnce(prevId)
            }
            if (songId != null) {
                _uiState.update { state ->
                    state.copy(
                        currentSongId = songId,
                        queueIndex = player?.currentMediaItemIndex ?: -1,
                        durationMs = player?.duration?.coerceAtLeast(0L) ?: state.durationMs
                    )
                }
                scope.launch { musicRepository.recordPlay(songId) }
                warmUpFollowingApe()
            }
            // 无缝播放关闭：在自动切歌（非用户手动 next）的边界处暂停，
            // 形成「曲间停顿」；Media3 默认即无缝，故 ON 时不做处理。
            if (!gaplessEnabled && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                player?.pause()
            }
        }

        override fun onPlayerError(error: PlaybackException) = handlePlayerError(error)
    }

    private val ticker = scope.launch {
        while (isActive) {
            val p = player
            if (p != null && p.isPlaying) {
                _uiState.update {
                    it.copy(
                        positionMs = p.currentPosition.coerceAtLeast(0L),
                        durationMs = p.duration.takeIf { d -> d > 0 } ?: it.durationMs
                    )
                }
            }
            // 睡眠定时剩余时间随 ticker 刷新，UI 才能显示「剩余 12:34」
            refreshSleepRemaining()
            delay(500)
        }
    }

    /** 重算睡眠定时剩余时间；到期则由 job 负责暂停与清零 */
    private fun refreshSleepRemaining() {
        val remaining = settings.sleepRemainingMs(lastSettings)
        if (remaining != _uiState.value.sleepRemainingMs) {
            _uiState.update { it.copy(sleepRemainingMs = remaining) }
        }
    }

    /** 由 [PlaybackService] 在 onCreate 注入播放器与媒体会话 */
    fun bind(player: ExoPlayer, session: MediaSession) {
        this.player = player
        this.session = session
        player.addListener(playerListener)
        audioEffectController.attachToSession(player.audioSessionId)
        // 订阅设置：倍速 / 睡眠定时 / 无缝播放 / 播放顺序 / 音效 实时生效
        settingsJob = scope.launch {
            settings.settings.collect { applySettings(it) }
        }
        // 执行挂起请求
        pendingPlay?.let { req ->
            pendingPlay = null
            scope.launch { doSetQueueAndPlay(req.songs, req.startIndex, req.startPositionMs, autoPlay = true) }
        }
        pendingEnqueue?.let { songs ->
            pendingEnqueue = null
            if (songs.isNotEmpty()) enqueue(songs)
        }
        if (pendingRestore) {
            pendingRestore = false
            scope.launch { restoreLastSession() }
        }
    }

    /** 由 [PlaybackService] 在 onDestroy 解绑 */
    fun unbind() {
        settingsJob?.cancel()
        settingsJob = null
        player?.removeListener(playerListener)
        player = null
        session = null
        audioEffectController.detach()
        pendingEnqueue = null
        pendingRestore = false
    }

    /**
     * 将用户设置映射到播放引擎与音效（唯一入口）。
     *
     * - 倍速 / 无缝播放：直接生效；
     * - 睡眠定时：按「到期时刻」恢复倒计时（进程重建后仍准确），已过期则清零；
     * - 播放顺序 / 随机：灌给 ExoPlayer 与 uiState，冷启动不再重置；
     * - 音效：整包交给 [AudioEffectController.applySettings]，设置页开关从此有真实效果。
     */
    private fun applySettings(s: PureToneSettings) {
        lastSettings = s
        gaplessEnabled = s.gapless
        setSpeed(s.playbackSpeed)
        applyPlayOrder(s)
        applySleepTimer(s)
        audioEffectController.applySettings(s)
    }

    /** 播放顺序（顺序/随机/单曲）来自设置：既灌给播放器，也写回 uiState */
    private fun applyPlayOrder(s: PureToneSettings) {
        val repeat = when (s.repeatMode) {
            PureToneSettings.REPEAT_ALL -> RepeatMode.ALL
            PureToneSettings.REPEAT_ONE -> RepeatMode.ONE
            else -> RepeatMode.OFF
        }
        if (repeat != _uiState.value.repeatMode) {
            _uiState.update { it.copy(repeatMode = repeat) }
        }
        if (s.shuffle != _uiState.value.shuffle) {
            _uiState.update { it.copy(shuffle = s.shuffle) }
        }
        player?.let { p ->
            p.repeatMode = when (repeat) {
                RepeatMode.OFF -> Player.REPEAT_MODE_OFF
                RepeatMode.ALL -> Player.REPEAT_MODE_ALL
                RepeatMode.ONE -> Player.REPEAT_MODE_ONE
            }
            p.shuffleModeEnabled = s.shuffle
        }
    }

    /**
     * 睡眠定时：以「到期时刻」为准恢复/取消。
     * 到期后由 job 暂停并把设置里的到期时刻清零——否则进程重启后
     * 会按完整时长重新倒计时（旧实现只存分钟数导致的「定时复活」bug）。
     */
    private fun applySleepTimer(s: PureToneSettings) {
        val remaining = settings.sleepRemainingMs(s)
        if (remaining <= 0L) {
            // 无定时，或已过期（例如到期瞬间进程被杀）→ 收尾并清零
            sleepTimerJob?.cancel()
            sleepTimerJob = null
            if (s.sleepTimerDeadlineMs > 0L) {
                scope.launch { settings.clearSleepTimerDeadline() }
            }
            _uiState.update { it.copy(sleepRemainingMs = 0L) }
            return
        }
        sleepTimerJob?.cancel()
        sleepTimerJob = scope.launch {
            delay(remaining)
            pause()
            // 关键：触发后清零，防止重启后再次倒计时
            scope.launch { settings.setSleepTimerMinutes(0) }
            _uiState.update { it.copy(sleepRemainingMs = 0L) }
        }
        _uiState.update { it.copy(sleepRemainingMs = remaining) }
    }

    // ---------- 队列命令 ----------

    /** 设置队列并播放 */
    fun setQueueAndPlay(songs: List<SongEntity>, startIndex: Int = 0, startPositionMs: Long = 0L) {
        val p = player
        if (p == null) {
            pendingPlay = PendingPlay(songs, startIndex, startPositionMs)
            ensureServiceStarted()
            return
        }
        scope.launch { doSetQueueAndPlay(songs, startIndex, startPositionMs, autoPlay = true) }
    }

    /**
     * 真正设置队列。[autoPlay] 显式控制是否 play()：
     * - 用户主动播放 / 续播恢复都走这里，恢复时传 false，避免「取决于队列里有没有 APE」
     *   （APE 会在 ensureWav 挂起，pause() 先于 play() 执行 → 恢复后不自动播放，非 APE 又自动播放）。
     */
    private suspend fun doSetQueueAndPlay(
        songs: List<SongEntity>,
        startIndex: Int,
        startPositionMs: Long,
        autoPlay: Boolean
    ) {
        val p = player ?: return
        if (songs.isEmpty()) return
        val entries = songs.map { it.toQueueEntry() }

        // 只转码「即将播放的这一首」：整队预转码会让 20 首 APE 的专辑等几十秒才出声。
        // 其余 APE 在切歌/出错时按需转码（见 playAtIndex / handlePlayerError / warmUpFollowingApe）。
        val startSong = songs[startIndex]
        val startUri = if (startSong.isApe) {
            _uiState.update { it.copy(isTranscoding = true) }
            val wav = runCatching { apeTranscoder.ensureWav(startSong.id, startSong.path) }.getOrNull()
            _uiState.update { it.copy(isTranscoding = false) }
            wav?.let { Uri.fromFile(it) }
        } else {
            mediaStoreUri(startSong)
        }
        if (startUri == null) {
            _uiState.update { it.copy(error = "无法解析播放地址") }
            return
        }

        // 其余项先按「非 APE 直连、APE 占位源路径」入库；切到它们时再按需转码替换 URI。
        val mediaItems = songs.mapIndexed { index, song ->
            if (index == startIndex) {
                song.toMediaItem(startUri)
            } else if (song.isApe) {
                song.toMediaItem(Uri.parse(song.path))
            } else {
                song.toMediaItem(mediaStoreUri(song))
            }
        }

        p.setMediaItems(mediaItems, startIndex, startPositionMs)
        p.prepare()
        if (autoPlay) p.play() else p.pause()
        _uiState.update {
            it.copy(
                queue = entries,
                queueIndex = startIndex,
                currentSongId = startSong.id,
                durationMs = startSong.duration,
                error = null
            )
        }
        saveSessionNow()
        if (!autoPlay) warmUpFollowingApe()
    }

    /** 追加到队列末尾（不打断当前播放）；player 未就绪时挂起，bind 后执行 */
    fun enqueue(songs: List<SongEntity>) {
        val p = player
        if (p == null) {
            pendingEnqueue = (pendingEnqueue ?: emptyList()) + songs
            ensureServiceStarted()
            return
        }
        if (songs.isEmpty()) return
        val entries = songs.map { it.toQueueEntry() }
        scope.launch {
            p.addMediaItems(songs.map { it.toMediaItemLazy() })
            _uiState.update { it.copy(queue = it.queue + entries) }
        }
    }

    /**
     * 插到当前曲目之后优先播放（不打断当前播放）。
     *
     * 队列为空或尚未起播时退化为 [setQueueAndPlay]，保证首次操作也能出声。
     */
    fun playNext(songs: List<SongEntity>) {
        val p = player
        if (p == null || _uiState.value.queue.isEmpty()) {
            setQueueAndPlay(songs)
            return
        }
        if (songs.isEmpty()) return
        val entries = songs.map { it.toQueueEntry() }
        scope.launch {
            val insertAt = (_uiState.value.queueIndex + 1).coerceIn(0, _uiState.value.queue.size)
            p.addMediaItems(insertAt, songs.map { it.toMediaItemLazy() })
            _uiState.update { state ->
                val merged = state.queue.toMutableList().also { q -> q.addAll(insertAt, entries) }
                state.copy(queue = merged)
            }
        }
    }

    /** 播放队列中指定索引（APE 按需转码后替换 URI） */
    fun playAtIndex(index: Int) {
        val p = player ?: run { pendingPlay = PendingPlay(emptyList(), index, 0L); ensureServiceStarted(); return }
        if (index !in _uiState.value.queue.indices) return
        scope.launch {
            val entry = _uiState.value.queue[index]
            if (entry.isApe) {
                _uiState.update { it.copy(isTranscoding = true) }
                val wav = runCatching { apeTranscoder.ensureWav(entry.songId, entry.filePath) }.getOrNull()
                _uiState.update { it.copy(isTranscoding = false) }
                if (wav != null) replaceMediaItemUri(index, wav)
            }
            p.seekTo(index, 0L)
            p.prepare()
            p.play()
        }
    }

    /**
     * 从队列移除指定索引。
     * 必须同步修正 [PlaybackUiState.queueIndex]：删除当前曲之前的条目后
     * player.currentMediaItemIndex 已左移，UI 若按旧索引取 currentEntry 会显示错歌。
     */
    fun removeFromQueue(index: Int) {
        val p = player ?: return
        if (index !in _uiState.value.queue.indices) return
        val currentIndex = p.currentMediaItemIndex
        p.removeMediaItem(index)
        _uiState.update { state ->
            val newQueue = state.queue.filterIndexed { i, _ -> i != index }
            val newIndex = when {
                index < currentIndex -> currentIndex - 1
                index == currentIndex -> currentIndex.coerceAtMost(newQueue.lastIndex)
                else -> currentIndex
            }
            state.copy(queue = newQueue, queueIndex = newIndex)
        }
    }

    /**
     * 清空队列。只重置「瞬时」字段，保留用户偏好（播放顺序/随机/倍速），
     * 否则 UI 显示的播放顺序会和播放器真实状态不一致（历史 bug）。
     */
    fun clearQueue() {
        player?.clearMediaItems()
        _uiState.update { state ->
            state.copy(
                queue = emptyList(),
                queueIndex = -1,
                currentSongId = null,
                positionMs = 0L,
                durationMs = 0L,
                isTranscoding = false,
                isBuffering = false,
                error = null
            )
        }
        scope.launch { musicRepository.clearPlaybackSession() }
        stopServiceIfIdle()
    }

    // ---------- 播放控制 ----------

    fun togglePlayPause() {
        val p = player
        if (p == null) {
            pendingRestore = true
            ensureServiceStarted()
            return
        }
        if (p.isPlaying) pause() else play()
    }

    fun play() {
        val p = player
        if (p == null) {
            pendingRestore = true
            ensureServiceStarted()
            return
        }
        if (p.mediaItemCount == 0) {
            scope.launch { restoreLastSession() }
            return
        }
        p.play()
    }

    fun pause() {
        player?.pause()
        saveSessionNow()
    }

    fun stop() {
        player?.stop()
        player?.clearMediaItems()
        _uiState.value = PlaybackUiState()
        // 显式停止即放弃续播：清会话，否则下次冷启动会把已停的队列又恢复出来
        scope.launch { musicRepository.clearPlaybackSession() }
        stopServiceIfIdle()
    }

    fun next() {
        val p = player ?: return
        if (p.hasNextMediaItem()) p.seekToNextMediaItem()
        else if (_uiState.value.repeatMode == RepeatMode.ALL && p.mediaItemCount > 0) p.seekTo(0, 0L)
    }

    fun previous() {
        val p = player ?: return
        if (p.hasPreviousMediaItem()) p.seekToPreviousMediaItem() else p.seekTo(0L)
    }

    fun seekTo(positionMs: Long) {
        val p = player ?: return
        p.seekTo(positionMs.coerceAtLeast(0L))
        _uiState.update { it.copy(positionMs = positionMs.coerceAtLeast(0L)) }
    }

    /**
     * 循环模式：本地即时生效 + 写回 DataStore。
     * 冷启动后由 [applyPlayOrder] 从设置恢复，不再重置为 OFF（P0-3）。
     */
    fun setRepeatMode(mode: RepeatMode) {
        val p = player ?: return
        p.repeatMode = when (mode) {
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
        }
        _uiState.update { it.copy(repeatMode = mode) }
        scope.launch {
            settings.setRepeatMode(
                when (mode) {
                    RepeatMode.ALL -> PureToneSettings.REPEAT_ALL
                    RepeatMode.ONE -> PureToneSettings.REPEAT_ONE
                    RepeatMode.OFF -> PureToneSettings.REPEAT_OFF
                }
            )
        }
    }

    /** 随机播放：本地即时生效 + 写回 DataStore（P0-3） */
    fun setShuffle(enabled: Boolean) {
        player?.shuffleModeEnabled = enabled
        _uiState.update { it.copy(shuffle = enabled) }
        scope.launch { settings.setShuffle(enabled) }
    }

    fun setSpeed(speed: Float) {
        val p = player ?: return
        p.setPlaybackSpeed(speed.coerceIn(0.25f, 3.0f))
        _uiState.update { it.copy(speed = p.playbackParameters.speed) }
    }

    /**
     * 睡眠定时：只写 DataStore（分钟数 + 到期时刻）。
     * 真正的倒计时由设置流触发 [applySleepTimer] 建立/取消 job，
     * 保证「UI 设的定时」与「播放器实际定时」同源，不会出现两套计时器。
     */
    fun setSleepTimer(minutes: Int) {
        scope.launch { settings.setSleepTimerMinutes(minutes) }
    }

    fun dismissError() = _uiState.update { it.copy(error = null) }

    // ---------- 续播 ----------

    /** 恢复上次播放会话（受 settings resumeOnStart 控制，由 PlayerViewModel 首帧后调用） */
    suspend fun restoreLastSession(): Boolean {
        val saved = musicRepository.loadPlaybackSession() ?: return false
        if (saved.songIds.isEmpty()) return false
        // 按保存的顺序重建队列并保留重复项：getByIds 是 `IN (:ids)` 查询，
        // 既无 ORDER BY 也会被 SQL 去重——直接拿它恢复会顺序错位、重复歌丢失（P0-7）。
        val byId = musicRepository.getSongs(saved.songIds).associateBy { it.id }
        val songs = saved.songIds.mapNotNull { byId[it] }
        if (songs.isEmpty()) return false
        // autoPlay=false：恢复后停在当前位置，是否播交给用户（旧实现先 setQueueAndPlay
        // 再 pause()，APE 队列会在 ensureWav 挂起导致 pause 先于 play 执行，行为不确定）。
        doSetQueueAndPlay(
            songs,
            saved.index.coerceIn(0, songs.lastIndex),
            saved.positionMs,
            autoPlay = false
        )
        return true
    }

    private fun saveSessionDebounced() {
        scope.launch { delay(800); saveSessionNow() }
    }

    private fun saveSessionNow() {
        val state = _uiState.value
        if (state.queue.isEmpty()) return
        scope.launch {
            musicRepository.savePlaybackSession(
                songIds = state.queue.map { it.songId },
                index = state.queueIndex,
                positionMs = player?.currentPosition ?: 0L
            )
        }
    }

    // ---------- 内部 ----------

    private fun handlePlayerError(error: PlaybackException) {
        val p = player ?: return
        val index = p.currentMediaItemIndex
        val entry = _uiState.value.queue.getOrNull(index)
        if (entry != null && entry.isApe && apeRetryIndex != index) {
            apeRetryIndex = index
            scope.launch {
                _uiState.update { it.copy(isTranscoding = true) }
                val wav = apeTranscoder.ensureWav(entry.songId, entry.filePath)
                _uiState.update { it.copy(isTranscoding = false) }
                if (wav != null) {
                    replaceMediaItemUri(index, wav)
                    p.prepare()
                    p.play()
                } else {
                    _uiState.update { it.copy(error = "「${entry.title}」APE 转码失败，已跳过") }
                    if (p.hasNextMediaItem()) p.seekToNextMediaItem() else stop()
                }
            }
        } else {
            _uiState.update { it.copy(error = error.errorCodeName + ": " + (error.message ?: "播放失败")) }
        }
    }

    private fun handlePlaybackEnded() {
        // 队列最后一首自然播完（无后继可自动切换）时无 AUTO/REPEAT 过渡，
        // 在此补记「完整播放完成」，避免末首歌漏计。
        _uiState.value.currentSongId?.let { id -> recordCompletionOnce(id) }
        saveSessionNow()
        if ((player?.mediaItemCount ?: 0) == 0) stopServiceIfIdle()
    }

    /**
     * 记录「完整播放完成」（驱动统计页播放次数）。
     * [onMediaItemTransition]（AUTO/REPEAT）与本方法（STATE_ENDED）都会调用，
     * 用 5s 窗口去重，防止同一首歌被计两次。
     */
    private fun recordCompletionOnce(songId: Long) {
        val now = SystemClock.elapsedRealtime()
        if (songId == lastCompletionId && now - lastCompletionAt < 5_000L) return
        lastCompletionId = songId
        lastCompletionAt = now
        scope.launch { musicRepository.recordCompletion(songId) }
    }

    /**
     * 确保播放服务已启动（冷启动兜底）。
     *
     * 刻意使用 startService() 而非 startForegroundService()：
     * 后者自 Android 8 起带有「5 秒内必须调用 Service.startForeground()」的硬性时限，
     * 否则系统抛 ForegroundServiceDidNotStartInTimeException 直接杀进程。
     * 前台化时机由 Media3 的 MediaSessionService 自行判断（内部通知控制器 +
     * shouldRunInForeground），本 App 无从保证时限，故绝不主动 arm 这颗炸弹。
     * 前台服务类型 mediaPlayback 已在清单声明，Media3 转前台时会自带该类型。
     */
    private fun ensureServiceStarted() {
        runCatching {
            context.startService(Intent(context, PlaybackService::class.java))
        }.onFailure { Log.w(TAG, "startService failed", it) }
    }

    /** 空闲时停止服务；同样兜底，避免 stopService 抛异常导致崩溃 */
    private fun stopServiceIfIdle() {
        if ((player?.isPlaying != true) && (player?.mediaItemCount ?: 0) == 0) {
            runCatching {
                context.stopService(Intent(context, PlaybackService::class.java))
            }.onFailure { Log.w(TAG, "stopService failed", it) }
        }
    }

    private companion object {
        const val TAG = "PlaybackManager"
    }

    /**
     * 惰性构建媒体项：
     * - 非 APE：直连 MediaStore content URI（本地文件，打开即播）；
     * - APE：先以源文件路径占位，ExoPlayer 无法解码时会走
     *   [handlePlayerError] → 转码 → [replaceMediaItemUri] 重试，
     *   或由 [playAtIndex] / [warmUpFollowingApe] 提前按需转码替换。
     *
     * 这样「追加/插播」不必等待整队转码，APE 队列也能秒级入队。
     */
    private fun SongEntity.toMediaItemLazy(): MediaItem {
        val uri = if (isApe) Uri.parse(path) else mediaStoreUri(this)
        return toMediaItem(uri)
    }

    private fun mediaStoreUri(song: SongEntity): Uri =
        Uri.parse("content://media/external/audio/media/${song.mediaStoreId}")

    private fun SongEntity.toMediaItem(playUri: Uri?): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setArtworkUri(coverUri?.let { Uri.parse(it) })
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setDurationMs(duration)
            .build()
        return MediaItem.Builder()
            .setUri(playUri ?: Uri.parse(path))
            .setMediaId(id.toString())
            .setMediaMetadata(metadata)
            .build()
    }

    private fun replaceMediaItemUri(index: Int, wavFile: File) {
        val p = player ?: return
        val updated = p.getMediaItemAt(index).buildUpon().setUri(Uri.fromFile(wavFile)).build()
        p.replaceMediaItem(index, updated)
    }

    /**
     * 预热「下一首 APE」的转码（只一首：整队预转会拖慢切片切换、
     * 并无谓占满字节上限的缓存，见 P0-5）。其余 APE 切到它们时按需转码。
     */
    private fun warmUpFollowingApe() {
        val state = _uiState.value
        val nextApe = state.queue.drop(state.queueIndex + 1).firstOrNull { it.isApe } ?: return
        scope.launch(Dispatchers.IO) {
            apeTranscoder.ensureWav(nextApe.songId, nextApe.filePath)
        }
    }

    private fun SongEntity.toQueueEntry() = QueueEntry(
        songId = id,
        title = title,
        artist = artist,
        coverUri = coverUri?.let { Uri.parse(it) },
        durationMs = duration,
        isApe = isApe,
        filePath = path
    )
}
