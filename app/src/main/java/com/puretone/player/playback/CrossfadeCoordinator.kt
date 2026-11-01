package com.puretone.player.playback

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.puretone.player.data.settings.PureToneSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 交叉淡化对播放器的**最小操作面**。
 *
 * 存在的理由不是抽象美感：协调器要能脱离 ExoPlayer 单测（[ExoPlayer] 是 final 实现、
 * 直接 mock 需引入额外框架），而真正被用到的只有下面这十来个动作。收窄之后，
 * 「淡化的状态机与时序」可以在纯 JVM 上用替身完整验证——这恰恰是最容易写歪的部分
 * （幂等收尾、取消后复位、重复触发）。
 *
 * 语义与原实现一一对应，见各方法注释。
 */
@OptIn(UnstableApi::class)
internal interface CrossfadePlayer {
    /** 当前曲目总时长（毫秒）；未知为 [C.TIME_UNSET] 等负值 */
    val durationMs: Long

    /** 当前播放位置（毫秒） */
    val positionMs: Long

    /** 队列内媒体项总数 */
    val itemCount: Int

    /** 当前媒体项索引 */
    val currentIndex: Int

    /** 是否还有下一首 */
    val hasNext: Boolean

    /** 当前媒体项的媒体 ID 解析出的歌曲 id（无法解析为 null） */
    val currentItemId: Long?

    /** 取 [index] 及其之后的全部媒体项（越界项跳过，不抛异常） */
    fun itemsFrom(index: Int): List<MediaItem>

    /** 移除 [index] 处的媒体项（失败静默：索引可能在读取后已失效） */
    fun removeItem(index: Int)

    /** 在 [index] 处插入媒体项（取消淡化时把「让出的下一首」还给主播放器） */
    fun insertItems(index: Int, items: List<MediaItem>)

    /** 置为单曲队列（淡化窗口内备援只承载一首） */
    fun setItem(item: MediaItem)

    /** 追加媒体项（淡入结束后把后续曲目补全到新主） */
    fun addItems(items: List<MediaItem>)

    /** 设置输出音量（0f~1f） */
    fun setVolume(volume: Float)

    /** 从开头起播：seek(0) → prepare → play */
    fun startFromBeginning()

    /** 暂停并清空队列（备援复位，供下次淡化复用） */
    fun pauseAndClear()
}

/** [CrossfadePlayer] 的 ExoPlayer 实现（唯一真身） */
@OptIn(UnstableApi::class)
internal class ExoCrossfadePlayer(private val player: ExoPlayer) : CrossfadePlayer {

    /** 底层播放器：宿主在角色交换时需要它去换 MediaSession 的 player 与监听器 */
    val exo: ExoPlayer get() = player

    override val durationMs: Long get() = player.duration

    override val positionMs: Long get() = player.currentPosition

    override val itemCount: Int get() = player.mediaItemCount

    override val currentIndex: Int get() = player.currentMediaItemIndex

    override val hasNext: Boolean get() = player.hasNextMediaItem()

    override val currentItemId: Long?
        get() = runCatching { player.currentMediaItem?.mediaId?.toLongOrNull() }.getOrNull()

    override fun itemsFrom(index: Int): List<MediaItem> =
        (index until player.mediaItemCount).mapNotNull { idx ->
            runCatching { player.getMediaItemAt(idx) }.getOrNull()
        }

    override fun removeItem(index: Int) {
        runCatching { player.removeMediaItem(index) }
    }

    override fun insertItems(index: Int, items: List<MediaItem>) {
        runCatching { player.addMediaItems(index.coerceAtLeast(0), items) }
    }

    override fun setItem(item: MediaItem) {
        player.setMediaItem(item)
    }

    override fun addItems(items: List<MediaItem>) {
        runCatching { player.addMediaItems(items) }
    }

    override fun setVolume(volume: Float) {
        player.setVolume(volume)
    }

    override fun startFromBeginning() {
        player.seekTo(0)
        player.prepare()
        player.play()
    }

    override fun pauseAndClear() {
        runCatching {
            player.pause()
            player.clearMediaItems()
        }
    }
}

/**
 * 交叉淡化协调器（A 路线：双实例真重叠）。
 *
 * 负责「备援播放器」的全部生命周期与淡化状态机：
 * - 触发判定委托 [CrossfadePolicy]（纯函数），步进计划委托 [CrossfadeRamp]（纯函数）；
 * - 本类只持有状态与时序：淡出方是谁、淡入到哪个索引、剩余曲目、渐变协程；
 * - **角色交换本身不在这里做**——那要动主播放器引用、ReplayGain 处理器、MediaSession
 *   与两个监听器，属于宿主的所有权，通过 [Host.onFinalize] 回传。
 *
 * 不变式（任何退出路径都必须维持）：
 * 1. 未淡化时，备援播放器处于「已暂停 + 空队列 + 音量 1f」的可复用状态；
 * 2. [finalize] 幂等——渐变协程与「主播放器自然播完」的 STATE_ENDED 回调谁先到谁收尾，
 *    后到者直接返回（历史上正是缺这层守卫导致重复收尾）；
 * 3. [cancel] 后必须回到不变式 1，否则下一次淡化会从错误音量起播（听感上是爆音）。
 */
