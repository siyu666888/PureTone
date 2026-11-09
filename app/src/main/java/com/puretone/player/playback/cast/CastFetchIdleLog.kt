package com.puretone.player.playback.cast

/**
 * 取流空窗诊断（R7 / 054751）。
 *
 * 投屏是 push 模式：手机自己就是 HTTP 音源，**电视来取流才有声音**。诊断里的
 * `http-fetch` 日志是 `CastHttpServer.sendFile()` 把响应 copy 完之后才落盘的，
 * 且每条投送最多 8 条，所以它的时间戳是「响应写完」不是「请求到达」，不能拿
 * 来算取流空窗；唯一可靠的是 [CastHttpServer.lastSuccessfulFetchMs]。
 *
 * 采样策略（避免 1Hz 轮询刷屏）：
 * - 未建立：每跨一个 [STEP_MS] 档位打一条，一首歌最多几条；
 * - 已建立：只在渲染器 `PAUSED` 状态跳变处另记一条，用来判断「电视缓冲欠载时
 *   手机是不是也停了供流」——这是 R7 息屏停顿的 A/B 根因判据。
 *
 * logger 由外部注入：本类要在纯 JVM 单测里锁死，不能直接调
 * [CastNetworkLog.log]（`android.util.Log not mocked`，见 E2 的教训）。
 */
internal class CastFetchIdleLog(
    private val stepMs: Long = STEP_MS,
    private val logger: (String) -> Unit
) {
    private var lastStep = 0L
    private var lastPaused = false

    /** 每次投送 / 结束投屏时复位，否则上一首的大档位会压住新曲的采样。 */
    fun reset() {
        lastStep = 0L
        lastPaused = false
    }

    /**
     * 每轮 1Hz 轮询调用一次。
     *
     * @param fetchMs 当前 token 最近一次成功取流的墙钟，0 表示本轮从未取流
     * @param castStartMs 本轮投送起始墙钟（fetchMs=0 时的兜底基准）
     * @return 本轮算出的空窗毫秒，供 PAUSED 跳变处复用
     */
    fun sample(nowMs: Long, fetchMs: Long, castStartMs: Long, established: Boolean): Long {
        val sinceMs = if (fetchMs > 0L) fetchMs else castStartMs
        val idleMs = (nowMs - sinceMs).coerceAtLeast(0L)
        val step = idleMs / stepMs
        if (!established && step > lastStep) {
            lastStep = step
            logger("cast-fetch-idle ms=$idleMs established=false")
        }
        return idleMs
    }

    /**
     * 每帧传入渲染器是否处于 PAUSED：只在「刚进入」这一帧记一条，避免刷屏。
     * 跳变检测放在本类，是为了不让 `DlnaCastManager.pollOwnTransport` 再多一个
     * 分支（detekt CognitiveComplexMethod 阈值 30）。
     *
     * @param snapshot R7 现场快照（progress/duration + keepalive/doze + 请求级 trace）。
     *   刻意用 lambda 传：**只在新跳变那一帧求值**，1Hz 轮询的其余帧不读
     *   PowerManager、不拼字符串；空快照时日志格式与旧版一字不差。
     */
    fun onState(paused: Boolean, idleMs: Long, snapshot: () -> String = { "" }) {
        if (paused && !lastPaused) {
            val extra = snapshot().trim()
            logger("cast-fetch-idle ms=$idleMs state=PAUSED" + if (extra.isEmpty()) "" else " $extra")
        }
        lastPaused = paused
    }

    companion object {
        const val STEP_MS = 5_000L
    }
}
