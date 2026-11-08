package com.puretone.player.playback.cast

/**
 * 「曲中 STOPPED」的判定与终止态确认窗口（2026-11-07 真机「电视画面断开」修复）。
 *
 * ## 背景
 *
 * 诊断 20261107_165555：16:55:25 电视先重新拉全量流（`http-fetch 黄昏.flac range=-`），
 * 紧接着把 AVTransport 置 `STOPPED`，`own-stop-pos progress=28000 duration=344000`——
 * 播到 28 秒 / 总 344 秒，**明确的曲中停止**。但 [CastQueuePolicy] 为修 v1.9.2/v1.9.3
 * 三次「曲末卡死」把 STOPPED 一律判成播完，于是同一秒 `cast-track-ended advance=1`
 * 把 URI 换成下一首：电视正在进行的重连取流被顶掉，DLNA 播放器退出画面，用户看到的
 * 就是「投屏播放时电视画面断开」。换曲后到导出报告为止，新曲一条 `http-fetch` 都没有，
 * 手机卡片上的 00:24 其实是 uiState 镜像 + 外推的假进度。
 *
 * ## 两条规则
 *
 * - **只对曲中停止加确认窗口**：STOPPED 后的 2.5 秒里若电视又来取流、或状态回到
 *   PLAYING，说明只是重连/重缓冲，不换 URI；窗口到期仍稳定 STOPPED 才判真中断。
 *   曲末 STOPPED **不进窗口、立即推进**，保住 [CastQueuePolicy] 的历史修复与切歌速度。
 * - **双证据判曲中**：渲染器自报「进度明显在曲中」**且**本地墙钟「本轮也远没播到曲末」。
 *   只看前者会重蹈 20:43:58 真机 `tv=8000/193000` 的覆辙——那次是曲末，渲染器把
 *   RelTime 复位到 8 秒，但本地已播约 193 秒；只看后者又会在暂停很久后误判。
 *   渲染器不给总时长 / 本轮投送时刻未知时一律按曲末处理（保守，不擅自改队列）。
 *
 * 与 [CastQueuePolicy] / [CastPlayStatePolicy] 一样收成纯函数，便于纯 JVM 逐条锁死。
 */
internal object CastStopPolicy {

    /** STOPPED 后观察多久才确认「真中断」（覆盖至少两次 1Hz 轮询） */
    const val CONFIRM_MS = 2_500L

    /** 判定「已到曲末」的容差，与 [CastQueuePolicy.END_TOLERANCE_MS] 同口径 */
    const val END_TOLERANCE_MS = 2_000L

    /**
     * 判定「仍是曲中」的曲末容差（毫秒）。
     *
     * 真机 20261107_214943：用户把进度拖到距曲末约 2.2 秒处（255000 / 总 257199），
     * 电视播完剩余 2.2 秒自然 STOPPED，但渲染器 STOPPED 时的 RelTime 停在 255000
     * （自然播完的偏差达 2199ms，略超 [END_TOLERANCE_MS]），被「进度 < 总长 - 2s」
     * 误判成曲中停止 → 重投 + seek 回 255000 → 又自然播完 → 无限重投死循环。
     *
     * 「自然播完偏差」与「seek 到曲末附近再播完」本质都是「进度距曲末只剩几秒就
     * STOPPED」，把曲中容差放宽到 10s：距曲末 ≤10s 一律按曲末推进下一首，不再
     * 重投当前曲。真曲中停止（buffer 耗尽）几乎都发生在距曲末远大于 10s 处，
     * 不会因此漏判；即便极罕见地停在最后 10s 内，按曲末跳歌也远好于死循环。
     */
    const val MID_TRACK_END_TOLERANCE_MS = 10_000L

    enum class Kind {
        /** 曲末自然播完：保持原有「立即推进下一首」语义 */
        END,

        /** 曲中停止：进入确认窗口，到期后重投当前曲并续播，不跳下一首 */
        MID_TRACK
    }

    /**
     * @param playbackState 本轮渲染器状态名（仅 STOPPED 参与判定）
     * @param progressMs 渲染器上报的 RelTime
     * @param rendererDurationMs 渲染器自报 TrackDuration（0=未上报）
     * @param localDurationMs 本地记录的总时长（渲染器不报时兜底）
     * @param castStartMs 本轮投送**实际完成**时刻（0=未知）
     * @param nowMs 当前墙钟（由调用方传入，保持本函数纯）
     */
    fun classify(
        playbackState: String,
        progressMs: Long,
        rendererDurationMs: Long,
        localDurationMs: Long,
        castStartMs: Long,
        nowMs: Long
    ): Kind {
        if (playbackState != CastQueuePolicy.STATE_STOPPED) return Kind.END
        val total = rendererDurationMs.takeIf { it > 0L } ?: localDurationMs
        if (total <= END_TOLERANCE_MS || castStartMs <= 0L) return Kind.END

        val rendererMidTrack = progressMs > 0L && progressMs < total - MID_TRACK_END_TOLERANCE_MS
        val elapsedMs = nowMs - castStartMs
        // 墙钟必须落在「已开播且远未到曲末」区间；时钟回拨（elapsed<0）按曲末处理
        val wallMidTrack = elapsedMs > 0L && elapsedMs < total - MID_TRACK_END_TOLERANCE_MS
        return if (rendererMidTrack && wallMidTrack) Kind.MID_TRACK else Kind.END
    }

    /**
     * 确认窗口是否到期。
     *
     * @param stoppedSinceMs 首次观测到 STOPPED 的墙钟
     * @param lastFetchMs 电视最近一次真正取流的墙钟（0=本轮从未取流）
     * @return true=可以判定真中断；false=仍在观察（窗口未到 / 期间电视又来取流）
     */
    fun isConfirmed(
        stoppedSinceMs: Long,
        nowMs: Long,
        lastFetchMs: Long,
        confirmMs: Long = CONFIRM_MS
    ): Boolean {
        // STOPPED 之后电视又来取流 = 正在重连，顺延窗口，不打断它。
        // 诊断里 16:55:25 的 http-fetch 与 own-state STOPPED 同秒，正是这条要接住的场景。
        if (lastFetchMs > stoppedSinceMs) return false
        return nowMs - stoppedSinceMs >= confirmMs
    }
}
