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
    // P3-13：初始态与设置默认值对齐为 ALL（列表循环），避免 UI 首次渲染
    // 显示「顺序播放」图标但底层实际是放完即停（OFF）。
    val repeatMode: RepeatMode = RepeatMode.ALL,
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

    /** 播放会话保存的去抖任务（P3-20：连续暂停/播放时取消上一个，避免协程堆叠） */
    private var saveSessionJob: Job? = null

    /**
     * 队列命令代次（P1-3）：每次「设置队列」自增。
     * 异步任务在挂起恢复后比对代次，过期则放弃写入，防止旧队列覆盖新队列。
     */
    private var queueGeneration: Int = 0

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
                // P1-2：只有「真的开始听这一首」才写播放历史：
                // - AUTO / REPEAT：自然播完切下一首、单曲循环回放；
                // - PLAYLIST_CHANGED 且换了歌：用户新选了一首开始播（换队列起播）。
                // 用户手动上/下一首（SEEK）不计——连点 next 会把「最近播放 /
                // 累计听歌时长 / 活跃天数 / 日均时长」整片灌水（旧实现对所有 reason 无差别记录）。
                val isNaturalAdvance = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                    reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
                val isFreshStart = reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED &&
                    songId != prevId
                if (isNaturalAdvance || isFreshStart) {
                    scope.launch { musicRepository.recordPlay(songId) }
                }
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
            // P3-18：暂停时不需要 500ms 粒度（睡眠定时剩余也只需秒级），
            // 拉长到 2s，减少空转唤醒
            delay(if (p?.isPlaying == true) 500 else 2_000)
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
        // P3-17：防御 bind 重复调用（正常生命周期 unbind→bind 各一次，不可达）。
        // 先清上一次的 settingsJob 与监听器注册，避免重复订阅设置流 / 重复回调。
        settingsJob?.cancel()
        this.player?.removeListener(playerListener)
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
            // P1-3：显式点歌优先于挂起的续播。冷启动首帧可能已由 togglePlayPause /
            // play / playAtIndex 排了 pendingRestore（自动恢复上次会话），用户随后
            // 的点歌更新鲜——两条命令若在 bind 后并发执行，恢复出的旧队列会覆盖
            // 用户新点的歌（queueGeneration 守卫也救不了：恢复命令最后执行、
            // 代次最新，先执行的点歌反而被判过期丢弃）。
            pendingRestore = false
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

    /**
     * 播放顺序（顺序/随机/单曲）来自设置：既灌给播放器，也写回 uiState。
     *
     * P3-13：任何非 ALL/ONE 的持久化值（含历史遗留的 OFF 默认）统归并为 ALL——
     * 三态循环（顺序→随机→单曲）不包含「停止」态，OFF 在 UI 被显示为「顺序播放」循环
     * 图标却「放完即停」，图标/状态/行为割裂。规整后 SEQUENTIAL 始终表示「列表循环」，
     * 与图标一致；点击循环图标进入随机也符合三态循环预期。
     */
    private fun applyPlayOrder(s: PureToneSettings) {
        val repeat = when (s.repeatMode) {
            PureToneSettings.REPEAT_ALL -> RepeatMode.ALL
            PureToneSettings.REPEAT_ONE -> RepeatMode.ONE
            else -> RepeatMode.ALL
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
            // P2-14：两个基准的到期时刻都要能触发清理
            if (settings.hasSleepTimer(s)) {
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
            // P1-3：显式点歌优先于挂起的续播（理由同 bind）——不清理的话，
            // bind 后点歌与恢复两条命令并发，旧会话队列可能覆盖用户的新选择。
            pendingPlay = PendingPlay(songs, startIndex, startPositionMs)
            pendingRestore = false
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
        // P1-3：本次「设置队列」命令的代次。下面 APE 转码会挂起（长曲可达数十秒），
        // 期间用户完全可能又点了别的歌；本协程恢复后若发现代次已被后来的命令刷新，
        // 就说明自己是过期任务，必须放弃——否则会用旧队列覆盖掉新队列
        // （表现为「点了新歌，播的却是上一首/旧队列」）。
        val generation = ++queueGeneration
        val entries = songs.map { it.toQueueEntry() }

        // 只转码「即将播放的这一首」：整队预转码会让 20 首 APE 的专辑等几十秒才出声。
        // 其余 APE 在切歌/出错时按需转码（见 playAtIndex / handlePlayerError / warmUpFollowingApe）。
        // P3-17：索引不再裸取——越界（调用方传错）时静默放弃，而不是抛 IndexOutOfBounds。
        val startSong = songs.getOrNull(startIndex) ?: return
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
                song.toMediaItem(rawPathUri(song.path))
            } else {
                song.toMediaItem(mediaStoreUri(song))
            }
        }

        // 挂起点（APE 转码）之后重新确认自己仍是「最新」的队列命令
        if (generation != queueGeneration) return

        // P2-14：新队列生效前复位 APE 重试标记——它是按「队列内索引」记的，
        // 旧队列 index=2 的 APE 重试过一次，新队列同样 index=2 的 APE
        // 首次出错就会被误判「已重试过」而跳过转码，直接卡在 IDLE。
        apeRetryIndex = -1

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
        // P2-13：先递增代次，使任何在途的 setQueueAndPlay（如 APE 首播挂起在 ensureWav）
        // 恢复后检测到代次不符而放弃，避免用户的追加被旧队列覆盖。
        queueGeneration++
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
        // P2-13：先递增代次，使在途 setQueueAndPlay 恢复后失效，避免插播被旧队列覆盖。
        queueGeneration++
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
        // P2-13：服务未就绪时旧实现写 PendingPlay(emptyList(), index, 0L)，
        // bind 后 doSetQueueAndPlay 在 songs.isEmpty() 处直接 return → 点了队列项却无声。
        // 改为标记「待恢复会话」，服务起来后由 bind() 真正恢复上次队列。
        val p = player ?: run { pendingRestore = true; ensureServiceStarted(); return }
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
        // P2-13：先递增代次，使在途 setQueueAndPlay 恢复后失效，避免删除被旧队列还原。
        queueGeneration++
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
        // P2-14：APE 重试标记随队列清空一并复位（理由同 stop）
        apeRetryIndex = -1
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
        // P3-21：旧实现整包重置 uiState，把 repeatMode / shuffle / speed 一并打回默认，
        // 而播放器实例上的这些属性并未被重置 → UI 与真实状态不符。
        // 与 clearQueue 对齐：只清「瞬时」字段，保留用户偏好。
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
        // 让仍在途的旧队列任务失效（例如 APE 转码挂起中）
        queueGeneration++
        // P3-16：作废挂起的播放请求——否则服务重启（bind 重新执行 pendingPlay）
        // 会把这次已停止的队列又播出来，与用户「显式停止」意图相反。
        pendingPlay = null
        pendingEnqueue = null
        pendingRestore = false
        // P2-14：APE 重试标记随旧队列一并作废——否则新队列同索引的 APE
        // 首次出错时会被误判为「已重试过」，跳过转码重试直接卡在 IDLE。
        apeRetryIndex = -1
        // 显式停止即放弃续播：清会话，否则下次冷启动会把已停的队列又恢复出来
        scope.launch { musicRepository.clearPlaybackSession() }
        stopServiceIfIdle()
    }

    fun next() {
        val p = player ?: return
        if (p.hasNextMediaItem()) p.seekToNextMediaItem()
        else if (_uiState.value.repeatMode == RepeatMode.ALL && p.mediaItemCount > 0) {
            // P3-15：循环到末尾回绕时，须回到「当前播放顺序」的首位，而非 timeline 的
            // 首位——开启随机时 seekTo(0) 会跳到随机序列之外的第一首，打乱随机体验。
            //
            // 注意：Media3 1.4.1 的 ExoPlayer **没有公开的 shuffleOrder getter**
            // （只有 setShuffleOrder），review 里建议的 player.shuffleOrder 无法编译。
            // 这里改用 Timeline.getFirstWindowIndex(shuffleModeEnabled)——它正是
            // Media3 自身在 REPEAT_ALL 回绕时使用的索引（见 Timeline.getNextWindowIndex
            // 实现：命中末窗口时返回 getFirstWindowIndex(shuffleModeEnabled)），
            // 因此与播放器内部语义完全一致，且是公开 API。
            val first = p.currentTimeline.getFirstWindowIndex(p.shuffleModeEnabled)
            p.seekTo(if (first >= 0) first else 0, 0L)
        }
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

    /**
     * 恢复上次播放会话（受 settings resumeOnStart 控制，由 PlayerViewModel 首帧后调用）。
     *
     * 守卫必须放在本层（进程级单例），不能只靠 ViewModel 的实例字段：
     * - 恢复以 [autoPlay=false] 重建队列并 `pause()`，一旦在「播放器里已有内容」时
     *   执行，就会打断正在播放的歌（队列、进度、音量均被重置）；
     * - 每个导航目的地用 `hiltViewModel()` 拿到的是 **NavBackStackEntry 作用域的独立
     *   PlayerViewModel 实例**，其 init 都会跑一次恢复。于是「点迷你条进播放页」
     *   （以及进 我的 / 统计 / 分组详情 页）都会新建实例 → 二次恢复 → 自动暂停。
     *
     * 因此：播放器已有媒体项或正在播放时，一律原样返回 false。
     */
    suspend fun restoreLastSession(): Boolean {
        val p = player
        if (!canRestoreSession(playerItemCount = p?.mediaItemCount ?: 0, isPlaying = p?.isPlaying == true)) {
            return false
        }
        val saved = musicRepository.loadPlaybackSession() ?: return false
        if (saved.songIds.isEmpty()) return false
        // 按保存的顺序重建队列并保留重复项：getByIds 是 `IN (:ids)` 查询，
        // 既无 ORDER BY 也会被 SQL 去重——直接拿它恢复会顺序错位、重复歌丢失（P0-7）。
        val byId = musicRepository.getSongs(saved.songIds).associateBy { it.id }
        val songs = saved.songIds.mapNotNull { byId[it] }
        if (songs.isEmpty()) return false
        // P1-2：上面的 DB 读取是挂起点——这期间用户可能已经点了别的歌起播
        // （setQueueAndPlay 会重建队列）。起播前必须再查一次守卫：否则旧会话
        // 队列会覆盖用户的新选择，表现为「点了新歌却播出旧队列/旧歌」。
        val current = player
        if (!canRestoreSession(
                playerItemCount = current?.mediaItemCount ?: 0,
                isPlaying = current?.isPlaying == true
            )
        ) {
            return false
        }
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
        // P3-20：取消上一个去抖任务再排新的——连续切播放/暂停时不会堆一串
        // 各睡 800ms 的协程（旧实现每次都新起一个）
        saveSessionJob?.cancel()
        saveSessionJob = scope.launch { delay(800); saveSessionNow() }
    }

    private fun saveSessionNow() {
        val state = _uiState.value
        if (state.queue.isEmpty()) return
        scope.launch {
            val p = player
            val rawPosition = (p?.currentPosition ?: 0L).coerceAtLeast(0L)
            // duration 未知时 ExoPlayer 返回 C.TIME_UNSET（远小于 0），takeIf 挡掉
            val duration = p?.duration?.takeIf { it > 0 } ?: 0L
            // P2-17：STATE_ENDED 时 currentPosition 即总时长，原样存下去次
            // restoreLastSession 以 positionMs = duration 起播，seek 到结尾
            // 立即再次 ENDED——表现为「续播恢复了但什么都没发生」。
            // 统一钳到 duration-500 以内（时长未知或极短（<500ms）时保底 0）。
            val position =
                if (duration > 0L) rawPosition.coerceIn(0L, (duration - 500L).coerceAtLeast(0L))
                else rawPosition
            musicRepository.savePlaybackSession(
                songIds = state.queue.map { it.songId },
                index = state.queueIndex,
                positionMs = position
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
                // P0-2：与 doSetQueueAndPlay / playAtIndex 一致包 runCatching——
                // ensureWav 一旦抛出（IO/FFmpegKit/取消），协程会经 SupervisorJob
                // 直达未捕获异常处理器导致进程崩溃；isTranscoding 用 finally 保证
                // 一定复位，否则 UI 会永久卡在「APE 转码中…」。
                _uiState.update { it.copy(isTranscoding = true) }
                val wav = try {
                    runCatching { apeTranscoder.ensureWav(entry.songId, entry.filePath) }.getOrNull()
                } finally {
                    _uiState.update { it.copy(isTranscoding = false) }
                }
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
        val uri = if (isApe) rawPathUri(path) else mediaStoreUri(this)
        return toMediaItem(uri)
    }

    private fun mediaStoreUri(song: SongEntity): Uri =
        Uri.parse("content://media/external/audio/media/${song.mediaStoreId}")

    /**
     * 把裸文件路径转成 URI，转义 `#` / `?`（P3-23）。
     * 这两个字符会被 [Uri.parse] 误判为 fragment / query，导致路径被截断或解析异常。
     * 用于 APE 占位源（本就设计为解码失败）与「playUri 缺失」的回退路径，
     * 使错误类型更准确、避免偶发的路径截断误播。
     */
    private fun rawPathUri(path: String): Uri =
        Uri.parse(path.replace("#", "%23").replace("?", "%3F"))

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
            .setUri(playUri ?: rawPathUri(path))
            .setMediaId(id.toString())
            .setMediaMetadata(metadata)
            .build()
    }

    private fun replaceMediaItemUri(index: Int, wavFile: File) {
        val p = player ?: return
        // P0-2：转码可挂起数十秒，期间用户可能 clearQueue() / removeFromQueue() / stop()
        // 把 playlist 清空或缩短；协程恢复后若仍用旧 index 调 getMediaItemAt 会抛
        // IndexOutOfBoundsException，而该异常发生在无 try/catch 的 scope.launch 里，
        // 经 SupervisorJob 直达未捕获异常处理器 → 进程崩溃。此处做边界 + 异常双保险。
        if (index !in 0 until p.mediaItemCount) return
        runCatching {
            val updated = p.getMediaItemAt(index).buildUpon().setUri(Uri.fromFile(wavFile)).build()
            p.replaceMediaItem(index, updated)
        }.onFailure { Log.w(TAG, "replaceMediaItemUri failed at index=$index", it) }
    }

    /**
     * 预热「下一首 APE」的转码（只一首：整队预转会拖慢切片切换、
     * 并无谓占满字节上限的缓存，见 P0-5）。其余 APE 切到它们时按需转码。
     */
    private fun warmUpFollowingApe() {
        val state = _uiState.value
        val nextApe = state.queue.drop(state.queueIndex + 1).firstOrNull { it.isApe } ?: return
        scope.launch(Dispatchers.IO) {
            // P0-2：预热是 fire-and-forget，ensureWav 抛异常同样会经 SupervisorJob
            // 到达未捕获异常处理器 → 进程崩溃，必须包住
            runCatching { apeTranscoder.ensureWav(nextApe.songId, nextApe.filePath) }
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

/**
 * 续播守卫（纯函数，可单测）：只有「播放器里什么都没有」时才允许恢复会话。
 *
 * 恢复会重建队列并暂停；若播放器已有媒体项（无论正在播放还是已暂停待播），
 * 恢复都会打断用户当前状态——这正是「进播放页被自动暂停」的根因场景。
 */
internal fun canRestoreSession(playerItemCount: Int, isPlaying: Boolean): Boolean =
    playerItemCount == 0 && !isPlaying