@OptIn(UnstableApi::class)
internal class CrossfadeCoordinator(
    private val scope: CoroutineScope,
    private val host: Host
) {

    /** 协调器需要宿主提供的上下文；宿主侧只有「所有权在此」的东西才回调出去 */
    internal interface Host {
        /** 当前设置快照：淡化时长与 ReplayGain 参数随用户设置实时变化 */
        val settings: PureToneSettings

        /** 当前队列（判定下一首是否需异步转码） */
        val queue: List<QueueEntry>

        /** 投屏进行中：本地播放器已暂停，不得触发淡化 */
        val isCasting: Boolean

        /**
         * 淡入结束：备援已可提升为新主。
         * 宿主负责交换主/备引用、ReplayGain 处理器、MediaSession 指向与监听器，并刷新 UI。
         */
        fun onFinalize(newPrimary: CrossfadePlayer, outgoing: CrossfadePlayer, nextIndex: Int)
    }

    /** 备援播放器（淡化窗口内承载下一首，其余时间空闲） */
    private var secondary: CrossfadePlayer? = null

    /** 备援播放器独立的 ReplayGain 处理器（增益按各自曲目实时刷新） */
    private var secondaryGain: ReplayGainAudioProcessor? = null

    private var crossfading: Boolean = false
    private var job: Job? = null
    private var pendingRemaining: List<MediaItem> = emptyList()

    /** 淡化开始被从主播放器摘下的「下一首」；取消淡化时原样还回去（否则队列永久少一首） */
    private var removedItem: MediaItem? = null
    private var nextIndex: Int = -1
    private var outgoing: CrossfadePlayer? = null

    /** 是否处于淡化中（宿主据此跳过常规切歌等命令） */
    val isCrossfading: Boolean get() = crossfading

    /** 当前备援（宿主在角色交换后必须把原主重新注入，否则第二次淡化会拿主播放器当备援） */
    internal val currentSecondary: CrossfadePlayer? get() = secondary

    /** [player] 是否为本次淡化的淡出方（STATE_ENDED 兜底收尾判定用） */
    fun isOutgoing(player: ExoPlayer): Boolean =
        (outgoing as? ExoCrossfadePlayer)?.exo === player

    /** 由宿主在 bind 时注入备援播放器及其 ReplayGain 处理器 */
    fun attach(secondary: CrossfadePlayer, gain: ReplayGainAudioProcessor?) {
        this.secondary = secondary
        this.secondaryGain = gain
    }

    /** 取消进行中的淡化并释放备援（宿主 unbind 时） */
    fun detach() {
        cancel()
        secondary = null
        secondaryGain = null
    }

    /**
     * ticker 每 500ms 调用：主播放器临近本曲结尾且存在下一首时启动淡化。
     *
     * 提前量 = crossfadeMs + 400ms（ticker 粒度 500ms，预留 400ms 防止错过边界；
     * 错过后主播放器按原路径正常切歌，仅本次无淡化）。APE 等在边界无法精确对齐，
     * 不参与淡化（退化为无缝/曲间停顿）。
     */
    fun maybeStart(primary: CrossfadePlayer) {
        // 投屏守卫：投屏中本地播放器已暂停，若仍被 ticker 推进到曲尾会误触发双实例淡化。
        if (host.isCasting) return
        if (crossfading) return
        val crossfadeMs = host.settings.crossfadeMs
        val duration = primary.durationMs.takeIf { it > 0 } ?: return
        val nextIndex = primary.currentIndex + 1
        val nextEntry = host.queue.getOrNull(nextIndex) ?: return
        if (!CrossfadePolicy.shouldTrigger(
                durationMs = duration,
                positionMs = primary.positionMs,
                crossfadeMs = crossfadeMs,
                hasNext = primary.hasNext,
                isApe = nextEntry.isApe
            )
        ) return
        start(primary, nextIndex, nextEntry, duration - primary.positionMs)
    }

    private fun start(primary: CrossfadePlayer, index: Int, nextEntry: QueueEntry, remainMs: Long) {
        val sec = secondary ?: return
        val gain = secondaryGain ?: return
        // 先取出主播放器「当前项之后」的全部媒体项（含即将起播的下一首），供淡入结束后补全队列。
        // 必须在移除下一首之前取，避免索引偏移。
        val remaining = primary.itemsFrom(index)
        if (remaining.isEmpty()) return
        // 主播放器移除下一首：避免其在淡出过程中自行续播（否则重复计完成 / 重复切歌）
        primary.removeItem(index)
        removedItem = remaining.first()
        // 备援承载下一首，音量从 0 起步
        sec.setItem(remaining.first())
        sec.setVolume(0f)
        sec.startFromBeginning()
        // 备援施加下一首的 ReplayGain（与主播放器各自独立）
        val s = host.settings
        val rgDb = if (s.replayGainMode == PureToneSettings.RG_ALBUM) nextEntry.rgAlbumGain else nextEntry.rgTrackGain
        gain.setGainLinear(
            ReplayGainAudioProcessor.computeLinearGain(s.replayGainEnabled, rgDb, s.replayGainPreampDb)
        )
        crossfading = true
        outgoing = primary
        nextIndex = index
        pendingRemaining = remaining.drop(1)
        PlaybackEventLog.record(
            "crossfade-start",
            "idx=$index cf=${s.crossfadeMs}ms remain=${remainMs}ms"
        )
        val rampMs = CrossfadeRamp.rampDurationMs(s.crossfadeMs, remainMs)
        job = scope.launch {
            val steps = CrossfadeRamp.steps(rampMs)
            val stepMs = CrossfadeRamp.stepMs(rampMs)
            repeat(steps) { i ->
                primary.setVolume(CrossfadeRamp.outgoingVolume(i, steps))
                sec.setVolume(CrossfadeRamp.incomingVolume(i, steps))
                delay(stepMs)
            }
            primary.setVolume(0f)
            sec.setVolume(1f)
            finalize()
        }
    }

    /**
     * 淡入结束（或主播放器 STATE_ENDED 兜底）：把备援提升为新主、旧主复位为空备援。
     * 幂等守卫 [crossfading] 保证只执行一次。
     */
    fun finalize() {
        if (!crossfading) return
        val outgoingPlayer = outgoing ?: run { reset(); return }
        val sec = secondary ?: run { reset(); return }
        val index = nextIndex
        // 把剩余曲目补全到新主，维持「主播放器持有完整剩余队列」不变式
        if (pendingRemaining.isNotEmpty()) {
            sec.addItems(pendingRemaining)
        }
        pendingRemaining = emptyList()
        nextIndex = -1
        removedItem = null
        outgoing = null
        crossfading = false
        // STATE_ENDED 兜底收尾时渐变协程可能还在跑：这里必须真的取消它。
        // 只把 job 置空的话，协程会继续按旧角色往「已交换的两个实例」上写音量
        // （把刚成为新主的那一路又从途中值拉回去），而且再也没人能取消它。
        job?.cancel()
        job = null
        PlaybackEventLog.record("crossfade-finalize", "idx=$index")
        // ---- 顺序至关重要 ----
        // 先把所有权交给宿主（player 引用 / MediaSession / 监听器全部换位），再复位旧主。
        // 反过来（先 pauseAndClear 再交权）那 40ms 里旧主仍是宿主眼中的「当前播放器」：
        // pause() + clearMediaItems() 会让它落到「队列已空且不在播」的空闲判定上，
        // 触发 stopServiceIfIdle → 整个服务被停掉（连带 release 两个播放器与会话），
        // 表现为「开了交叉淡化后一首播完就整体停止，随后前台服务超时崩溃」。
        host.onFinalize(newPrimary = sec, outgoing = outgoingPlayer, nextIndex = index)
        // 此时 crossfade.attach() 已把旧主重新注入为备援，secondaryGain 指向旧主的处理器
        outgoingPlayer.setVolume(1f)
        secondaryGain?.setGainLinear(1f)
        outgoingPlayer.pauseAndClear()
    }

    /**
     * 取消进行中的淡化（用户切歌 / 拖动 / 换队列 / 停止等）。
     * 丢弃备援内容、复位双方音量，使后续命令在「主播放器持有完整队列」的常规状态下执行。
     */
    fun cancel() {
        if (!crossfading) return
        job?.cancel()
        job = null
        val out = outgoing
        val removed = removedItem
        val index = nextIndex
        reset()
        // 把「让出的下一首」还给主播放器。淡化窗口内它被 removeItem 出去了，
        // 不还回去的话，用户在中途按暂停/切歌（cancel）后队列会永久少一首。
        if (out != null && removed != null && index >= 0) out.insertItems(index, listOf(removed))
        out?.setVolume(1f)
        secondaryGain?.setGainLinear(1f)
        secondary?.let { sec ->
            sec.pauseAndClear()
            sec.setVolume(1f)
        }
        PlaybackEventLog.record("crossfade-cancel", "idx=$index")
    }

    /** 清空淡化状态（不含播放器复位，供 finalize 内部与异常路径复用） */
    private fun reset() {
        crossfading = false
        job?.cancel()
        job = null
        outgoing = null
        removedItem = null
        nextIndex = -1
        pendingRemaining = emptyList()
    }
}
