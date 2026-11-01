package com.puretone.player.playback.cast

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 投屏队列快照（供自动续播判定使用）。
 *
 * 由宿主（[com.puretone.player.playback.PlaybackManager]）从本地队列实时提供——
 * 协调器不持有播放器，也不认识队列，只问「还有下一首吗」「这一首多长」。
 */
internal data class CastQueueInfo(
    /** 队列是否还有下一首 */
    val hasNext: Boolean,
    /** 当前曲在本地记录的总时长（毫秒，0 = 未知） */
    val currentDurationMs: Long
)

/**
 * 投屏队列推进（M3）需要在宿主侧落地的动作。
 *
 * 协调器只做「判定 + 通知」，不动播放器、不动投屏通道——那两样都属于宿主。
 */
internal interface CastQueueSink {
    /**
     * 渲染器自然播完当前这一首。
     *
     * 宿主应推进本地队列（尊重循环/随机），并把新的当前曲重新投给渲染器。
     */
    fun onCastTrackEnded()

    /**
     * 投屏连接已不可用（设备离线 / 流错误）。
     *
     * 宿主应停止投屏、把播放回退到本地，并向用户给出提示。
     */
    fun onCastConnectionLost(reason: String)
}

/**
 * 投屏队列协调器（M3）。
 *
 * ## 它解决什么
 *
 * DLNA 是一次性 push，投屏态下本地播放器又是暂停的，于是「一首播完」后没有任何
 * 东西推进队列——投屏只能听一首就停住。本类在渲染器播完后推进队列并重新投送下一首。
 *
 * ## 为什么单独一个类
 *
 * 这段逻辑的难点全在**时序**：
 * - 「自然播完」与「用户手动切歌」在渲染器侧的表现几乎一样（都会短暂上报停止态），
 *   靠 [CastState.pushGeneration] 才能区分；
 * - 推进之后必须**重新观测到「在播」**才允许下一次推进，否则渲染器迟迟不启动时
 *   会把整个队列一路跳空；
 * - 失联回退必须**每轮投送只触发一次**，否则会反复把播放拉回本地。
 *
 * 把这些状态与判据收在一处，才能用纯 JVM 单测把它们逐条锁死。
 *
 * ## 依赖
 *
 * 只依赖 [CastState] 流与一个队列快照 lambda，**不持有** [DlnaCastManager]——
 * 于是测试可以喂一条假的 [StateFlow]，把上面三类时序逐一复现。
 */
internal class CastQueueCoordinator(
    private val scope: CoroutineScope,
    private val castState: StateFlow<CastState>,
    private val queueInfo: () -> CastQueueInfo,
    private val sink: CastQueueSink
) {
    private var job: Job? = null

    /** 本轮投送是否已观测到「渲染器在播」——「在播 → 不在播」的跃迁才算播完 */
    private var playingSeen: Boolean = false

    /** 已处理过的投送代次；代次变化说明「换了一首」，需重新等待「在播」 */
    private var seenPushGeneration: Long = -1L

    /**
     * 是否要跳过本轮的「在播」观测。
     *
     * 投送（`SetAVTransportURI`）**不会**立刻改变渲染器的播放态：代次变化的那一轮状态里，
     * `isPlaying` 描述的还是**上一首**。若拿它当「新曲已在播」的证据，渲染器切换窗口内
     * 的一次旧曲结束观测就会被误判成「新曲播完了」→ 凭空多跳一首。
     *
     * 故代次变化后的第一轮一律不采信 `isPlaying`，等下一次轮询（那才是新曲的观测）。
     */
    private var ignorePlayingThisRound: Boolean = false

    /** 失联回退是否已触发（同一轮投送只触发一次） */
    private var fallbackDone: Boolean = false

    /** 开始监听投屏状态（幂等） */
    fun start() {
        if (job != null) return
        job = scope.launch {
            castState.collect { onState(it) }
        }
    }

    /** 停止监听并复位全部状态（幂等） */
    fun stop() {
        job?.cancel()
        job = null
        playingSeen = false
        seenPushGeneration = -1L
        fallbackDone = false
        ignorePlayingThisRound = false
    }

    /**
     * 处理一次投屏状态变化（供单测直接驱动）。
     *
     * 判据全部委托 [CastQueuePolicy]，本方法只负责状态机的推进与去重。
     */
    internal fun onState(st: CastState) {
        // 1) 换了一首（投送代次变化）→ 重新等待「渲染器在播」。
        //    手动切歌同样会改代次，这正是它与「自然播完」能被区分开的关键。
        if (st.pushGeneration != seenPushGeneration) {
            seenPushGeneration = st.pushGeneration
            playingSeen = false
            fallbackDone = false
            ignorePlayingThisRound = true
        }

        // 2) 设备失联 → 回退本地（每轮投送只触发一次）
        if (st.isConnectionLost) {
            ignorePlayingThisRound = false
            if (!fallbackDone) {
                fallbackDone = true
                playingSeen = false
                sink.onCastConnectionLost(st.error ?: "投屏设备已断开")
            }
            return
        }

        // 3) 跳过本轮的「在播」观测（见 [ignorePlayingThisRound]）
        if (ignorePlayingThisRound) {
            ignorePlayingThisRound = false
            return
        }

        // 4) 未在投屏 → 复位「见过在播」，下一轮投送重新计数
        if (!st.isCasting) {
            playingSeen = false
            return
        }

        // 5) 正在播 → 记下跃迁起点
        if (st.isPlaying) {
            playingSeen = true
            return
        }

        // 6) 由「在播」跃迁到「不在播」→ 交给策略函数判定是不是播完了
        if (!playingSeen) return
        val info = queueInfo()
        val ended = CastQueuePolicy.shouldAutoAdvance(
            wasPlaying = true,
            isPlaying = false,
            playbackState = st.playbackState,
            progressMs = st.progressMs,
            hasProgressInfo = st.hasProgressInfo,
            rendererDurationMs = st.durationMs,
            localDurationMs = info.currentDurationMs,
            hasNext = info.hasNext
        )
        if (!ended) return

        // 推进后必须**重新观测到「在播」**才允许下一次推进：
        // 新曲的投送可能耗时数十秒（APE 转码），这期间渲染器一直报「不在播」，
        // 若不置位就会把剩余队列一路跳空。
        playingSeen = false
        sink.onCastTrackEnded()
    }
}
