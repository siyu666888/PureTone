package com.puretone.player.playback

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
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
import com.puretone.player.playback.cast.CastDevice
import com.puretone.player.playback.cast.CastQueueCoordinator
import com.puretone.player.playback.cast.CastQueueInfo
import com.puretone.player.playback.cast.CastQueueSink
import com.puretone.player.playback.cast.CastResult
import com.puretone.player.playback.cast.CastState
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
) : CastCommandSink, PlaybackEventSink, PlaybackControlSink, PlaybackQueueSink, CastQueueSink {

    private var player: ExoPlayer? = null
    private var session: MediaSession? = null

    /** ReplayGain 音频处理器（由 [PlaybackService] 创建并随播放器注入；交叉淡化时 standby 播放器另持一份） */
    private var replayGainProcessor: ReplayGainAudioProcessor? = null

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
     * 「曲间停顿」：关闭无缝播放时，自动切歌边界暂停一小会再继续。
     * 逻辑本体见 [InterTrackGap]——那里记录了「只暂停不续播导致永久停住」的历史缺陷。
     */
    private val interTrackGap = InterTrackGap(
        scope = scope,
        playerProvider = { player?.let(::ExoGapPlayer) }
    )

    /** 连续「播放失败自动跳过」的计数（防止整条队列都是坏文件时无限空转） */
    private var consecutiveErrorSkips: Int = 0

    /**
     * 应用级音频焦点（双实例必须单点持有，见 [AudioFocusController]）：两个 ExoPlayer 都以
     * `handleAudioFocus = false` 构建，否则备援在淡化起点申请焦点会把主播放器打成 LOSS。
     */
    private val playbackFocus = PlaybackAudioFocus(
        context = appContext,
        playerProvider = { player },
        isCrossfading = { crossfade.isCrossfading },
        onPlayingFlagChanged = { playing -> _uiState.update { it.copy(isPlaying = playing) } },
        onSessionChanged = { saveSessionDebounced() }
    )

    /**
     * 播放会话存档（P1-1 拆分第五刀）。
     *
     * 「保存位置钳到 duration-500」与「恢复时按保存顺序重排」两个已踩过的修复点都在这里，
     * 抽出来后可纯 JVM 锁定（见 [PlaybackSessionStore] 类注释）。
     */
    private val sessionStore: PlaybackSessionStore = PlaybackSessionStore(
        archive = object : PlaybackSessionArchive {
            override suspend fun load(): SavedSession? =
                musicRepository.loadPlaybackSession()?.let {
                    SavedSession(songIds = it.songIds, index = it.index, positionMs = it.positionMs)
                }

            override suspend fun songsByIds(ids: List<Long>): List<SongEntity> =
                musicRepository.getSongs(ids)

            override suspend fun save(songIds: List<Long>, index: Int, positionMs: Long) {
                musicRepository.savePlaybackSession(songIds, index, positionMs)
            }

            override suspend fun clear() {
                musicRepository.clearPlaybackSession()
            }
        },
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
    private val castQueueCoordinator = CastQueueCoordinator(
        scope = scope,
        castState = dlnaCast.state,
        queueInfo = {
            val state = _uiState.value
            val index = state.queueIndex
            val queue = state.queue
            CastQueueInfo(
                // 末曲不推进：投完这一首就停，回绕交给本地播放模式（循环/随机）
                hasNext = index >= 0 && index < queue.lastIndex,
                currentDurationMs = queue.getOrNull(index)?.durationMs ?: 0L
            )
        },
        sink = this
    )

    /**
     * 播放事件处理（P1-1 拆分第六刀）。
     *
     * 收听主/备播放器回调、做事件分类与完成去重，再通过 [PlaybackEventSink] 回传副作用。
     * 监听器对象本体与分类/去重逻辑都搬到了 [PlaybackEventListener]，本类只实现副作用。
     */
    private val eventListener = PlaybackEventListener(
        playerProvider = { player },
        currentSongIdProvider = { _uiState.value.currentSongId },
        sink = this
    )

    /** 投屏态的 MediaSession 门面。在 [bind] 时按当前 player 重建（player 换了就要换包装） */
    private var castPlayer: CastPlaybackPlayer? = null
    /** MediaSession 当前是否挂在门面上（避免每次状态刷新都重复 setPlayer） */
    private var routedToCast = false
    private var castRoutingJob: Job? = null

    // 服务拉起 / 兜底 controller 已下沉到 PlaybackServiceLauncher（P1-1 拆分第二刀）：
    // 那里装的是 Android 8+ 后台启动禁令的绕行逻辑，不属于播放编排。

    /** 当前无缝播放开关（来自设置，bind 时订阅） */
    private var gaplessEnabled: Boolean = true

    /** 最近一次设置快照（session 重建后重新灌入音效/播放模式用） */
    private var lastSettings: PureToneSettings = PureToneSettings()

    // 主/备播放器监听器已下沉到 PlaybackEventListener（P1-1 拆分第六刀）：
    // 事件解释、分类与完成去重都在那里，本类只通过 PlaybackEventSink 实现副作用。

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
    // 此处只剩三个入口：触发（ticker）、收尾（STATE_ENDED 兜底）、取消。

    /** ticker 调用：临近曲尾且存在下一首时启动淡化（判定与编排在协调器内） */
    private fun maybeStartCrossfade(p: ExoPlayer) {
        crossfade.maybeStart(ExoCrossfadePlayer(p))
    }

    /**
     * 主播放器自然播到结尾时的兜底收尾（[PlaybackEventSink] 实现）。
     * 只有「淡出方就是当前主播放器」时才收尾：[CrossfadeCoordinator.finalize] 自带幂等守卫，
     * 渐变协程与本回调谁先到谁收尾，后到者直接返回。
     */
    override fun finalizeCrossfadeIfOutgoing(player: ExoPlayer) {
        if (crossfade.isCrossfading && crossfade.isOutgoing(player)) crossfade.finalize()
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
            settings.settings.collect { applySettings(it) }
        }
        // 投屏路由（M1 收尾）：门面必须包住**当前**这个 player，故每次 bind 重建
        castPlayer = CastPlaybackPlayer(player, this)
        castRoutingJob?.cancel()
        castRoutingJob = scope.launch {
            dlnaCast.state.collect { st ->
                // 投屏期本地 ExoPlayer 是暂停的，onIsPlayingChanged 只会推 false；
                // UI 的播放态镜像改跟渲染器，否则手机显示「暂停」而电视在播。
                if (st.isCasting) _uiState.update { it.copy(isPlaying = st.isPlaying) }
                routeSessionToCast(st.isCasting)
            }
        }
        // 投屏队列推进（M3）：渲染器播完自动投下一首 / 投屏失联回退本地。
        // 与 castRoutingJob 是两件事——那个只管 UI 镜像与 MediaSession 切换，
        // 这个管「队列要不要继续往前走」，两者各自独立订阅同一份状态流。
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
        // session/player 都将被释放：停掉路由订阅，避免对已死对象调 setPlayer
        castRoutingJob?.cancel()
        castRoutingJob = null
        castPlayer = null
        routedToCast = false
        // 投屏队列协调器同样停掉：它回调宿主推进队列，而队列已随播放器一起释放
        castQueueCoordinator.stop()
    }

    /**
     * 把 MediaSession 的 player 在「本地 ExoPlayer」与「投屏门面」之间切换。
     *
     * 只在跨越投屏边界时调用：频繁 setPlayer 会让 Media3 反复重建通知与内部控制器。
     * 切换失败（session 已释放等）只记日志——投屏是附加能力，不能因此崩掉播放链路。
     */
    private fun routeSessionToCast(toCast: Boolean) {
        if (toCast == routedToCast) return
        val target = if (toCast) castPlayer else player
        val s = session
        if (target == null || s == null) return
        runCatching { s.setPlayer(target) }
            .onFailure { Log.w(TAG, "session.setPlayer failed (toCast=$toCast)", it) }
            .onSuccess { routedToCast = toCast }
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
            PlaybackEventLog.record("sleep-timer", "MINUTES 倒计时到点 → 暂停（剩余 $remaining ms 已走完）")
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
        PlaybackEventLog.record("sleep-timer", "END_OF_TRACK 到点 → 暂停")
        pause()
        _uiState.update { it.copy(sleepTimerMode = SleepTimerMode.OFF, sleepRemainingMs = 0L) }
        scope.launch { settings.clearSleepTimer() }
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
     * 守卫必须放在本层（进程级单例），不能只靠 ViewModel 的实例字段：
     * - 恢复以 [autoPlay=false] 重建队列并 `pause()`，一旦在「播放器里已有内容」时
     *   执行，就会打断正在播放的歌（队列、进度、音量均被重置）；
     * - 每个导航目的地用 `hiltViewModel()` 拿到的是 **NavBackStackEntry 作用域的独立
     *   PlayerViewModel 实例**，其 init 都会跑一次恢复。于是「点迷你条进播放页」
     *   （以及进 我的 / 统计 / 分组详情 页）都会新建实例 → 二次恢复 → 自动暂停。
     *
     * 因此：播放器已有媒体项或正在播放时，一律原样返回 false。
     */
    override suspend fun restoreLastSession(): Boolean {
        val p = player
        if (!canRestoreSession(playerItemCount = p?.mediaItemCount ?: 0, isPlaying = p?.isPlaying == true)) {
            return false
        }
        // 按保存顺序重建队列（含重复项）：顺序还原与「曲目已删除」过滤都在 sessionStore 内
        val restored = sessionStore.restoreQueue() ?: return false
        val songs = restored.songs
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
        queueOrchestrator.applyQueueAndPlay(
            songs,
            restored.index,
            restored.positionMs,
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
        // 快照在写入时刻才求值：去抖的意义就是「以最新状态写一次」
        sessionStore.saveDebounced { sessionSnapshot() }
    }

    override fun saveSessionNow() {
        val snapshot = sessionSnapshot() ?: return
        sessionStore.saveNow(snapshot)
    }

    /** 当前待存档的会话快照；队列为空（无可存档内容）时为 null */
    private fun sessionSnapshot(): SavedSession? {
        val state = _uiState.value
        if (state.queue.isEmpty()) return null
        return SavedSession(
            songIds = state.queue.map { it.songId },
            index = state.queueIndex,
            positionMs = sessionStore.positionToSave(currentPosition(), currentDuration())
        )
    }

    /** 当前播放位置（毫秒，非负） */
    private fun currentPosition(): Long = (player?.currentPosition ?: 0L).coerceAtLeast(0L)

    /** 当前曲目时长；未知（ExoPlayer 返回 C.TIME_UNSET 等负值）时按 0 处理 */
    private fun currentDuration(): Long = player?.duration?.takeIf { it > 0 } ?: 0L

    // ---------- 内部 ----------

    private fun handlePlayerError(error: PlaybackException) {
        val p = player ?: return
        val index = p.currentMediaItemIndex
        val entry = _uiState.value.queue.getOrNull(index)
        PlaybackEventLog.record(
            "player-error",
            "code=${error.errorCodeName} idx=$index song=${entry?.songId ?: -1} isApe=${entry?.isApe} " +
                "msg=${error.message}"
        )
        // 按队列内索引记忆重试资格：同一索引只自动转码重试一次，防止
        // 「出错 → 转码 → 再出错 → 再转码」死循环。写法保持「判断 + 占位」原子完成。
        if (entry != null && entry.isApe && gate.tryAcquireApeRetry(index)) {
            scope.launch {
                // P0-2：转码失败/取消的语义统一由 apeResolver 处理（finally 复位标记，
                // 异常收敛为 null，取消原样抛出），此处只负责结果分支。
                val wav = apeResolver.transcodeToFile(entry.songId, entry.filePath)
                if (wav != null) {
                    queueOrchestrator.replaceMediaItemUri(index, wav)
                    p.prepare()
                    p.play()
                } else {
                    _uiState.update { it.copy(error = "「${entry.title}」APE 转码失败，已跳过") }
                    if (p.hasNextMediaItem()) p.seekToNextMediaItem() else stop()
                }
            }
            return
        }
        _uiState.update { it.copy(error = error.errorCodeName + ": " + (error.message ?: "播放失败")) }
        // 关键修复：非 APE 错误（文件被删/移动、MediaStore URI 失效、编码不支持、
        // 读盘瞬时 IO 失败…）原实现只写一条错误文案就结束，ExoPlayer 已落 IDLE，
        // 整条队列就此停住——用户看到的就是「播着播着自动停了」，而且只在
        // 恰好轮到那一首时出现，所以听起来像「一定概率」。
        // 正确做法与主流播放器一致：坏一首跳一首，继续往下放。
        skipAfterPlaybackError(p, entry)
    }

    /**
     * 单曲播放失败后的自愈：跳过这一首继续放，而不是让整条队列停在这里。
     *
     * 防死循环：连续跳过超过 [MAX_CONSECUTIVE_ERROR_SKIPS] 首就判定「队列不可用」，
     * 暂停但**保留队列**（用户还能手动挑别的歌），并在 UI 上留下错误文案。
     * 计数由 [onIsPlayingChanged] 在真正播起来时归零。
     */
    private fun skipAfterPlaybackError(p: ExoPlayer, failed: QueueEntry?) {
        consecutiveErrorSkips++
        PlaybackEventLog.record(
            "error-skip",
            "consecutive=$consecutiveErrorSkips failedSong=${failed?.songId ?: -1} " +
                "hasNext=${p.hasNextMediaItem()}"
        )
        if (consecutiveErrorSkips > MAX_CONSECUTIVE_ERROR_SKIPS) {
            consecutiveErrorSkips = 0
            PlaybackEventLog.record("error-give-up", "连续失败超过上限，暂停并保留队列")
            p.pause()
            return
        }
        when {
            p.hasNextMediaItem() -> {
                p.seekToNextMediaItem()
                p.prepare()
                p.play()
            }
            // 已到队列末尾：列表循环（ALL / ONE，ONE 时 hasNext 恒 true 走不到这里）
            // 情况下回绕到第一首再试，否则正常结束。
            p.mediaItemCount > 0 && _uiState.value.repeatMode != RepeatMode.OFF -> {
                val first = p.currentTimeline.getFirstWindowIndex(p.shuffleModeEnabled)
                p.seekTo(if (first >= 0) first else 0, 0L)
                p.prepare()
                p.play()
            }
            else -> p.pause()
        }
    }

    // ---------- 播放事件副作用（PlaybackEventSink 实现） ----------
    //
    // 事件「发生了什么」的解释与去重已在 PlaybackEventListener 完成，这里只落地副作用。

    override fun onAudioSessionChanged(audioSessionId: Int) {
        audioEffectController.attachToSession(audioSessionId)
        // session 重建后把当前设置重新灌一遍（音效/播放模式不因换会话丢失）
        audioEffectController.applySettings(lastSettings)
    }

    override fun onSecondaryAudioSessionChanged(audioSessionId: Int) {
        audioEffectController.attachToSession(audioSessionId)
        audioEffectController.applySettings(lastSettings)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        _uiState.update { it.copy(isPlaying = isPlaying) }
        // 正常播起来了：连续失败计数归零（只有「一直在失败」才判定为坏队列）
        if (isPlaying) consecutiveErrorSkips = 0
        else PlaybackEventLog.recordPauseWithQueueIfAny(player, _uiState.value.currentSongId)
        // 焦点跟着「真的在发声」走：起播即申请、真正停住才放弃（判据见 PlaybackAudioFocus）
        playbackFocus.onIsPlayingChanged(isPlaying)
        // 桌面小部件同步（内部去抖，连续变化只推一次 IPC）
        widgetUpdater.requestUpdate()
        // 注意：此处绝不可调用 serviceLauncher.ensureStarted()（见 PlaybackEventListener 注释）。
        saveSessionDebounced()
    }

    override fun onBufferingChanged(isBuffering: Boolean) {
        _uiState.update { it.copy(isBuffering = isBuffering) }
    }

    override fun onTrackChanged(songId: Long, queueIndex: Int, durationMs: Long) {
        _uiState.update { state ->
            state.copy(
                currentSongId = songId,
                queueIndex = queueIndex,
                durationMs = durationMs
            )
        }
    }

    override fun recordPlay(songId: Long) {
        scope.launch { musicRepository.recordPlay(songId) }
    }

    override fun onTrackSideEffects(reason: Int) {
        warmUpFollowingApe()
        // 曲目切换后立即刷新 ReplayGain 增益（取当前曲目标签 + 设置）
        applyReplayGainForCurrent()
        // 无缝播放关闭：在自动切歌（非用户手动 next）的边界处停顿一小会再继续。
        // 注意必须是「暂停 → 延时 → 继续」，只暂停不续播会让播放到此为止（历史缺陷）。
        // 其余原因（用户手动切歌 / 换队列）不动在途的停顿：用户按 next 时播放器
        // 正处于「被停顿暂停」的状态，若在这里取消续播意图，会变成「切了歌却停着」。
        if (!gaplessEnabled && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
            interTrackGap.start()
        }
        // 「播完本曲停止」到点：自然播完切下一首（AUTO）或单曲循环重头（REPEAT）。
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
            reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
        ) {
            finishEndOfTrackSleepTimer()
        }
        // 曲目变化（含清空队列导致切到空）→ 同步桌面小部件
        widgetUpdater.requestUpdate()
    }

    /** 作废在途的曲间停顿（不改变当前播放态，仅取消「稍后自动续播」的意图） */
    override fun cancelInterTrackGap() {
        interTrackGap.cancel()
    }

    override fun recordCompletion(songId: Long) {
        scope.launch { musicRepository.recordCompletion(songId) }
    }

    override fun onPlaybackEnded() {
        // 末首自然播完（无 AUTO/REPEAT 过渡）的收尾。完成去重已由事件层
        // [CompletionRecorder] 在触发处负责，此处只做：睡眠定时归零 + 立即存档 + 空闲停服。
        PlaybackEventLog.record("playback-ended", endedSnapshot())
        finishEndOfTrackSleepTimer()
        saveSessionNow()
        // 判定一律交给 stopServiceIfIdle（它同时看主/备两个实例，交叉淡化收尾时不会误停）
        stopServiceIfIdle()
    }

    override fun onIdleCleared() {
        PlaybackEventLog.record("idle-cleared", endedSnapshot())
        stopServiceIfIdle()
    }

    /** 播放结束类事件的现场快照（诊断报告靠它把「停服」与「淡化中的角色交换」对上账） */
    private fun endedSnapshot(): String =
        "idx=${player?.currentMediaItemIndex} count=${player?.mediaItemCount ?: 0} " +
            "cf=${crossfade.isCrossfading}"

    override fun onError(error: PlaybackException) {
        handlePlayerError(error)
    }

    override fun onSecondaryError(error: PlaybackException) {
        Log.w(TAG, "secondary player error: ${error.errorCodeName} ${error.message}")
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

        /** 连续失败跳过上限：超过就判定队列不可用，暂停但保留队列 */
        const val MAX_CONSECUTIVE_ERROR_SKIPS = 3
    }

    // 媒体项 / URI / 队列项构造已抽到 PlaybackMediaItems（P1-1 拆分第一刀）：
    // 那一簇是纯映射，不需要播放器实例与 UI 状态，独立成文件后可单测。
    // 队列项的 URI 替换（APE 转码产物回写）已随队列编排下沉到 QueueOrchestrator（第八刀）。

    // ---------- 投屏（M1 收尾：MediaSession 切换 + 命令路由） ----------

    /**
     * 开始投屏：暂停本地 → 选定设备 → 投送当前曲。
     *
     * 路由语义与 ViewModel 时期一致：投屏失败（设备拒绝/网络异常/APE 转码失败）时
     * **恢复本地播放**——否则用户点了投屏却两头都没声音，且看不出发生了什么。
     * 若另一次投送仍在进行（已投屏或正在准备），说明是并发/过期场景，由它接管，
     * 本次不抢回本地播放。
     */
    fun startCast(device: CastDevice, song: SongEntity) {
        scope.launch {
            if (_uiState.value.isPlaying) pause()
            dlnaCast.selectDevice(device)
            when (val result = dlnaCast.castSong(song)) {
                is CastResult.Success -> Unit
                // 首投失败 → 恢复本地播放，否则用户点了投屏却两头都没声音。
                // 「已过期」不恢复：那是期间用户自己停了投屏或改了目标，
                // 本地播放已由那条路径恢复，这里再拉一次会把新一轮投送也打断。
                is CastResult.Failed -> {
                    val st = dlnaCast.state.value
                    if (!st.isCasting && !st.isPreparing) play()
                }
                is CastResult.Superseded -> Unit
            }
        }
    }

    /**
     * 停止投屏：停渲染器 → MediaSession 切回本地 → 本地从原位置续播。
     *
     * 无条件续播而非「若本地非播放才播」：投屏期本地必然是暂停的，停止投屏的意图就是
     * 回到手机上继续听。若按 uiState 判断会误判——投屏期 uiState.isPlaying 跟随的是
     * 渲染器（见 bind 里的投屏状态订阅），停止后它仍是 true，会被读成「正在播放」而
     * 不恢复，用户就既听不到电视也听不到手机。
     */
    fun stopCast() {
        scope.launch {
            dlnaCast.stopCast()
            play()
        }
    }

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
                fallbackToLocalPlayback("投屏中断，已切回本地播放")
            }
        }
    }

    // ---------- CastQueueSink：投屏队列推进（M3） ----------

    /**
     * 渲染器自然播完当前这一首 → 推进队列并重新投送下一首。
     *
     * 走 [next] 而非自己推进索引，是为了**复用本地播放模式**（循环/随机）——
     * 投屏的队列推进与本地切歌必须是同一套语义，否则会出现「本地按顺序、
     * 投屏按另一套」的分叉。
     *
     * [next] 内部已带重投（本地推进后调 [recastCurrentIfCasting]），故这里
     * 不再单独投送。
     */
    override fun onCastTrackEnded() {
        next()
    }

    /**
     * 投屏连接失效（设备离线 / 流错误）→ 停止投屏并把播放回退到本地。
     *
     * 与用户主动「停止投屏」走同一条路径：停渲染器 → MediaSession 切回本地 →
     * 本地续播。区别只在于多给一条提示——用户是**被动**失去投屏的，
     * 不提示就会以为手机静音了。
     */
    override fun onCastConnectionLost(reason: String) {
        scope.launch { fallbackToLocalPlayback(reason) }
    }

    /**
     * 投屏不可用时的统一回退：停投屏 → 本地续播 → 给出提示。
     *
     * 顺序不能颠倒：[dlnaCast.stopCast] 会把 [CastState] 整体重置（isCasting 归 false），
     * 只有在它之后调 [play]，本地播放才会真的起播——否则 [play] 会按「仍在投屏」
     * 分支去命令渲染器播放，而渲染器已经不可用了。
     */
    private suspend fun fallbackToLocalPlayback(reason: String) {
        try {
            dlnaCast.stopCast()
        } catch (e: Exception) {
            Log.w(TAG, "stopCast during fallback failed", e)
        }
        _uiState.update { it.copy(error = reason) }
        play()
    }

    // ---------- CastCommandSink：门面回调 ----------

    override val castState: CastState
        get() = dlnaCast.state.value

    override fun playOnRenderer() {
        scope.launch { dlnaCast.play() }
    }

    override fun pauseOnRenderer() {
        scope.launch { dlnaCast.pause() }
    }

    override fun seekOnRenderer(positionMs: Long) {
        scope.launch { dlnaCast.seek(positionMs) }
    }

    /** 门面收到的是「切到第 N 首」：走常规点播路径，末尾会自动重投 */
    override fun skipToIndex(index: Int) {
        playAtIndex(index)
    }

    override fun skipToNext() {
        next()
    }

    override fun skipToPrevious() {
        previous()
    }

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

}

/**
 * 续播守卫（纯函数，可单测）：只有「播放器里什么都没有」时才允许恢复会话。
 *
 * 恢复会重建队列并暂停；若播放器已有媒体项（无论正在播放还是已暂停待播），
 * 恢复都会打断用户当前状态——这正是「进播放页被自动暂停」的根因场景。
 */
internal fun canRestoreSession(playerItemCount: Int, isPlaying: Boolean): Boolean =
    playerItemCount == 0 && !isPlaying
