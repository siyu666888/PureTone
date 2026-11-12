package com.puretone.player.playback.cast

import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
     * 队列**最后一首**自然播完（循环关闭、没有下一首）。
     *
     * 电视已经自行结束传输并退出投屏画面，但 DLNA push 模式不会通知手机，
     * [CastState.isCasting] 仍为 true → 手机长期显示「已连接」却无声无画。
     * 宿主应结束投屏会话、把播放态写回停止，并给出提示；是否回本地续播由
     * 产品语义决定（当前约定：结束投屏并回本地，不自动续播）。
     */
    fun onCastQueueFinished()

    /**
     * 渲染器在**曲中**停止（不是自然播完），且确认窗口内没能自行恢复。
     *
     * 宿主应重投**当前这一首**并 seek 回中断位置，不能推进队列——否则电视画面
     * 会在重连取流时被新 URI 顶掉，用户看到「投屏断开 / 凭空跳歌」。
     */
    fun onCastInterrupted(progressMs: Long)

    /**
     * 曲中 STOPPED 但处于「用户刚拖过进度条」的保护窗口内 → 先尝试**就地 play** 恢复，
     * 而非整套重投（A 方案，174553 体验层「拖进度条 2~4s 静音」修复）。
     *
     * 宿主应下发一次 Play 尝试就地拉回渲染器；若观察窗口内仍未恢复，再降级走
     * [onCastInterrupted]（整套重投当前曲），不能推进队列。
     */
    fun onCastInterruptedSeekRecover(progressMs: Long)

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
    private val sink: CastQueueSink,
    /** 墙钟注入：终止态确认窗口要能用虚拟时间单测驱动 */
    private val nowProvider: () -> Long = { System.currentTimeMillis() },
    private val confirmMs: Long = CastStopPolicy.CONFIRM_MS,
    /**
     * 用户**手动拖动进度条**后，渲染器瞬间 STOPPED 的「就地恢复」保护窗口。
     * 该窗口内出现的曲中 STOPPED 改走 play 恢复而非整套重投（A 方案，174553 体验层修复）。
     * 超过该窗口的 STOPPED 仍按正常曲中中断处理，避免掩盖真实断流。
     */
    private val seekProtectMs: Long = 3_000L,
    /** 诊断日志注入：生产层接 [CastNetworkLog]，纯 JVM 单测默认 no-op（Log 未 mock 会抛） */
    private val log: (String) -> Unit = {}
) {
    private companion object {
        /**
         * 连续「同一位置曲中停止」重投上限：超过则放弃重投、按曲末推进下一首。
         *
         * 真机 20261107_214943 的兜底：曲中容差放宽到 10s 后（见 [CastStopPolicy]），
         * 仍可能出现「seek 到距曲末 >10s、渲染器 STOPPED 时 RelTime 又不前进」的极端
         * 情形——重投 + seek 回同一位置后电视又在该位置 STOPPED，陷入无限重投。连续
         * 2 次在同一位置中断即认定「这个位置播不下去」，改按曲末推进，避免电视画面
         * 反复闪烁、用户只能手动切歌打断。
         */
        const val MAX_CONSECUTIVE_INTERRUPT = 2

        /** 「同一位置」的容差：中断位置相差 < 1s 视为同一处（渲染器取整/网络延迟） */
        const val SAME_SPOT_TOLERANCE_MS = 1_000L

        /**
         * seek-recover 的短确认窗口：用户拖进度条后的瞬间 STOPPED 只需短窗即可判定，
         * 比曲中正常中断的 [CastStopPolicy.CONFIRM_MS] 更短，尽快就地 play 恢复、压低静音。
         */
        const val SEEK_RECOVER_CONFIRM_MS = 1_000L
    }

    private var job: Job? = null

    /** 曲中 STOPPED 的待确认窗口：到期仍未恢复才重投当前曲 */
    private data class PendingStop(
        val progressMs: Long,
        val sinceMs: Long,
        /** 是否处于「用户拖进度条」保护窗口：是则恢复走就地 play，否则整套重投 */
        val seekRecover: Boolean = false,
        /** 确认窗口时长（seek-recover 用短窗 [SEEK_RECOVER_CONFIRM_MS]，其余沿用 [confirmMs]；
         *  两个构造点均显式传入，此处默认值不参与实际创建） */
        val windowMs: Long = 0L
    )

    private var pendingStop: PendingStop? = null

    /**
     * 确认窗口的到期定时器。
     *
     * [pendingStop] 的到期判定**不能只靠 [castState.collect] 的状态帧驱动**：真机
     * 20261107_203641 里，渲染器 STOPPED 后状态与进度都稳定不变，[StateFlow] 按
     * `equals` 去重不再发新帧，`onState` 不会被调用，`isConfirmed` 永远不被检查——
     * 确认窗口卡死，电视黑屏 15 秒直到用户手动切歌。这里用一个独立定时器兜底，
     * 到期主动判定，不再依赖下一帧恰好会来。
     */
    private var pendingConfirmJob: Job? = null

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

    /** 末首收尾是否已触发（同一轮投送只触发一次，避免 1Hz 反复结束会话） */
    private var queueFinished: Boolean = false

    /** 上次曲中停止重投的进度位置（-1 = 无），用于识别「重投后又在同一位置停止」 */
    private var lastInterruptPosMs: Long = -1L

    /** 连续在同一位置触发曲中停止重投的次数（见 [MAX_CONSECUTIVE_INTERRUPT]） */
    private var consecutiveInterrupt: Int = 0

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
        queueFinished = false
        ignorePlayingThisRound = false
        lastInterruptPosMs = -1L
        consecutiveInterrupt = 0
        clearPendingStop()
    }

    /** 清空待确认窗口并停掉其定时器；所有「取消窗口」的出口统一走这里 */
    private fun clearPendingStop() {
        pendingStop = null
        pendingConfirmJob?.cancel()
        pendingConfirmJob = null
    }

    /**
     * 为当前 [pendingStop] 启动/重置到期定时器。
     *
     * 顺延（电视期间又来取流）后要重新计时：按「剩余时长」重排，避免旧定时器在
     * 顺延后提前触发。定时器触发时若发现又被顺延，则递归重排；否则确认到期，
     * 清空窗口并通知 [CastQueueSink.onCastInterrupted]。
     */
    private fun schedulePendingConfirm() {
        pendingConfirmJob?.cancel()
        val pending = pendingStop ?: return
        val remainingMs = (pending.windowMs - (nowProvider() - pending.sinceMs)).coerceAtLeast(0L)
        pendingConfirmJob = scope.launch {
            delay(remainingMs)
            val p = pendingStop ?: return@launch
            val st = castState.value
            // 期间电视又来取流 → 顺延窗口重新计时，绝不在这时重投当前曲打断重连。
            if (st.lastFetchMs > p.sinceMs) {
                pendingStop = p.copy(sinceMs = st.lastFetchMs)
                schedulePendingConfirm()
                return@launch
            }
            pendingStop = null
            pendingConfirmJob = null
            onInterruptConfirmed(p.progressMs, p.seekRecover)
        }
    }

    /**
     * 曲中停止确认到期后的统一出口。
     *
     * 先做「连续同位置重投」上限判断（见 [MAX_CONSECUTIVE_INTERRUPT]）：连续两次在同一
     * 位置中断，认定这个位置根本播不下去，放弃重投、改按曲末推进（有下一首投下一首，
     * 末首则结束会话），避免电视画面反复闪烁、用户只能手动切歌打断的死循环。
     */
    private fun onInterruptConfirmed(progressMs: Long, seekRecover: Boolean) {
        playingSeen = false
        // 「用户拖进度条」保护窗口内的 STOPPED：先尝试就地 play 恢复，绝不走整套重投。
        if (seekRecover) {
            sink.onCastInterruptedSeekRecover(progressMs)
            return
        }
        val sameSpot = lastInterruptPosMs >= 0L &&
            abs(progressMs - lastInterruptPosMs) < SAME_SPOT_TOLERANCE_MS
        consecutiveInterrupt = if (sameSpot) consecutiveInterrupt + 1 else 1
        lastInterruptPosMs = progressMs
        if (consecutiveInterrupt >= MAX_CONSECUTIVE_INTERRUPT) {
            consecutiveInterrupt = 0
            lastInterruptPosMs = -1L
            if (queueInfo().hasNext) {
                log("cast-interrupted-abandon pos=${progressMs}ms -> advance-next")
                sink.onCastTrackEnded()
            } else {
                log("cast-interrupted-abandon pos=${progressMs}ms -> finish-queue")
                queueFinished = true
                sink.onCastQueueFinished()
            }
            return
        }
        sink.onCastInterrupted(progressMs)
    }

    /**
     * 处理一次投屏状态变化（供单测直接驱动）。
     *
     * 判据委托 [CastQueuePolicy] / [CastStopPolicy]，本方法只负责状态机的推进与去重。
     * 曲末 STOPPED 立即推进（保持历史修复）；曲中 STOPPED 先进确认窗口，到期才重投当前曲；
     * 末首 STOPPED 且没有下一首时发一次 [CastQueueSink.onCastQueueFinished]，结束幽灵会话。
     */
    internal fun onState(st: CastState) {
        // 1) 换了一首（投送代次变化）→ 重新等待「渲染器在播」。
        //    手动切歌同样会改代次，这正是它与「自然播完」能被区分开的关键。
        if (st.pushGeneration != seenPushGeneration) {
            seenPushGeneration = st.pushGeneration
            playingSeen = false
            fallbackDone = false
            queueFinished = false
            ignorePlayingThisRound = true
            clearPendingStop()
        }

        // 2) 设备失联 → 回退本地（每轮投送只触发一次）
        if (st.isConnectionLost) {
            ignorePlayingThisRound = false
            clearPendingStop()
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
            clearPendingStop()
            return
        }

        // 5) 正在播 → 记下跃迁起点；曲中停止的待确认窗口作废（电视自己重连成功）
        if (st.isPlaying) {
            playingSeen = true
            clearPendingStop()
            return
        }

        // 5.5) 曲中 STOPPED 的确认窗口：期间电视又来取流就把窗口顺延，绝不在这时换 URI
        val pending = pendingStop
        if (pending != null) {
            if (st.lastFetchMs > pending.sinceMs) {
                pendingStop = pending.copy(sinceMs = st.lastFetchMs)
                schedulePendingConfirm()
                return
            }
            if (CastStopPolicy.isConfirmed(pending.sinceMs, nowProvider(), st.lastFetchMs, pending.windowMs)) {
                val progressMs = pending.progressMs
                clearPendingStop()
                onInterruptConfirmed(progressMs, pending.seekRecover)
            }
            return
        }

        // 6) 由「在播」跃迁到「不在播」→ 交给策略函数判定是不是播完了
        if (!playingSeen) return
        val info = queueInfo()
        val nowMs = nowProvider()
        val kind = CastStopPolicy.classify(
            playbackState = st.playbackState,
            progressMs = st.progressMs,
            rendererDurationMs = st.durationMs,
            localDurationMs = info.currentDurationMs,
            castStartMs = st.castStartMs,
            nowMs = nowMs
        )
        if (kind == CastStopPolicy.Kind.MID_TRACK) {
            // 曲中停止：先观察确认窗口，让电视把重连取流走完；不推进队列。
            // 「用户刚拖过进度条」保护窗口内的 STOPPED，大概率只是拖动的瞬间复位，
            // 改走短窗 + 就地 play 恢复（seekRecover），而非整套重投（A 方案）。
            val recentSeek = st.lastSeekMs > 0L && (nowMs - st.lastSeekMs) < seekProtectMs
            pendingStop = PendingStop(
                progressMs = st.progressMs,
                sinceMs = nowMs,
                seekRecover = recentSeek,
                windowMs = if (recentSeek) SEEK_RECOVER_CONFIRM_MS else confirmMs
            )
            schedulePendingConfirm()
            return
        }
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
        if (!ended) {
            // 队列已到末首：电视播完自己结束传输并退出画面，但 isCasting 仍为 true。
            // 旧实现在这里直接 return，把「播完」当成「无需动作」，手机便长期显示
            // 「已连接」却没有声音也没有画面（真机 20261107_184914 18:48:40）。
            // 补一条明确的收尾信号，由宿主结束投屏会话；只认 STOPPED，
            // 用户中途暂停（PAUSED）绝不关投屏，且每轮投送只触发一次。
            if (!queueFinished && CastQueuePolicy.shouldFinishQueue(
                    wasPlaying = true,
                    isPlaying = false,
                    playbackState = st.playbackState,
                    hasNext = info.hasNext
                )
            ) {
                playingSeen = false
                queueFinished = true
                sink.onCastQueueFinished()
            }
            return
        }

        // 推进后必须**重新观测到「在播」**才允许下一次推进：
        // 新曲的投送可能耗时数十秒（APE 转码），这期间渲染器一直报「不在播」，
        // 若不置位就会把剩余队列一路跳空。
        playingSeen = false
        sink.onCastTrackEnded()
    }
}
