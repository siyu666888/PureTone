package com.puretone.player.playback

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import com.puretone.player.data.local.entity.SongEntity
import com.puretone.player.data.repository.MusicRepository
import com.puretone.player.data.settings.PureToneSettings
import com.puretone.player.data.settings.SleepTimerMode
import com.puretone.player.data.settings.SettingsRepository
import com.puretone.player.effect.AudioEffectController
import com.puretone.player.playback.cast.CastDevice
import com.puretone.player.playback.cast.CastQueueCoordinator
import com.puretone.player.playback.cast.CastQueueInfo
import com.puretone.player.playback.cast.CastResult
import com.puretone.player.playback.cast.DlnaCastManager
import com.puretone.player.widget.WidgetUpdater
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
    /** 完整 UI 队列中对应「播放器 mediaItemIndex==0」的绝对索引；淡化收尾会 prepend 回已播前缀使播放器恢复完整队列，故恒为 0，播放器索引与 UI 绝对索引始终对齐 */
    val queueOffset: Int = 0,
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
    @ApplicationContext private val appContext: Context,
    private val musicRepository: MusicRepository,
    private val apeTranscoder: ApeTranscoder,
    private val audioEffectController: AudioEffectController,
    private val settings: SettingsRepository,
    private val widgetUpdater: WidgetUpdater,
    private val dlnaCast: DlnaCastManager,
    private val serviceLauncher: PlaybackServiceLauncher
) : PlaybackControlSink, PlaybackQueueSink {

    private var player: ExoPlayer? = null
    private var session: MediaSession? = null

    /** ReplayGain 音频处理器（由 [PlaybackService] 创建并随播放器注入；交叉淡化时 standby 播放器另持一份） */
    private var replayGainProcessor: ReplayGainAudioProcessor? = null

    private val _uiState = MutableStateFlow(PlaybackUiState())
    val uiState: StateFlow<PlaybackUiState> = _uiState.asStateFlow()

    /**
     * 是否处于投屏会话。供 [PlaybackService.onTaskRemoved] 判断划掉任务卡时
     * 能否停前台服务：投屏期本地播放器恒暂停，但手机仍是电视的 HTTP 媒体源。
     */
    val isCasting: Boolean get() = dlnaCast.state.value.isCasting

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

    private var settingsJob: Job? = null

    /**
     * 「曲间停顿」：关闭无缝播放时，自动切歌边界暂停一小会再继续。
     * 逻辑本体见 [InterTrackGap]——那里记录了「只暂停不续播导致永久停住」的历史缺陷。
     */
    private val interTrackGap = InterTrackGap(
        scope = scope,
        playerProvider = { player?.let(::ExoGapPlayer) }
    )
    /**
     * 应用级音频焦点（双实例必须单点持有，见 [AudioFocusController]）：两个 ExoPlayer 都以
     * `handleAudioFocus = false` 构建，否则备援在淡化起点申请焦点会把主播放器打成 LOSS。
     */
    private val playbackFocus = PlaybackAudioFocus(
        context = appContext,
        playerProvider = { player },
        isCrossfading = { crossfade.isCrossfading },
        isCasting = { dlnaCast.state.value.isCasting }
    )

    /**
     * 播放会话存档（P1-1 拆分第五刀）。
     *
     * 「保存位置钳到 duration-500」与「恢复时按保存顺序重排」两个已踩过的修复点都在这里，
     * 抽出来后可纯 JVM 锁定（见 [PlaybackSessionStore] 类注释）。
     */
    private val sessionStore: PlaybackSessionStore = PlaybackSessionStore(
        archive = RoomPlaybackSessionArchive(musicRepository),
        scope = scope
    )

    /**
     * 交叉淡化协调器（P1-1 拆分第四刀）。
     *
     * 备援播放器、其 ReplayGain 处理器、以及「淡出方 / 淡入索引 / 剩余曲目 / 渐变协程」
     * 四份淡化状态全部归属它，主类不再持有任何一个——此前它们与队列命令状态混在一处，
     * 而「取消淡化后没复位音量」这类缺陷的后果要到几十秒后的下一次淡化才听得出来。
     *
     * 主类只保留**所有权在此**的收尾动作（见 [Host.onFinalize]）：交换主/备引用、
     * ReplayGain 处理器、MediaSession 指向与监听器，并刷新 UI。
     */
    // 类型必须显式写出：初始化 lambda 内部会回调 attach() 重新注入备援，
    // 若让编译器推断，会与「正在推断的 crossfade 自身」形成递归而无法解析。
    private val crossfade: CrossfadeCoordinator = CrossfadeCoordinator(
        scope = scope,
        host = object : CrossfadeCoordinator.Host {
            override val settings: PureToneSettings get() = lastSettings
            override val queue: List<QueueEntry> get() = _uiState.value.queue
            override val isCasting: Boolean get() = dlnaCast.state.value.isCasting

            override fun onFinalize(newPrimary: CrossfadePlayer, outgoing: CrossfadePlayer, nextIndex: Int) {
                val sec = (newPrimary as? ExoCrossfadePlayer)?.exo ?: return
                val out = (outgoing as? ExoCrossfadePlayer)?.exo ?: return
                // 恢复完整队列：淡出方仍持有「当前曲之前」的全部已播项（从未被移除，
                // 仅「下一首」在 maybeStart 时被摘下），prepend 回新主；否则新主只持剩余尾部，
                // 播放器索引会与 UI 完整队列绝对索引错位（UI 高亮跑偏、第二次淡化 APE 门控取错歌）。
                // prepend 后新主当前曲索引自动顺移到 nextIndex，播放不中断。
                val prefixCount = minOf(nextIndex, out.mediaItemCount)
                if (prefixCount > 0) {
                    val prefix = (0 until prefixCount).mapNotNull { i ->
                        runCatching { out.getMediaItemAt(i) }.getOrNull()
                    }
                    if (prefix.isNotEmpty()) runCatching { sec.addMediaItems(0, prefix) }
                }
                // MediaSession 指向新主
                runCatching { session?.setPlayer(sec) }
                // 交换监听器：新主挂完整监听器，旧主（现为备援）挂轻量 secondaryListener。
                // 监听器按实例取（而非共用一份），这样旧主残留的异步回调能被识别并丢弃。
                out.removeListener(eventListener.primaryListenerFor(out))
                out.addListener(eventListener.secondaryListener)
                sec.removeListener(eventListener.secondaryListener)
                sec.addListener(eventListener.primaryListenerFor(sec))
                // 新主的 RG 处理器即原备援处理器实例（处理器在 ExoPlayer 构建时就绑死在
                // 各自的播放器上，随角色一并换位）
                val outgoingGain = replayGainProcessor
                player = sec
                replayGainProcessor = replayGainProcessorB
                // 播放顺序 / 随机 / 倍速必须随角色一起搬家：两个 ExoPlayer 各自持有自己的
                // repeatMode，而设置流只在 bind 后发射一次、只作用于当时的主播放器。
                // 换主不重放时两个实例会逐首交替 ALL / OFF，前 N 首都靠淡化主动推进被掩盖，
                // 直到队列走到末尾、主恰好是 OFF 的那一次：hasNext=false → 不触发淡化 →
                // Media3 不回绕直接 STATE_ENDED，UI 停成「暂停」且无 paused-with-queue。
                // 双方都同步，避免下一次角色交换把旧值再换回来。
                val carriedSpeed = out.playbackParameters.speed
                settingsApplier.applyOrderTo(sec, carriedSpeed)
                settingsApplier.applyOrderTo(out, carriedSpeed)
                // 备援随之换成「原主播放器」，其 RG 处理器即原主处理器。
                // 原实现漏了这一步：收尾后 secondaryPlayer 仍指向已升为主播放器的实例，
                // 于是第二次淡化会拿主播放器当备援，把它的队列清成单曲——表现为
                //「连续两首之间都开了淡化时，第二首之后队列莫名变短 / 切不动」。
                secondaryPlayer = out
                replayGainProcessorB = outgoingGain
                crossfade.attach(ExoCrossfadePlayer(out), outgoingGain)
                _uiState.update { state ->
                    state.copy(
                        currentSongId = sec.currentMediaItem?.mediaId?.toLongOrNull(),
                        queueIndex = nextIndex,
                        queueOffset = 0,
                        durationMs = sec.duration.coerceAtLeast(0L)
                    )
                }
                widgetUpdater.requestUpdate()
                applyReplayGainForCurrent()
            }
        }
    )

    /** 备援播放器与它的 ReplayGain 处理器仍由主类持有引用：角色交换时要交还给 [player] */
    private var secondaryPlayer: ExoPlayer? = null
    private var replayGainProcessorB: ReplayGainAudioProcessor? = null

    /**
     * 队列命令闸门：代次 / 挂起命令 / APE 重试标记三份隐式状态收敛于此（P1-1 第二轮）。
     * 以前它们散在 20 余处读写点、靠注释维持不变量，已实际产生「删歌后 APE 不转码」
     * 「清空队列后自己冒回来」「bind 后旧会话盖掉新点歌」三类缺陷。详见其类注释。
     */
    private val gate = PlaybackCommandGate()

    /**
     * 播放控制编排（P1-1 拆分第七刀）。
     *
     * 公开命令方法（togglePlayPause/play/pause/stop/next/previous/seekTo/setRepeatMode/
     * setShuffle/setSpeed/setSleepTimer 系列/dismissError）在 [PlaybackManager] 上保留一行转发，
     * 逻辑全部下沉到 [PlaybackController]，避免 god class 继续膨胀。
     */
    private val controller = PlaybackController(
        playerProvider = { player },
        uiState = _uiState,
        crossfade = crossfade,
        gate = gate,
        dlnaCast = dlnaCast,
        settings = settings,
        sessionStore = sessionStore,
        serviceLauncher = serviceLauncher,
        scope = scope,
        sink = this
    )

    /**
     * 队列编排（P1-1 拆分第八刀）。
     *
     * 公开队列命令方法（setQueueAndPlay/enqueue/playNext/playAtIndex/removeFromQueue/
     * moveQueueItem/clearQueue）在 [PlaybackManager] 上保留一行转发，逻辑全部下沉到
     * [QueueOrchestrator]，避免 god class 继续膨胀。
     */
    private val queueOrchestrator = QueueOrchestrator(
        playerProvider = { player },
        uiState = _uiState,
        crossfade = crossfade,
        gate = gate,
        dlnaCast = dlnaCast,
        serviceLauncher = serviceLauncher,
        sessionStore = sessionStore,
        apeResolver = apeResolver,
        scope = scope,
        sink = this
    )

    /**
     * 续播恢复（P1-1 拆分第十一刀）：读取存档 → 二次守卫 → 重建队列。
     * 落地动作仍在 [QueueOrchestrator]（依赖它独有的播放器与 APE 转码编排）。
     */
    private val sessionRestorer = PlaybackSessionRestorer(
        playerProvider = { player },
        sessionStore = sessionStore,
        queueOrchestrator = queueOrchestrator
    )

    /**
     * 睡眠定时控制器（P1-1 收尾抽取）：MINUTES 倒计时 / END_OF_TRACK 播完本曲停止 / OFF。
     * 状态机与 job 全部归它，主类只保留「设置变化 / ticker / 自然播完」三处一行转发。
     */
    private val sleepTimer = SleepTimerController(
        settings = settings,
        uiState = _uiState,
        scope = scope,
        pause = { this@PlaybackManager.pause() }
    )

    /**
     * 设置 → 引擎/音效 映射（P1-1 拆分第十刀）：倍速 / 无缝 / 顺序 / 随机 / 音效 / ReplayGain。
     * `lastSettings` / `gaplessEnabled` 仍以回调回写，因为交叉淡化协调器与 ReplayGain 刷新仍读它们。
     */
    private val settingsApplier = PlaybackSettingsApplier(
        uiState = _uiState,
        playerProvider = { player },
        controller = controller,
        sleepTimer = sleepTimer,
        audioEffectController = audioEffectController,
        applyReplayGainForCurrent = this::applyReplayGainForCurrent,
        setLastSettings = { lastSettings = it },
        setGaplessEnabled = { gaplessEnabled = it }
    )

    /**
     * 播放错误自愈处理器（P1-1 收尾抽取）：APE 重试 / 错误自愈 / 连续跳过计数。
     * 由 onError / onIsPlayingChanged 经 errorHandler 字段转发。
     */
    private val errorHandler = PlaybackErrorHandler(
        playerProvider = { player },
        uiState = _uiState,
        gate = gate,
        apeResolver = apeResolver,
        queueOrchestrator = queueOrchestrator,
        scope = scope,
        stop = { this@PlaybackManager.stop() }
    )

    /**
     * 投屏队列协调器（M3）。
     *
     * DLNA 是一次性 push，渲染器不会自动接着播下一首；而投屏态下本地播放器是暂停的
     * （避免手机与电视同时出声），于是「一首播完」后没有任何东西推进队列——投屏只能
     * 听一首就停住。本协调器在渲染器播完后推进队列并重新投送下一首；投屏连接失效时
     * 回调宿主把播放回退到本地。
     *
     * 判定与状态机都在协调器内部（可用纯 JVM 单测逐条锁定），主类只提供
     * 「队列快照」与「落地动作」两件事。
     */
    /**
     * 投屏会话控制器（P1-1 收尾抽取）：CastCommandSink / CastQueueSink 实现、路由状态、
     * startCast / stopCast / fallback 全归它；[castQueueCoordinator] 的 sink 指向它。
     * recastCurrentIfCasting 与 warmUpFollowingApe 仍属 PlaybackQueueSink 留在主类。
     */
    private val cast = CastSessionController(
        dlnaCast = dlnaCast,
        playbackFocus = playbackFocus,
        uiState = _uiState,
        scope = scope,
        playerProvider = { player },
        sessionProvider = { session },
        pause = { this@PlaybackManager.pause() },
        play = { this@PlaybackManager.play() },
        next = { this@PlaybackManager.next() },
        previous = { this@PlaybackManager.previous() },
        playAtIndex = { i -> this@PlaybackManager.playAtIndex(i) },
        seekTo = { ms -> this@PlaybackManager.seekTo(ms) },
        followPhoneVolume = { lastSettings.castVolumeFollowPhone }
    )

    private val castQueueCoordinator = CastQueueCoordinator(
        scope = scope,
        castState = dlnaCast.state,
        queueInfo = { buildCastQueueInfo() },
        sink = cast
    )

    /**
     * 播放事件处理（P1-1 拆分第六刀）。
     *
     * 收听主/备播放器回调、做事件分类与完成去重，再通过 [PlaybackEventSink] 回传副作用。
     * 监听器对象本体与分类/去重逻辑都搬到了 [PlaybackEventListener]，本类只实现副作用。
     */
    /**
     * 播放事件副作用落地（P1-1 拆分第九刀）：[PlaybackEventListener] 解释完事件后，
     * 所有「宿主据此要做什么」都委托给本类——UI 镜像 / 音效会话 / 去抖存档 / ReplayGain /
     * APE 预热 / 小部件 / 空闲停服。主类因此只实现控制与队列两个 sink 接口。
     */
    private val eventEffects = PlaybackEventEffects(
        playerProvider = { player },
        uiState = _uiState,
        audioEffectController = audioEffectController,
        lastSettingsProvider = { lastSettings },
        errorHandler = errorHandler,
        playbackFocus = playbackFocus,
        widgetUpdater = widgetUpdater,
        sessionStore = sessionStore,
        scope = scope,
        musicRepository = musicRepository,
        crossfade = crossfade,
        interTrackGap = interTrackGap,
        sleepTimer = sleepTimer,
        gaplessEnabledProvider = { gaplessEnabled },
        warmUpFollowingApe = this::warmUpFollowingApe,
        applyReplayGainForCurrent = this::applyReplayGainForCurrent,
        stopServiceIfIdle = this::stopServiceIfIdle
    )

    private val eventListener = PlaybackEventListener(
        playerProvider = { player },
        currentSongIdProvider = { _uiState.value.currentSongId },
        sink = eventEffects
    )

    // 服务拉起 / 兜底 controller 已下沉到 PlaybackServiceLauncher（P1-1 拆分第二刀）：
    // 那里装的是 Android 8+ 后台启动禁令的绕行逻辑，不属于播放编排。

    /** 当前无缝播放开关（来自设置，bind 时订阅） */
    private var gaplessEnabled: Boolean = true

    /** 最近一次设置快照（session 重建后重新灌入音效/播放模式用） */
    private var lastSettings: PureToneSettings = PureToneSettings()

    // 主/备播放器监听器已下沉到 PlaybackEventListener（P1-1 拆分第六刀）：
    // 事件解释、分类与完成去重都在那里，本类只通过 PlaybackEventSink 实现副作用。

    /**
     * 桌面小组件的播放态同步。
     *
     * 投屏期渲染器的播放/暂停由 [CastSessionController] 直接写 uiState，不经过本地
     * ExoPlayer 的 onIsPlayingChanged，小组件按钮会停在投屏开始那一刻的状态——
     * 用户看到的「其它卡片与播放页不一致」也包含它。这里按**展示态**统一推一次，
     * 与本地事件路径的重复请求由 WidgetUpdater 内部 300ms 去抖合并。
     */
    private val widgetPlayStateSync = scope.launch {
        _uiState.map { it.isPlaying }.distinctUntilChanged().collect {
            widgetUpdater.requestUpdate()
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
            sleepTimer.refreshRemaining()
            // 交叉淡化：主播放器临近本曲结尾（且存在下一首、未处于淡化中、已开启）时，
            // 提前起播备援播放器承载下一首，两路音量此消彼长形成真重叠淡化。
            if (p != null && p.isPlaying) maybeStartCrossfade(p)
            // P3-18：暂停时不需要 500ms 粒度（睡眠定时剩余也只需秒级），
            // 拉长到 2s，减少空转唤醒
            delay(if (p?.isPlaying == true) 500 else 2_000)
        }
    }

    /**
     * 按当前曲目 + 设置的 ReplayGain 参数，刷新 [replayGainProcessor] 的线性增益。
     * 取轨道或专辑增益取决于设置模式；关闭 ReplayGain 时增益恒为 1.0（透传）。
     * 处理器逐缓冲读取增益，曲目切换时实时更新无爆音。
     */
    override fun applyReplayGainForCurrent() {
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
    //
    // 状态机与备援播放器的全部生命周期已下沉到 CrossfadeCoordinator（P1-1 拆分第四刀），
    // 此处只剩两个入口：触发（ticker）、取消（收尾已随事件层下沉到 PlaybackEventEffects）。

    /** ticker 调用：临近曲尾且存在下一首时启动淡化（判定与编排在协调器内） */
    private fun maybeStartCrossfade(p: ExoPlayer) {
        crossfade.maybeStart(ExoCrossfadePlayer(p))
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
        this.player?.let { it.removeListener(eventListener.primaryListenerFor(it)) }
        this.secondaryPlayer?.removeListener(eventListener.secondaryListener)
        this.player = player
        this.secondaryPlayer = secondary
        this.session = session
        this.replayGainProcessor = replayGainProcessor
        this.replayGainProcessorB = replayGainProcessorB
        // 备援播放器交由协调器持有（其淡化窗口内的全部操作都在那里）
        crossfade.attach(ExoCrossfadePlayer(secondary), replayGainProcessorB)
        player.addListener(eventListener.primaryListenerFor(player))
        // 备援播放器只负责「挂接音效会话」与错误兜底，不驱动 UI/切歌（淡入淡出由协调器统一编排）
        secondary.addListener(eventListener.secondaryListener)
        audioEffectController.attachToSession(player.audioSessionId)
        // 由「后台 bind 兜底」临时持有的 controller：服务已起、命令已排入，交由
        // launcher 延迟释放（若此时服务里已有播放内容，服务自己会保持存活）。
        serviceLauncher.onServiceBound()
        // 订阅设置：倍速 / 睡眠定时 / 无缝播放 / 播放顺序 / 音效 实时生效
        settingsJob = scope.launch {
            settings.settings.collect { settingsApplier.apply(it) }
        }
        // 投屏路由（M1 收尾）：门面必须包住**当前**这个 player，故每次 bind 重建；
        // 路由状态与订阅已下沉到 CastSessionController（P1-1 收尾抽取）。
        cast.bind(player)
        // 投屏队列推进（M3）：渲染器播完自动投下一首 / 投屏失联回退本地。
        // 与上面 CastSessionController 的路由订阅是两件事——那个只管 UI 镜像与
        // MediaSession 切换，这个管「队列要不要继续往前走」，两者各自独立订阅同一份状态流。
        castQueueCoordinator.start()
        // 执行挂起请求：一次性取走全部（重放期间新来的命令会留在槽里，不被这次重放吞掉）
        val pending = gate.drain()
        val playReq = pending.play
        if (playReq == null) {
            if (pending.enqueue.isNotEmpty()) queueOrchestrator.enqueue(pending.enqueue)
            if (pending.restore) scope.launch { restoreLastSession() }
            return
        }
        // P1-3：显式点歌优先于挂起的续播。冷启动首帧可能已由 togglePlayPause /
        // play / playAtIndex 排了「自动恢复上次会话」，用户随后点的歌更新鲜——
        // 两条命令若在 bind 后并发执行，恢复出的旧队列会覆盖新队列（代次守卫也
        // 救不了：恢复命令最后执行、代次最新，先执行的点歌反而被判过期丢弃）。
        // 该互斥现由 PlaybackCommandGate.pendPlay 内部强制，此处不再手动清标志。
        scope.launch {
            queueOrchestrator.applyQueueAndPlay(playReq.songs, playReq.startIndex, playReq.startPositionMs, autoPlay = true)
            // 追加必须排在点歌「完成」之后：enqueue 会走 onQueueStructureChanged
            // 递增代次（作废在途的旧 setQueue）。若并发执行，首播曲是 APE 时
            // applyQueueAndPlay 正挂在转码上，会被同一批次重放命令自己作废 ——
            // 表现为「冷启动点了 APE，却只播出了随后追加的歌」。
            if (pending.enqueue.isNotEmpty()) queueOrchestrator.enqueue(pending.enqueue)
        }
    }

    /** 由 [PlaybackService] 在 onDestroy 解绑 */
    fun unbind() {
        settingsJob?.cancel()
        settingsJob = null
        // 播放器即将释放：焦点必须一并放弃，否则系统里会留下一个「永远不会主动让出」的客户端
        playbackFocus.abandon()
        // 备援播放器的释放（含撤销进行中的淡化）一并交给协调器
        crossfade.detach()
        player?.let { it.removeListener(eventListener.primaryListenerFor(it)) }
        secondaryPlayer?.removeListener(eventListener.secondaryListener)
        player = null
        secondaryPlayer = null
        replayGainProcessorB = null
        session = null
        audioEffectController.detach()
        // 解绑只作废「追加」与「续播」（依赖当前会话的队列/设置），
        // 保留 pendingPlay——用户显式点的歌在服务重建后仍应执行。
        gate.clearSessionBoundPending()
        // 投屏路由状态与订阅（CastSessionController）随解绑释放，避免对已死对象调 setPlayer
        cast.unbind()
        // 投屏队列协调器同样停掉：它回调宿主推进队列，而队列已随播放器一起释放
        castQueueCoordinator.stop()
    }

    // ---------- 队列命令（逻辑下沉至 QueueOrchestrator，此处仅转发） ----------

    fun setQueueAndPlay(songs: List<SongEntity>, startIndex: Int = 0, startPositionMs: Long = 0L) =
        queueOrchestrator.setQueueAndPlay(songs, startIndex, startPositionMs)

    fun enqueue(songs: List<SongEntity>) = queueOrchestrator.enqueue(songs)

    fun playNext(songs: List<SongEntity>) = queueOrchestrator.playNext(songs)

    fun playAtIndex(index: Int) = queueOrchestrator.playAtIndex(index)

    fun removeFromQueue(index: Int) = queueOrchestrator.removeFromQueue(index)

    fun moveQueueItem(from: Int, to: Int) = queueOrchestrator.moveQueueItem(from, to)

    fun clearQueue() = queueOrchestrator.clearQueue()

    // ---------- 播放控制 ----------

    // ---------- 播放控制（逻辑下沉至 PlaybackController，此处仅转发） ----------

    fun togglePlayPause() = controller.togglePlayPause()
    fun play() = controller.play()
    fun pause() = controller.pause()
    fun stop() = controller.stop()
    fun next() = controller.next()
    fun previous() = controller.previous()
    fun seekTo(positionMs: Long) = controller.seekTo(positionMs)
    fun setRepeatMode(mode: RepeatMode) = controller.setRepeatMode(mode)
    fun setShuffle(enabled: Boolean) = controller.setShuffle(enabled)
    fun setSpeed(speed: Float) = controller.setSpeed(speed)
    fun setSleepTimer(minutes: Int) = controller.setSleepTimer(minutes)
    fun setSleepTimerEndOfTrack() = controller.setSleepTimerEndOfTrack()
    fun dismissError() = controller.dismissError()

    // ---------- 续播 ----------

    /**
     * 恢复上次播放会话（受 settings resumeOnStart 控制，由 PlayerViewModel 首帧后调用）。
     *
     * 守卫逻辑与「读取存档 → 二次守卫 → 重建队列」已下沉到 [PlaybackSessionRestorer]；
     * 这里只保留进程级单例的入口语义（详见其类注释）。
     */
    override suspend fun restoreLastSession(): Boolean = sessionRestorer.restore()

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

    /**
     * 立即存档（停止 / 暂停 / 切歌等关键节点）；事件触发的去抖存档在 [PlaybackEventEffects]。
     */
    override fun saveSessionNow() = eventEffects.saveSessionNow()

    // 播放事件副作用（事件解释在 [PlaybackEventListener]、落地在 [PlaybackEventEffects]）、
    // APE 重试 / 错误自愈（[PlaybackErrorHandler]）都已下沉；主类只保留控制与队列两个 sink 接口。
    // 曲间停顿作废（[PlaybackControlSink.cancelInterTrackGap]）：事件层触发后此处一行转发。
    override fun cancelInterTrackGap() = interTrackGap.cancel()

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

    /**
     * 空闲时停止服务。判据必须是「**两个**播放器都不在播且都没有队列」：交叉淡化期间主/备
     * 刚交换过角色，只看主播放器会把仍在出声的那一路判成空闲——实测就是「一首播完整体停止」。
     */
    override fun stopServiceIfIdle() {
        val primary = player
        val standby = secondaryPlayer
        val anyPlaying = primary?.isPlaying == true || standby?.isPlaying == true
        val anyQueued = (primary?.mediaItemCount ?: 0) > 0 || (standby?.mediaItemCount ?: 0) > 0
        if (anyPlaying || anyQueued) return
        PlaybackEventLog.record("service-stop", "p=${primary?.mediaItemCount ?: 0} s=${standby?.mediaItemCount ?: 0}")
        serviceLauncher.stopService()
    }

    private companion object {
        const val TAG = "PlaybackManager"
    }

    // 媒体项 / URI / 队列项构造已抽到 PlaybackMediaItems（P1-1 拆分第一刀）：
    // 那一簇是纯映射，不需要播放器实例与 UI 状态，独立成文件后可单测。
    // 队列项的 URI 替换（APE 转码产物回写）已随队列编排下沉到 QueueOrchestrator（第八刀）。

    // ---------- 投屏（M1 收尾：MediaSession 切换 + 命令路由） ----------

    /** 开始投屏：暂停本地 → 选定设备 → 投送当前曲（实现见 [CastSessionController]） */
    fun startCast(device: CastDevice, song: SongEntity) = cast.startCast(device, song)

    /** 停止投屏：停渲染器 → MediaSession 切回本地 → 从电视当前进度续播（实现见 [CastSessionController]） */
    fun stopCast() = cast.stopCast()

    /**
     * 投屏期本地切歌后，把「当前曲」重新投给渲染器。
     *
     * DLNA push 是一次性的：手机队列怎么变渲染器都不知道，不会自动跟着切。
     * 不重投的话，电视会继续播旧的那首，而通知栏显示的是另一首。
     *
     * M3：重投失败必须回退本地。重投是一次「换曲」，失败意味着**渲染器上仍是旧曲**
     * 而手机端已经换到新曲——继续投屏就是状态撕裂（电视播 A、手机显示 B）。
     * 此时宁可退回本地播放（A 从手机放），也不能让两个声音/两个画面各说各话。
     */
    override fun recastCurrentIfCasting() {
        if (!dlnaCast.state.value.isCasting) return
        val entry = _uiState.value.currentEntry ?: return
        scope.launch {
            val result = dlnaCast.castSong(entry.songId, entry.title, entry.filePath)
            if (result is CastResult.Failed) {
                cast.fallbackToLocalPlayback("投屏中断，已切回本地播放")
            }
        }
    }

    // 投屏的 CastCommandSink / CastQueueSink 实现、路由状态与 startCast / stopCast /
    // fallback 已下沉到 CastSessionController（P1-1 收尾抽取）；recastCurrentIfCasting
    // 与 warmUpFollowingApe 属 PlaybackQueueSink 留在下方。

    /**
     * 预热「下一首 APE」的转码（只一首：整队预转会拖慢切片切换、
     * 并无谓占满字节上限的缓存，见 P0-5）。其余 APE 切到它们时按需转码。
     */
    override fun warmUpFollowingApe() {
        val state = _uiState.value
        val nextApe = state.queue.drop(state.queueIndex + 1).firstOrNull { it.isApe } ?: return
        scope.launch(Dispatchers.IO) {
            // P0-2：预热是 fire-and-forget，ensureWav 抛异常同样会经 SupervisorJob
            // 到达未捕获异常处理器 → 进程崩溃，必须包住
            runCatching { apeTranscoder.ensureWav(nextApe.songId, nextApe.filePath) }
        }
    }

    /**
     * 投屏队列推进所需的队列快照。
     *
     * `hasNext` 必须与本地 [PlaybackController.next] 的语义一致：Media3 的
     * `hasNextMediaItem()` 在 REPEAT_ALL / REPEAT_ONE 下同样为 true（回绕 / 重播当前曲）。
     * 旧实现只按 `queueIndex < queue.lastIndex` 判断，于是「末曲 + 列表循环」时本地能
     * 接着播、投屏却按设计停在末曲——投屏与本地出现两套队列语义。
     */
    private fun buildCastQueueInfo(): CastQueueInfo {
        val state = _uiState.value
        val index = state.queueIndex
        val queue = state.queue
        val repeatAllowsMore = state.repeatMode != RepeatMode.OFF && queue.isNotEmpty()
        return CastQueueInfo(
            hasNext = index >= 0 && (index < queue.lastIndex || repeatAllowsMore),
            currentDurationMs = queue.getOrNull(index)?.durationMs ?: 0L
        )
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
