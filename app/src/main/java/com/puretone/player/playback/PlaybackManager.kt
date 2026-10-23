package com.puretone.player.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
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

    private val songCache = HashMap<Long, SongEntity>()

    private var sleepTimerJob: Job? = null
    private var settingsJob: Job? = null
    private var appliedSleepMin: Int = -1
    private var apeRetryIndex: Int = -1

    /** 当前无缝播放开关（来自设置，bind 时订阅） */
    private var gaplessEnabled: Boolean = true

    private var pendingPlay: PendingPlay? = null
    private var pendingRestore: Boolean = false

    private val playerListener = object : Player.Listener {
        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            audioEffectController.attachToSession(audioSessionId)
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
            // 上一首「完整播放完成」判定：自动切歌（自然播完）或循环回到开头（re-peat）。
            // 用户手动上/下一首（SEEK）、替换队列（PLAYLIST_CHANGED）不计入完成。
            if (prevId != null && songId != null &&
                (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                    reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)
            ) {
                scope.launch { musicRepository.recordCompletion(prevId) }
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
            delay(500)
        }
    }

    /** 由 [PlaybackService] 在 onCreate 注入播放器与媒体会话 */
    fun bind(player: ExoPlayer, session: MediaSession) {
        this.player = player
        this.session = session
        player.addListener(playerListener)
        audioEffectController.attachToSession(player.audioSessionId)
        // 订阅设置：倍速 / 睡眠定时 / 无缝播放 实时生效（决策 D1 MVP+）
        settingsJob = scope.launch {
            settings.settings.collect { applySettings(it) }
        }
        // 执行挂起请求
        pendingPlay?.let { req ->
            pendingPlay = null
            scope.launch { doSetQueueAndPlay(req.songs, req.startIndex, req.startPositionMs) }
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
        pendingPlay = null
        pendingRestore = false
    }

    /** 将用户设置映射到播放引擎（倍速 / 睡眠定时 / 无缝播放） */
    private fun applySettings(s: PureToneSettings) {
        gaplessEnabled = s.gapless
        setSpeed(s.playbackSpeed)
        // 仅当睡眠定时分钟数变化时才重新计时，避免改动其它设置时重置计时
        if (s.sleepTimerMinutes != appliedSleepMin) {
            appliedSleepMin = s.sleepTimerMinutes
            setSleepTimer(s.sleepTimerMinutes)
        }
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
        scope.launch { doSetQueueAndPlay(songs, startIndex, startPositionMs) }
    }

    private suspend fun doSetQueueAndPlay(
        songs: List<SongEntity>,
        startIndex: Int,
        startPositionMs: Long
    ) {
        val p = player ?: return
        if (songs.isEmpty()) return
        songCache.putAll(songs.associateBy { it.id })
        val entries = songs.map { it.toQueueEntry() }

        val apeSongs = songs.filter { it.isApe }
        if (apeSongs.isNotEmpty()) _uiState.update { it.copy(isTranscoding = true) }
        val mediaItems = try {
            buildMediaItems(songs)
        } finally {
            if (apeSongs.isNotEmpty()) _uiState.update { it.copy(isTranscoding = false) }
        }
        if (mediaItems.isEmpty()) {
            _uiState.update { it.copy(error = "无法解析播放地址") }
            return
        }

        p.setMediaItems(mediaItems, startIndex, startPositionMs)
        p.prepare()
        p.play()
        _uiState.update {
            it.copy(
                queue = entries,
                queueIndex = startIndex,
                currentSongId = songs[startIndex].id,
                durationMs = songs[startIndex].duration,
                error = null
            )
        }
        saveSessionNow()
    }

    /** 追加到队列末尾（不打断当前播放） */
    fun enqueue(songs: List<SongEntity>) {
        val p = player ?: run { ensureServiceStarted(); return }
        if (songs.isEmpty()) return
        songCache.putAll(songs.associateBy { it.id })
        val entries = songs.map { it.toQueueEntry() }
        scope.launch {
            songs.forEach { song -> if (song.isApe) apeTranscoder.ensureWav(song.id, song.path) }
            p.addMediaItems(songs.map { it.toMediaItemSuspending() })
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
        songCache.putAll(songs.associateBy { it.id })
        val entries = songs.map { it.toQueueEntry() }
        scope.launch {
            songs.forEach { song -> if (song.isApe) apeTranscoder.ensureWav(song.id, song.path) }
            val insertAt = (_uiState.value.queueIndex + 1).coerceIn(0, _uiState.value.queue.size)
            p.addMediaItems(insertAt, songs.map { it.toMediaItemSuspending() })
            _uiState.update { state ->
                val merged = state.queue.toMutableList().also { q -> q.addAll(insertAt, entries) }
                state.copy(queue = merged)
            }
        }
    }

    /** 播放队列中指定索引 */
    fun playAtIndex(index: Int) {
        val p = player ?: run { ensureServiceStarted(); return }
        if (index !in _uiState.value.queue.indices) return
        scope.launch {
            val entry = _uiState.value.queue[index]
            if (entry.isApe) {
                _uiState.update { it.copy(isTranscoding = true) }
                val wav = apeTranscoder.ensureWav(entry.songId, entry.filePath)
                _uiState.update { it.copy(isTranscoding = false) }
                if (wav != null) replaceMediaItemUri(index, wav)
            }
            p.seekTo(index, 0L)
            p.prepare()
            p.play()
        }
    }

    /** 从队列移除指定索引 */
    fun removeFromQueue(index: Int) {
        val p = player ?: return
        if (index !in _uiState.value.queue.indices) return
        p.removeMediaItem(index)
        _uiState.update { it.copy(queue = it.queue.filterIndexed { i, _ -> i != index }) }
    }

    fun clearQueue() {
        player?.clearMediaItems()
        _uiState.value = PlaybackUiState()
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

    fun setRepeatMode(mode: RepeatMode) {
        val p = player ?: return
        p.repeatMode = when (mode) {
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
        }
        _uiState.update { it.copy(repeatMode = mode) }
    }

    fun setShuffle(enabled: Boolean) {
        player?.shuffleModeEnabled = enabled
        _uiState.update { it.copy(shuffle = enabled) }
    }

    fun setSpeed(speed: Float) {
        val p = player ?: return
        p.setPlaybackSpeed(speed.coerceIn(0.25f, 3.0f))
        _uiState.update { it.copy(speed = p.playbackParameters.speed) }
    }

    /** 睡眠定时：分钟数后暂停；0 取消 */
    fun setSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        if (minutes <= 0) return
        sleepTimerJob = scope.launch {
            delay(minutes * 60_000L)
            pause()
        }
    }

    fun dismissError() = _uiState.update { it.copy(error = null) }

    // ---------- 续播 ----------

    /** 恢复上次播放会话（受 settings resumeOnStart 控制） */
    suspend fun restoreLastSession(): Boolean {
        val saved = musicRepository.loadPlaybackSession() ?: return false
        if (saved.songIds.isEmpty()) return false
        val songs = musicRepository.getSongs(saved.songIds)
        if (songs.isEmpty()) return false
        setQueueAndPlay(songs, saved.index.coerceIn(0, songs.lastIndex), saved.positionMs)
        player?.pause()
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
        _uiState.value.currentSongId?.let { id ->
            scope.launch { musicRepository.recordCompletion(id) }
        }
        saveSessionNow()
        if ((player?.mediaItemCount ?: 0) == 0) stopServiceIfIdle()
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

    private suspend fun buildMediaItems(songs: List<SongEntity>): List<MediaItem> =
        songs.map { song ->
            val uri = if (song.isApe) {
                apeTranscoder.ensureWav(song.id, song.path)?.let { Uri.fromFile(it) }
            } else {
                mediaStoreUri(song)
            }
            song.toMediaItem(uri)
        }

    private suspend fun SongEntity.toMediaItemSuspending(): MediaItem {
        val uri = if (isApe) {
            apeTranscoder.ensureWav(id, path)?.let { Uri.fromFile(it) }
        } else {
            mediaStoreUri(this)
        }
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

    private fun warmUpFollowingApe() {
        val queue = _uiState.value.queue
        val current = _uiState.value.queueIndex
        val following = queue.drop(current + 1).filter { it.isApe }
        if (following.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            following.forEach { apeTranscoder.ensureWav(it.songId, it.filePath) }
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
