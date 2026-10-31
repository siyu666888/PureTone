package com.puretone.player.playback

import android.app.PendingIntent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import com.puretone.player.data.local.entity.SongEntity
import com.puretone.player.data.repository.MusicRepository
import com.puretone.player.data.settings.PureToneSettings
import com.puretone.player.data.settings.SleepTimerMode
import com.puretone.player.data.settings.SettingsRepository
import com.puretone.player.effect.AudioEffectController
import com.puretone.player.MainActivity
import com.puretone.player.playback.cast.DlnaCastManager
import com.puretone.player.widget.WidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineExceptionHandler
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
    val filePath: String,
    /** 队列内唯一实例标识（同一首歌可多次入队，拖拽重排需稳定 key） */
    val key: Long = 0L,
    /** ReplayGain 轨道增益（dB，可空） */
    val rgTrackGain: Float? = null,
    /** ReplayGain 专辑增益（dB，可空） */
    val rgAlbumGain: Float? = null
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
    /** 当前睡眠定时模式（OFF / MINUTES 倒计时 / END_OF_TRACK 播完本曲停止） */
    val sleepTimerMode: SleepTimerMode = SleepTimerMode.OFF,
    /** 睡眠定时剩余毫秒（0 = 未设置/已到期；END_OF_TRACK 模式下恒为 0，它没有时间上限） */
    val sleepRemainingMs: Long = 0L,
    val queue: List<QueueEntry> = emptyList(),
    val queueIndex: Int = -1,
    val error: String? = null
) {
    val currentEntry: QueueEntry?
        get() = queue.getOrNull(queueIndex)
}

/**
 * 播放管理器（进程级单例，UI 层唯一接触点）。
 *
 * 设计权衡（与 MediaSessionService 生命周期对齐）：
 * - ExoPlayer 与 MediaSession 由 [PlaybackService] 创建并持有（基类 onDestroy 会 release）；
 * - 服务在 onCreate 通过 [bind] 注入实例，onDestroy 通过 [unbind] 解绑；
 * - 本单例持有 [uiState] 与命令方法，存活于整个进程，UI 仅消费它、不直接触碰 player。
 * - 首播时服务可能尚未绑定（player 为 null）：命令暂存进 [PlaybackCommandGate]，
 *   待 [bind] 后自动执行，避免静默失败。
 */
