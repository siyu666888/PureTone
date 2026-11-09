package com.puretone.player.playback.cast

/**
 * 请求级取流取证（R7：息屏期自发 `PAUSED_PLAYBACK` 停顿）。
 *
 * ## 为什么不能只看 `http-fetch`
 *
 * [CastHttpServer.logRequest] 每轮最多落 8 条，且写在响应 `copy()` **之后**——
 * 时间戳是「响应写完」不是「请求到达」。FLAC 长连接一次拉完几十 MB 后，
 * 几十秒没有新请求属正常；`cast-fetch-idle ms=` 也只是「距最近一次请求的空窗」，
 * **不能当供流中断时长**（见 `191939` 报告的判读教训）。
 *
 * 本类维护一份**不受日志条数上限影响**的轻量取样：最近 [CAPACITY] 次请求的
 * 到达墙钟、累计请求数、累计写出字节与最近一次写失败原因。PAUSED 跳变时
 * 由 [CastFetchIdleLog] 把 [snapshot] 打进同一行，用来区分：
 * - 停顿前电视刚来过请求（`ages` 里有小值）→ 电视侧缓冲/解码抖动的可能更大；
 * - 停顿前后都长时间零请求且 `err` 非空 → 手机侧供流中断需要继续查。
 *
 * 只记数字与异常类名，不记曲名/路径/内容；3 个 HTTP worker 并发调用，故全程加锁。
 * 换曲（[CastHttpServer.urlFor]）与结束会话（[CastHttpServer.stop]）都会复位，
 * 避免上一首的请求串进新曲的判读。
 */
internal class CastFetchTrace(private val capacity: Int = CAPACITY) {

    private val lock = Any()
    private val recentMs = ArrayDeque<Long>()
    private var totalRequests = 0L
    private var totalBytes = 0L
    private var lastError: String? = null

    /** 请求**到达**时调用（在发响应 / copy 之前），不是响应写完时 */
    fun noteRequest(nowMs: Long) {
        synchronized(lock) {
            totalRequests++
            recentMs.addLast(nowMs)
            while (recentMs.size > capacity) recentMs.removeFirst()
        }
    }

    /** 一次响应实际写出的字节；[CastHttpServer.copy] 在 `runCatching` 之后调用 */
    fun noteWritten(bytes: Long) {
        if (bytes <= 0L) return
        synchronized(lock) { totalBytes += bytes }
    }

    /** 写失败原因（异常类名 + message），截断避免异常文本刷爆诊断 */
    fun noteError(reason: String) {
        synchronized(lock) { lastError = reason.take(MAX_ERROR_CHARS) }
    }

    /** 新一轮投送 / 结束会话：全部归零 */
    fun reset() {
        synchronized(lock) {
            recentMs.clear()
            totalRequests = 0L
            totalBytes = 0L
            lastError = null
        }
    }

    /**
     * 生成一行快照。
     *
     * `ages` 是最近若干次请求距 [nowMs] 的毫秒差（由近到远），
     * 比绝对时间戳更好判读「停顿那一帧电视有没有刚来拉流」。
     * 时钟回拨按 0 处理，不产生负数。
     */
    fun snapshot(nowMs: Long): String = synchronized(lock) {
        // 由近到远：第一个数字就是「距最近一次请求多久」，停顿判读时最直观
        val ages = recentMs.reversed().joinToString(",") { (nowMs - it).coerceAtLeast(0L).toString() }
        "requests=$totalRequests bytes=$totalBytes ages=[$ages] err=${lastError ?: "-"}"
    }

    companion object {
        const val CAPACITY = 5
        private const val MAX_ERROR_CHARS = 120
    }
}