@Singleton
@OptIn(UnstableApi::class)
class PlaybackManager @Inject constructor(
    private val musicRepository: MusicRepository,
    private val apeTranscoder: ApeTranscoder,
    private val audioEffectController: AudioEffectController,
    private val settings: SettingsRepository,
    private val widgetUpdater: WidgetUpdater,
    private val dlnaCast: DlnaCastManager,
    private val serviceLauncher: PlaybackServiceLauncher
) {

    private var player: ExoPlayer? = null
    private var session: MediaSession? = null

    /** ReplayGain 音频处理器（由 [PlaybackService] 创建并随播放器注入；交叉淡化时 standby 播放器另持一份） */
    private var replayGainProcessor: ReplayGainAudioProcessor? = null

    /**
     * 交叉淡化备援播放器（A 路线：双实例真重叠）。
     * 仅在淡化窗口内短暂起播下一首，与主播放器两路同时发声（音量此消彼长），平时空闲。
     */
    private var secondaryPlayer: ExoPlayer? = null
    /** 备援播放器的独立 ReplayGain 处理器（增益按各自当前曲目实时刷新） */
    private var replayGainProcessorB: ReplayGainAudioProcessor? = null
    /** 交叉淡化时长（毫秒），0 = 关闭 */
    private var crossfadeMs: Int = 0
    /** 是否正在交叉淡化（主→备 音量此消彼长） */
    private var crossfading: Boolean = false
    /** 交叉淡化音量渐变协程 */
    private var crossfadeJob: Job? = null
    /** 淡入结束后要补全到新主播放器的「后续曲目」MediaItem（保持完整队列不变量） */
    private var pendingCrossfadeRemaining: List<MediaItem> = emptyList()
    /** 当前交叉淡化正在进入的队列索引（淡入结束后更新 uiState.queueIndex 用） */
    private var crossfadeNextIndex: Int = -1
    /** 当前交叉淡化正在淡出的主播放器（STATE_ENDED 兜底收尾用） */
    private var crossfadeOutgoing: ExoPlayer? = null

    private val _uiState = MutableStateFlow(PlaybackUiState())
    val uiState: StateFlow<PlaybackUiState> = _uiState.asStateFlow()

    /**
     * APE 按需转码统一入口（P1-1 拆分第三刀）。
     *
     * 转码引擎仍是 [ApeTranscoder]（缓存 / 同曲并发去重 / LRU 都在那一层），
     * 这里只统一「转码中标记」与「异常 / 取消」语义——此前三个调用点各写一遍，
     * 只有出错重试那条用了 `try/finally`，另两处一旦中途抛出就会把 UI 永久
     * 卡在「APE 转码中…」。
     */
    private val apeResolver = ApePlaybackResolver(
        transcode = { songId, path -> apeTranscoder.ensureWav(songId, path) },
        onTranscoding = { transcoding -> _uiState.update { it.copy(isTranscoding = transcoding) } },
    )

    /**
     * 进程级协程作用域。
     *
     * 刻意挂一个 [CoroutineExceptionHandler]：本管理器大量逻辑跑在此作用域的
     * launch 里（设置流 applySettings、续播 restoreLastSession、ticker、保存会话等）。
     * 任一处若抛出未捕获异常，默认会冒泡到 Android 的默认未捕获处理器并直接杀进程——
     * 这正是「打开应用即闪退」在协程路径上的成因。这里改为「记录到崩溃日志后吞掉」，
     * 保证单点异常不会拖垮整个进程（UI 仍可正常打开，仅该次操作失败）。
     */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, t ->
                Log.e(TAG, "uncaught exception in PlaybackManager scope (swallowed to avoid crash)", t)
            }
    )

    private var sleepTimerJob: Job? = null
    private var settingsJob: Job? = null

    /**
     * 队列命令闸门：代次 / 挂起命令 / APE 重试标记三份隐式状态收敛于此（P1-1 第二轮）。
     * 以前它们散在 20 余处读写点、靠注释维持不变量，已实际产生「删歌后 APE 不转码」
     * 「清空队列后自己冒回来」「bind 后旧会话盖掉新点歌」三类缺陷。详见其类注释。
     */
    private val gate = PlaybackCommandGate()

    // 服务拉起 / 兜底 controller 已下沉到 PlaybackServiceLauncher（P1-1 拆分第二刀）：
    // 那里装的是 Android 8+ 后台启动禁令的绕行逻辑，不属于播放编排。

    /** 播放会话保存的去抖任务（P3-20：连续暂停/播放时取消上一个，避免协程堆叠） */
    private var saveSessionJob: Job? = null

    /** 队列项唯一实例序号（拖拽重排需稳定 key，同一首歌可多次入队） */
    private var queueEntrySeq: Long = 0L
    private fun nextQueueKey(): Long = ++queueEntrySeq

    /** 「完整播放完成」去重窗口：自动切歌（AUTO/REPEAT）与末首播完（STATE_ENDED）
     *  两条路径可能指向同一首歌，5s 内只计一次，避免统计页播放次数虚高。 */
    private var lastCompletionId: Long = -1L
    private var lastCompletionAt: Long = 0L

    /** 当前无缝播放开关（来自设置，bind 时订阅） */
    private var gaplessEnabled: Boolean = true

    /** 最近一次设置快照（session 重建后重新灌入音效/播放模式用） */
    private var lastSettings: PureToneSettings = PureToneSettings()

    private val playerListener = object : Player.Listener {
        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            audioEffectController.attachToSession(audioSessionId)
            // session 重建后把当前设置重新灌一遍（音效/播放模式不因换会话丢失）
            audioEffectController.applySettings(lastSettings)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _uiState.update { it.copy(isPlaying = isPlaying) }
            // 桌面小部件同步（内部去抖，连续变化只推一次 IPC）
            widgetUpdater.requestUpdate()
            // 注意：此处绝不可调用 serviceLauncher.ensureStarted()——播放器由服务创建，
            // 能收到本回调即说明服务已在运行；且历史上的 startForegroundService
            // 调用正是「播放约 30s 后闪退」的直接触发点（见 PlaybackServiceLauncher.ensureStarted 注释）。
            saveSessionDebounced()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            _uiState.update { it.copy(isBuffering = playbackState == Player.STATE_BUFFERING) }
            // 交叉淡化期间主播放器（淡出方）自然播到结尾：由渐变协程或此处兜底收尾，
            // [finalizeCrossfade] 的幂等守卫保证只执行一次。
            if (playbackState == Player.STATE_ENDED && crossfading && player === crossfadeOutgoing) {
                finalizeCrossfade()
            }
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
            // 曲目切换后立即刷新 ReplayGain 增益（取当前曲目标签 + 设置）
            applyReplayGainForCurrent()
            // 无缝播放关闭：在自动切歌（非用户手动 next）的边界处暂停，
            // 形成「曲间停顿」；Media3 默认即无缝，故 ON 时不做处理。
            if (!gaplessEnabled && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                player?.pause()
            }
            // 「播完本曲停止」到点：自然播完切下一首（AUTO）或单曲循环重头（REPEAT）。
            // SEEK（用户手动上/下一首）与 PLAYLIST_CHANGED（换队列）不算「播完」——
            // 用户主动操作说明还没打算睡。
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
            ) {
                finishEndOfTrackSleepTimer()
            }
            // 曲目变化（含清空队列导致切到空）→ 同步桌面小部件
            widgetUpdater.requestUpdate()
        }

        override fun onPlayerError(error: PlaybackException) = handlePlayerError(error)
    }

    /**
     * 备援（standby）播放器的轻量监听器。
     *
     * 差异化设计：备援播放器不参与 UI/切歌编排（淡入淡出由本类统一驱动），
     * 仅需——① 音频会话变化时把音效（均衡器/重低音/环绕）挂到它的会话上，
     * 使交叉淡化期间两路声音都受音效影响；② 错误兜底记录日志。
     * 它不会触发 [onMediaItemTransition]（交叉淡化窗口内只承载单首，不会自动续播）。
     */
    private val secondaryListener = object : Player.Listener {
        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            audioEffectController.attachToSession(audioSessionId)
            audioEffectController.applySettings(lastSettings)
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "secondary player error: ${error.errorCodeName} ${error.message}")
        }
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
            // 交叉淡化：主播放器临近本曲结尾（且存在下一首、未处于淡化中、已开启）时，
            // 提前起播备援播放器承载下一首，两路音量此消彼长形成真重叠淡化。
            if (p != null && p.isPlaying) maybeStartCrossfade(p)
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

    /**
     * 按当前曲目 + 设置的 ReplayGain 参数，刷新 [replayGainProcessor] 的线性增益。
     * 取轨道或专辑增益取决于设置模式；关闭 ReplayGain 时增益恒为 1.0（透传）。
     * 处理器逐缓冲读取增益，曲目切换时实时更新无爆音。
     */
    private fun applyReplayGainForCurrent() {
        val p = replayGainProcessor ?: return
        val s = lastSettings
        val entry = _uiState.value.currentEntry ?: return
        val rgDb = if (s.replayGainMode == PureToneSettings.RG_ALBUM) entry.rgAlbumGain else entry.rgTrackGain
        val gain = ReplayGainAudioProcessor.computeLinearGain(s.replayGainEnabled, rgDb, s.replayGainPreampDb)
        p.setGainLinear(gain)
    }

    // ---------- 交叉淡化（A 路线：双实例真重叠） ----------
    //
    // 思路：主播放器（active）正常持有完整队列并播放；临近本曲结尾时，把「下一首」交给
    // 备援播放器（secondary）提前起播，两路音量此消彼长（主 1→0、备 0→1）形成真重叠淡化；
    // 淡入结束后把剩余曲目补全到备援播放器、交换二者角色（MediaSession 指向新主），旧主复位为空备援。
    // 这样就以「最小侵入」实现了双实例真重叠：交叉淡化关闭（crossfadeMs=0，默认）时，
    // 全程走原有单播放器路径，回归无缝/曲间停顿语义，不会引入回归。

    /**
     * ticker 每 500ms 调用一次：在主播放器临近本曲结尾且存在下一首时触发淡化。
     *
     * 提前量 = crossfadeMs + 400ms：ticker 粒度 500ms，预留 400ms 防止错过边界
     * （错过后主播放器会按原路径正常切歌，仅本次无淡化，不崩溃）。
     * APE 等需异步转码的格式在边界无法精确对齐，不参与淡化（退化无缝/曲间停顿）。
     */
    private fun maybeStartCrossfade(p: ExoPlayer) {
        // 投屏守卫（M1 止血）：投屏进行中本地播放器已暂停，若本地仍被 ticker 推进到
        // 曲尾会误触发双实例交叉淡化，与 DLNA 投屏叠加后果不可预测。投屏态下直接跳过。
        if (dlnaCast.state.value.isCasting) return
        if (crossfading || crossfadeMs <= 0) return
        val duration = p.duration.takeIf { it > 0 } ?: return
        val position = p.currentPosition
        val hasNext = p.hasNextMediaItem()
        val nextIndex = p.currentMediaItemIndex + 1
        val nextEntry = _uiState.value.queue.getOrNull(nextIndex) ?: return
        // 触发决策抽取到 CrossfadePolicy（纯函数，独立单测锁定边界），行为不变
        if (!CrossfadePolicy.shouldTrigger(duration, position, crossfadeMs, hasNext, nextEntry.isApe)) return
        val remain = duration - position
        startCrossfade(p, nextIndex, nextEntry, remain)
    }

    private fun startCrossfade(p: ExoPlayer, nextIndex: Int, nextEntry: QueueEntry, remainMs: Long) {
        val sec = secondaryPlayer ?: return
        val rgB = replayGainProcessorB ?: return
        // 先取出主播放器「当前项之后」的全部 MediaItem（含即将起播的下一首），供淡入结束后补全队列。
        // 必须在 removeMediaItem 之前取，避免索引偏移。
        val remainingItems = (nextIndex until p.mediaItemCount).mapNotNull { idx ->
            runCatching { p.getMediaItemAt(idx) }.getOrNull()
        }
        if (remainingItems.isEmpty()) return
        // 主播放器移除下一首：避免其在淡出过程中自行续播（否则重复计完成 / 重复切歌）。
        runCatching { p.removeMediaItem(nextIndex) }
        // 备援播放器承载下一首，音量从 0 起步
        sec.setMediaItem(remainingItems.first())
        sec.setVolume(0f)
        sec.seekTo(0)
        sec.prepare()
        sec.play()
        // 备援播放器施加下一首的 ReplayGain（与主播放器各自独立）
        val s = lastSettings
        val rgDb = if (s.replayGainMode == PureToneSettings.RG_ALBUM) nextEntry.rgAlbumGain else nextEntry.rgTrackGain
        rgB.setGainLinear(
            ReplayGainAudioProcessor.computeLinearGain(s.replayGainEnabled, rgDb, s.replayGainPreampDb)
        )
        crossfading = true
        crossfadeOutgoing = p
        crossfadeNextIndex = nextIndex
        pendingCrossfadeRemaining = remainingItems.drop(1)
        // 步进计划抽取到 CrossfadeRamp（纯函数，独立单测锁定端点/步长/时长钳制），行为不变
        val rampMs = CrossfadeRamp.rampDurationMs(crossfadeMs, remainMs)
        crossfadeJob = scope.launch {
            val steps = CrossfadeRamp.steps(rampMs)
            val stepMs = CrossfadeRamp.stepMs(rampMs)
            repeat(steps) { i ->
                p.setVolume(CrossfadeRamp.outgoingVolume(i, steps))
                sec.setVolume(CrossfadeRamp.incomingVolume(i, steps))
                delay(stepMs)
            }
            p.setVolume(0f)
            sec.setVolume(1f)
            finalizeCrossfade()
        }
    }

    /**
     * 淡入结束（或主播放器 STATE_ENDED 兜底）：把备援提升为新主、旧主复位为空备援。
     * [crossfading] 守卫保证幂等（渐变协程与 STATE_ENDED 回调谁先到谁收尾，另一个直接返回）。
     */
    private fun finalizeCrossfade() {
        if (!crossfading) return
        val outgoing = crossfadeOutgoing ?: run { crossfading = false; return }
        val sec = secondaryPlayer ?: run { crossfading = false; return }
        val nextIndex = crossfadeNextIndex
        // 把剩余曲目补全到新主播放器，维持「主播放器持有完整剩余队列」不变量
        if (pendingCrossfadeRemaining.isNotEmpty()) {
            runCatching { sec.addMediaItems(pendingCrossfadeRemaining) }
        }
        pendingCrossfadeRemaining = emptyList()
        crossfadeNextIndex = -1
        crossfadeOutgoing = null
        // MediaSession 指向新主
        runCatching { session?.setPlayer(sec) }
        // 交换监听器：新主挂完整 playerListener，旧主（现为备援）挂轻量 secondaryListener
        outgoing.removeListener(playerListener)
        outgoing.addListener(secondaryListener)
        sec.removeListener(secondaryListener)
        sec.addListener(playerListener)
        // 更新字段：新主的 RG 处理器即原备援处理器实例
        player = sec
        replayGainProcessor = replayGainProcessorB
        // 复位旧主（备援）音量并清空，供下次淡化复用
        outgoing.setVolume(1f)
        replayGainProcessorB?.setGainLinear(1f)
        runCatching {
            outgoing.pause()
            outgoing.clearMediaItems()
        }
        crossfading = false
        crossfadeJob = null
        // 更新 UI：当前曲目切到下一首
        _uiState.update { state ->
            state.copy(
                currentSongId = sec.currentMediaItem?.mediaId?.toLongOrNull(),
                queueIndex = nextIndex,
                durationMs = sec.duration.coerceAtLeast(0L)
            )
        }
        widgetUpdater.requestUpdate()
        applyReplayGainForCurrent()
    }

    /**
     * 取消进行中的交叉淡化（用户手动切歌/拖动/换队列/停止等）。
     * 丢弃备援播放器、复位双方音量，使后续命令在「主播放器持有完整队列」的常规状态下执行。
     */
    private fun cancelCrossfade() {
        if (!crossfading) return
        crossfadeJob?.cancel()
        crossfadeJob = null
        crossfading = false
        crossfadeOutgoing = null
        crossfadeNextIndex = -1
        pendingCrossfadeRemaining = emptyList()
        secondaryPlayer?.let { sec ->
            runCatching { sec.pause() }
            runCatching { sec.clearMediaItems() }
            sec.setVolume(1f)
        }
        player?.setVolume(1f)
        replayGainProcessorB?.setGainLinear(1f)
    }

    /** 由 [PlaybackService] 在 onCreate 注入主/备播放器、媒体会话与各自的 ReplayGain 处理器 */
    fun bind(
        player: ExoPlayer,
        secondary: ExoPlayer,
        session: MediaSession,
        replayGainProcessor: ReplayGainAudioProcessor,
        replayGainProcessorB: ReplayGainAudioProcessor
    ) {
        // P3-17：防御 bind 重复调用（正常生命周期 unbind→bind 各一次，不可达）。
        // 先清上一次的 settingsJob 与监听器注册，避免重复订阅设置流 / 重复回调。
        settingsJob?.cancel()
        this.player?.removeListener(playerListener)
        this.secondaryPlayer?.removeListener(secondaryListener)
        this.player = player
        this.secondaryPlayer = secondary
        this.session = session
        this.replayGainProcessor = replayGainProcessor
        this.replayGainProcessorB = replayGainProcessorB
        player.addListener(playerListener)
        // 备援播放器只负责「挂接音效会话」与错误兜底，不驱动 UI/切歌（淡入淡出由本类统一编排）
        secondary.addListener(secondaryListener)
        audioEffectController.attachToSession(player.audioSessionId)
        // 由「后台 bind 兜底」临时持有的 controller：服务已起、命令已排入，交由
        // launcher 延迟释放（若此时服务里已有播放内容，服务自己会保持存活）。
        serviceLauncher.onServiceBound()
        // 订阅设置：倍速 / 睡眠定时 / 无缝播放 / 播放顺序 / 音效 实时生效
        settingsJob = scope.launch {
            settings.settings.collect { applySettings(it) }
        }
        // 执行挂起请求：一次性取走全部（重放期间新来的命令会留在槽里，不被这次重放吞掉）
        val pending = gate.drain()
        val playReq = pending.play
        if (playReq == null) {
            if (pending.enqueue.isNotEmpty()) enqueue(pending.enqueue)
            if (pending.restore) scope.launch { restoreLastSession() }
            return
        }
        // P1-3：显式点歌优先于挂起的续播。冷启动首帧可能已由 togglePlayPause /
        // play / playAtIndex 排了「自动恢复上次会话」，用户随后点的歌更新鲜——
        // 两条命令若在 bind 后并发执行，恢复出的旧队列会覆盖新队列（代次守卫也
        // 救不了：恢复命令最后执行、代次最新，先执行的点歌反而被判过期丢弃）。
        // 该互斥现由 PlaybackCommandGate.pendPlay 内部强制，此处不再手动清标志。
        scope.launch {
            doSetQueueAndPlay(playReq.songs, playReq.startIndex, playReq.startPositionMs, autoPlay = true)
            // 追加必须排在点歌「完成」之后：enqueue 会走 onQueueStructureChanged
            // 递增代次（作废在途的旧 setQueue）。若并发执行，首播曲是 APE 时
            // doSetQueueAndPlay 正挂在转码上，会被同一批重放命令自己作废 ——
            // 表现为「冷启动点了 APE，却只播出了随后追加的歌」。
            if (pending.enqueue.isNotEmpty()) enqueue(pending.enqueue)
        }
    }

    /** 由 [PlaybackService] 在 onDestroy 解绑 */
    fun unbind() {
        settingsJob?.cancel()
        settingsJob = null
        crossfadeJob?.cancel()
        crossfadeJob = null
        player?.removeListener(playerListener)
        secondaryPlayer?.removeListener(secondaryListener)
        cancelCrossfade()
        player = null
        secondaryPlayer = null
        session = null
        audioEffectController.detach()
        // 解绑只作废「追加」与「续播」（依赖当前会话的队列/设置），
        // 保留 pendingPlay——用户显式点的歌在服务重建后仍应执行。
        gate.clearSessionBoundPending()
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
        crossfadeMs = s.crossfadeMs
        setSpeed(s.playbackSpeed)
        applyPlayOrder(s)
        applySleepTimer(s)
        audioEffectController.applySettings(s)
        // ReplayGain：开关 / 模式 / preamp 变化时刷新当前曲目的增益
        applyReplayGainForCurrent()
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
     * 睡眠定时：按模式分派。
     *
     * 三种模式（2026-10-30 细粒度定时）共用同一份持久化状态，由 [SettingsRepository.resolveSleepTimerMode]
     * 统一判定，避免「UI 选的模式」与「播放器实际执行的模式」出现两套解释：
     * - [SleepTimerMode.MINUTES]：按「到期时刻」恢复倒计时；已过期则收尾并清零；
     * - [SleepTimerMode.END_OF_TRACK]：不排倒计时 job，结束时机由播放器回调决定（见 [finishEndOfTrackSleepTimer]）；
     * - [SleepTimerMode.OFF]：取消 job 并清理残留。
     *
     * MINUTES 到期后要把定时四字段（模式 + 分钟数 + 两个到期时刻）全部清零——
     * 否则进程重启后既会按完整时长重新倒计时（旧「定时复活」bug），设置页时段也
     * 不会回到「关」。另见 [SettingsRepository] 读取层的过期归一化。
     */
    private fun applySleepTimer(s: PureToneSettings) {
        when (settings.resolveSleepTimerMode(s)) {
            SleepTimerMode.MINUTES -> applyMinutesSleepTimer(s, settings.sleepRemainingMs(s))
            SleepTimerMode.END_OF_TRACK -> applyEndOfTrackSleepTimer()
            SleepTimerMode.OFF -> cancelSleepTimer(s)
        }
    }

    /** 倒计时型定时：以「到期时刻」为准恢复/取消（进程重建后仍准确） */
    private fun applyMinutesSleepTimer(s: PureToneSettings, remaining: Long) {
        if (remaining <= 0L) {
            // 到期时刻已过（例如到期瞬间进程被杀）→ 收尾并把残留写回全关
            clearSleepTimerJob()
            if (settings.hasSleepTimer(s)) {
                scope.launch { settings.clearSleepTimer() }
            }
            _uiState.update { it.copy(sleepTimerMode = SleepTimerMode.OFF, sleepRemainingMs = 0L) }
            return
        }
        clearSleepTimerJob()
        sleepTimerJob = scope.launch {
            delay(remaining)
            pause()
            // 关键：触发后清零，防止重启后再次倒计时
            scope.launch { settings.clearSleepTimer() }
            _uiState.update { it.copy(sleepTimerMode = SleepTimerMode.OFF, sleepRemainingMs = 0L) }
        }
        _uiState.update { it.copy(sleepTimerMode = SleepTimerMode.MINUTES, sleepRemainingMs = remaining) }
    }

    /**
     * 「播完本曲停止」：没有时间上限，只登记模式，真正的暂停时机由
     * [finishEndOfTrackSleepTimer] 在自然切歌 / 末首播完时执行。
     */
    private fun applyEndOfTrackSleepTimer() {
        clearSleepTimerJob()
        _uiState.update { it.copy(sleepTimerMode = SleepTimerMode.END_OF_TRACK, sleepRemainingMs = 0L) }
    }

    /** 关：取消倒计时并清理持久化残留 */
    private fun cancelSleepTimer(s: PureToneSettings) {
        clearSleepTimerJob()
        if (settings.hasSleepTimer(s) || s.sleepTimerMinutes > 0) {
            scope.launch { settings.clearSleepTimer() }
        }
        _uiState.update { it.copy(sleepTimerMode = SleepTimerMode.OFF, sleepRemainingMs = 0L) }
    }

    private fun clearSleepTimerJob() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
    }

    /**
     * 「播完本曲停止」到点：暂停播放并把定时归零。
     *
     * 幂等：重复调用（自动切歌回调与 STATE_ENDED 回调可能同时到达）时，
     * 第二次 [PlaybackUiState.sleepTimerMode] 已是 OFF，直接返回。
     */
    private fun finishEndOfTrackSleepTimer() {
        if (_uiState.value.sleepTimerMode != SleepTimerMode.END_OF_TRACK) return
        pause()
        _uiState.update { it.copy(sleepTimerMode = SleepTimerMode.OFF, sleepRemainingMs = 0L) }
        scope.launch { settings.clearSleepTimer() }
    }

    // ---------- 队列命令 ----------

    /** 设置队列并播放 */
    fun setQueueAndPlay(songs: List<SongEntity>, startIndex: Int = 0, startPositionMs: Long = 0L) {
        cancelCrossfade()
        val p = player
        if (p == null) {
            // P1-3：显式点歌优先于挂起的续播（理由同 bind）——互斥由 gate 内部强制，
            // 不再依赖此处手动清 pendingRestore。
            gate.pendPlay(PendingPlay(songs, startIndex, startPositionMs))
            serviceLauncher.ensureStarted()
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
        val generation = gate.nextGeneration()
        val entries = songs.map { PlaybackMediaItems.toQueueEntry(it, nextQueueKey()) }

        // 只转码「即将播放的这一首」：整队预转码会让 20 首 APE 的专辑等几十秒才出声。
        // 其余 APE 在切歌/出错时按需转码（见 playAtIndex / handlePlayerError / warmUpFollowingApe）。
        // P3-17：索引不再裸取——越界（调用方传错）时静默放弃，而不是抛 IndexOutOfBounds。
        val startSong = songs.getOrNull(startIndex) ?: return
        // 转码中标记与异常/取消语义统一由 apeResolver 负责（含 finally 复位）
        val startUri = apeResolver.resolveUri(
            songId = startSong.id,
            path = startSong.path,
            isApe = startSong.isApe,
            mediaStoreUri = { PlaybackMediaItems.mediaStoreUri(startSong) }
        )
        if (startUri == null) {
            _uiState.update { it.copy(error = "无法解析播放地址") }
            return
        }

        // 其余项先按「非 APE 直连、APE 占位源路径」入库；切到它们时再按需转码替换 URI。
        val mediaItems = songs.mapIndexed { index, song ->
            if (index == startIndex) {
                PlaybackMediaItems.toMediaItem(song, startUri)
            } else if (song.isApe) {
                PlaybackMediaItems.toMediaItem(song, PlaybackMediaItems.rawPathUri(song.path))
            } else {
                PlaybackMediaItems.toMediaItem(song, PlaybackMediaItems.mediaStoreUri(song))
            }
        }

        // 挂起点（APE 转码）之后重新确认自己仍是「最新」的队列命令
        if (!gate.isCurrent(generation)) return

        // P2-14：新队列生效前复位 APE 重试标记——它是按「队列内索引」记的，
        // 旧队列 index=2 的 APE 重试过一次，新队列同样 index=2 的 APE
        // 首次出错就会被误判「已重试过」而跳过转码，直接卡在 IDLE。
        // 用 resetApeRetry 而非 onQueueStructureChanged：后者会再递增代次，
        // 把本协程刚取的号弄失效、导致下面全部写入被跳过。
        gate.resetApeRetry()

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
        applyReplayGainForCurrent()
        if (!autoPlay) warmUpFollowingApe()
    }

    /** 追加到队列末尾（不打断当前播放）；player 未就绪时挂起，bind 后执行 */
    fun enqueue(songs: List<SongEntity>) {
        cancelCrossfade()
        val p = player
        if (p == null) {
            gate.pendEnqueue(songs)
            serviceLauncher.ensureStarted()
            return
        }
        if (songs.isEmpty()) return
        // P2-13：先作废在途的 setQueueAndPlay（如 APE 首播挂起在 ensureWav）——它恢复后
        // 检测到代次不符会放弃，避免用户的追加被旧队列覆盖。
        // 连带复位 APE 重试标记：追加本身不改已有索引，但标记与代次同属「队列状态已变」，
        // 由同一个入口维护可避免以后有人只改一半。
        gate.onQueueStructureChanged()
        val entries = songs.map { PlaybackMediaItems.toQueueEntry(it, nextQueueKey()) }
        scope.launch {
            p.addMediaItems(songs.map { PlaybackMediaItems.lazyMediaItem(it) })
            _uiState.update { it.copy(queue = it.queue + entries) }
        }
    }

    /**
     * 插到当前曲目之后优先播放（不打断当前播放）。
     *
     * 队列为空或尚未起播时退化为 [setQueueAndPlay]，保证首次操作也能出声。
     */
    fun playNext(songs: List<SongEntity>) {
        cancelCrossfade()
        val p = player
        if (p == null || _uiState.value.queue.isEmpty()) {
            setQueueAndPlay(songs)
            return
        }
        if (songs.isEmpty()) return
        // P2-13：先作废在途 setQueueAndPlay，避免插播被旧队列覆盖。
        // 插到当前曲之后会让后续项索引整体右移，APE 重试标记（按索引记）必须一并复位。
        gate.onQueueStructureChanged()
        val entries = songs.map { PlaybackMediaItems.toQueueEntry(it, nextQueueKey()) }
        scope.launch {
            val insertAt = (_uiState.value.queueIndex + 1).coerceIn(0, _uiState.value.queue.size)
            p.addMediaItems(insertAt, songs.map { PlaybackMediaItems.lazyMediaItem(it) })
            _uiState.update { state ->
                val merged = state.queue.toMutableList().also { q -> q.addAll(insertAt, entries) }
                state.copy(queue = merged)
            }
        }
    }

    /** 播放队列中指定索引（APE 按需转码后替换 URI） */
    fun playAtIndex(index: Int) {
        cancelCrossfade()
        // P2-13：服务未就绪时旧实现写 PendingPlay(emptyList(), index, 0L)，
        // bind 后 doSetQueueAndPlay 在 songs.isEmpty() 处直接 return → 点了队列项却无声。
        // 改为标记「待恢复会话」，服务起来后由 bind() 真正恢复上次队列。
        val p = player ?: run { gate.pendRestore(); serviceLauncher.ensureStarted(); return }
        if (index !in _uiState.value.queue.indices) return
        scope.launch {
            val entry = _uiState.value.queue[index]
            if (entry.isApe) {
                apeResolver.transcodeToFile(entry.songId, entry.filePath)
                    ?.let { replaceMediaItemUri(index, it) }
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
        cancelCrossfade()
        val p = player ?: return
        if (index !in _uiState.value.queue.indices) return
        // P2-13：先作废在途 setQueueAndPlay，避免删除被旧队列还原。
        // 删除会让后续项索引左移——旧实现漏了复位 APE 重试标记，导致删掉前一首后，
        // 另一个 APE 的下标被错认成「已重试过」而跳过转码，直接卡在 IDLE。
        gate.onQueueStructureChanged()
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
     * 队列内拖拽重排：把 [from] 移到 [to] 位置，并同步当前播放索引。
     *
     * [androidx.media3.common.Player.moveMediaItem] 会自动维护正在播放项的
     * currentMediaItemIndex（当前曲被移动时索引跟随），故直接用真实索引对齐
     * [PlaybackUiState.queueIndex]，比 [removeFromQueue] 的手动计算更稳。
     * 同步清除 APE 重试标记（按索引记，重排后失效，理由同 [clearQueue]）。
     */
    fun moveQueueItem(from: Int, to: Int) {
        cancelCrossfade()
        val p = player ?: return
        val queue = _uiState.value.queue
        if (from !in queue.indices || to !in queue.indices || from == to) return
        // P2-13：先作废在途 setQueueAndPlay，避免重排被旧队列覆盖。
        // 重排会打乱索引映射，APE 重试标记由 onQueueStructureChanged 一并复位。
        gate.onQueueStructureChanged()
        p.moveMediaItem(from, to)
        _uiState.update { state ->
            val newQueue = state.queue.toMutableList().also { it.add(to, it.removeAt(from)) }
            state.copy(queue = newQueue, queueIndex = p.currentMediaItemIndex)
        }
    }

    /**
     * 清空队列。只重置「瞬时」字段，保留用户偏好（播放顺序/随机/倍速），
     * 否则 UI 显示的播放顺序会和播放器真实状态不一致（历史 bug）。
     */
    fun clearQueue() {
        cancelCrossfade()
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
        // 清空同样属于「队列结构变化」：既作废 APE 重试标记（P2-14），也递增代次——
        // 旧实现漏了后者，仍挂起在 APE 转码里的旧 setQueue 恢复后会把队列又写回来
        // （历史现象：点了清空，队列隔一会儿自己冒回来）。
        gate.onQueueStructureChanged()
        scope.launch { musicRepository.clearPlaybackSession() }
        stopServiceIfIdle()
    }

    // ---------- 播放控制 ----------

    fun togglePlayPause() {
        cancelCrossfade()
        val p = player
        if (p == null) {
            gate.pendRestore()
            serviceLauncher.ensureStarted()
            return
        }
        if (p.isPlaying) pause() else play()
    }

    fun play() {
        cancelCrossfade()
        val p = player
        if (p == null) {
            gate.pendRestore()
            serviceLauncher.ensureStarted()
            return
        }
        if (p.mediaItemCount == 0) {
            scope.launch { restoreLastSession() }
            return
        }
        p.play()
    }

    fun pause() {
        cancelCrossfade()
        player?.pause()
        // 同步刷新播放态：onIsPlayingChanged 监听是异步的，投屏 startCast 暂停本地后
        // 若不立即置 false，UI 会短暂显示「播放中」与投屏中提示条冲突（P0-2-③）。
        _uiState.update { it.copy(isPlaying = false) }
        saveSessionNow()
    }

    fun stop() {
        cancelCrossfade()
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
        // 让仍在途的旧队列任务失效（例如 APE 转码挂起中），并作废 APE 重试标记
        // （P2-14：否则新队列同索引的 APE 首次出错会被误判「已重试过」，跳过转码卡 IDLE）
        gate.onQueueStructureChanged()
        // P3-16：作废全部挂起的播放请求——否则服务重启（bind 重放 pendingPlay）
        // 会把这次已停止的队列又播出来，与用户「显式停止」意图相反。
        gate.clearPending()
        // 显式停止即放弃续播：清会话，否则下次冷启动会把已停的队列又恢复出来
        scope.launch { musicRepository.clearPlaybackSession() }
        stopServiceIfIdle()
    }

    fun next() {
        cancelCrossfade()
        val p = player ?: return
        // 同步计算下一首索引（尊重 repeat/shuffle；INDEX_UNSET = 无下一首）。
        // 用 Timeline.getNextWindowIndex 与下方 seek 语义一致（顺序/随机/循环三态统一），
        // 但能立即拿到目标索引，用于同步刷新 UI 镜像——投屏态下 ViewModel 需马上读
        // currentSongId 决定投屏哪一首，仅靠异步 onMediaItemTransition 会有竞态。
        val target = p.currentTimeline.getNextWindowIndex(p.currentMediaItemIndex, p.repeatMode, p.shuffleModeEnabled)
        if (p.hasNextMediaItem()) p.seekToNextMediaItem()
        else if (_uiState.value.repeatMode == RepeatMode.ALL && p.mediaItemCount > 0) {
            // P3-15：循环到末尾回绕时，须回到「当前播放顺序」的首位，而非 timeline 的
            // 首位——开启随机时 seekTo(0) 会跳到随机序列之外的第一首，打乱随机体验。
            // 这里改用 Timeline.getFirstWindowIndex(shuffleModeEnabled)——它正是
            // Media3 自身在 REPEAT_ALL 回绕时使用的索引，与播放器内部语义完全一致。
            val first = p.currentTimeline.getFirstWindowIndex(p.shuffleModeEnabled)
            p.seekTo(if (first >= 0) first else 0, 0L)
        }
        if (target != C.INDEX_UNSET) {
            val id = runCatching { p.getMediaItemAt(target)?.mediaId?.toLongOrNull() }.getOrNull()
            _uiState.update { it.copy(currentSongId = id, queueIndex = target, positionMs = 0L, durationMs = 0L) }
        }
    }

    fun previous() {
        cancelCrossfade()
        val p = player ?: return
        // 同步计算上一首索引（尊重 repeat/shuffle；INDEX_UNSET = 无上一首→回到当前曲开头）。
        val target = p.currentTimeline.getPreviousWindowIndex(p.currentMediaItemIndex, p.repeatMode, p.shuffleModeEnabled)
        if (p.hasPreviousMediaItem()) p.seekToPreviousMediaItem() else p.seekTo(0L)
        if (target != C.INDEX_UNSET) {
            val id = runCatching { p.getMediaItemAt(target)?.mediaId?.toLongOrNull() }.getOrNull()
            _uiState.update { it.copy(currentSongId = id, queueIndex = target, positionMs = 0L, durationMs = 0L) }
        } else {
            // 无上一首：回到当前曲开头（与原 else 分支 p.seekTo(0L) 一致）
            _uiState.update { it.copy(positionMs = 0L) }
        }
    }

    fun seekTo(positionMs: Long) {
        cancelCrossfade()
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
        scope.launch {
            if (minutes > 0) settings.setSleepTimerMinutes(minutes) else settings.clearSleepTimer()
        }
    }

    /** 「播完本曲停止」：当前这首自然播完就暂停（不限时长） */
    fun setSleepTimerEndOfTrack() {
        scope.launch { settings.setSleepTimerEndOfTrack() }
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

    /**
     * 桌面小部件专用：队列为空时把上次会话恢复到暂停态。
     *
     * 返回 true 表示本次已执行恢复（调用方应跳过自己的切歌命令）——桌面上点「下一首」
     * 时进程可能刚被回收、队列是空的，直接 next() 什么都不会发生。
     */
    suspend fun ensureQueueRestored(): Boolean {
        if ((player?.mediaItemCount ?: 0) > 0) return false
        return restoreLastSession()
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
        // 按队列内索引记忆重试资格：同一索引只自动转码重试一次，防止
        // 「出错 → 转码 → 再出错 → 再转码」死循环。写法保持「判断 + 占位」原子完成。
        if (entry != null && entry.isApe && gate.tryAcquireApeRetry(index)) {
            scope.launch {
                // P0-2：转码失败/取消的语义统一由 apeResolver 处理（finally 复位标记，
                // 异常收敛为 null，取消原样抛出），此处只负责结果分支。
                val wav = apeResolver.transcodeToFile(entry.songId, entry.filePath)
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
        // 同理补触发「播完本曲停止」：末首播完不会再有移行回调。
        finishEndOfTrackSleepTimer()
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

    // ensureServiceStarted / startServiceByBinding 已下沉到 PlaybackServiceLauncher
    // （P1-1 拆分第二刀），调用点统一走 serviceLauncher.ensureStarted()。

    /**
     * 确保服务与播放器就绪（后台命令入口，如桌面小部件）。
     *
     * 调用方（[com.puretone.player.widget.PlayPauseAction] 等）在挂起上下文里等播放器注入后
     * 再发命令，否则命令会落在 player == null 上被静默丢弃。
     *
     * @return 播放器是否已就绪；false 表示超时（服务被系统拒绝拉起等极端情况）
     */
    suspend fun ensureServiceReady(timeoutMs: Long = 3_000L): Boolean {
        if (player != null) return true
        serviceLauncher.ensureStarted()
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (player == null && SystemClock.elapsedRealtime() < deadline) {
            delay(50L)
        }
        return player != null
    }

    /** 空闲时停止服务（空闲判定需要读播放器，故留在此处；停服务本身下沉到 launcher） */
    private fun stopServiceIfIdle() {
        if ((player?.isPlaying != true) && (player?.mediaItemCount ?: 0) == 0) {
            serviceLauncher.stopService()
        }
    }

    private companion object {
        const val TAG = "PlaybackManager"
    }

    // 媒体项 / URI / 队列项构造已抽到 PlaybackMediaItems（P1-1 拆分第一刀）：
    // 那一簇是纯映射，不需要播放器实例与 UI 状态，独立成文件后可单测。

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

}

/**
 * 续播守卫（纯函数，可单测）：只有「播放器里什么都没有」时才允许恢复会话。
 *
 * 恢复会重建队列并暂停；若播放器已有媒体项（无论正在播放还是已暂停待播），
 * 恢复都会打断用户当前状态——这正是「进播放页被自动暂停」的根因场景。
 */
internal fun canRestoreSession(playerItemCount: Int, isPlaying: Boolean): Boolean =
    playerItemCount == 0 && !isPlaying
