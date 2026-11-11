package com.puretone.player.playback.cast

import java.util.concurrent.ConcurrentHashMap

/**
 * P2-3 目标校验诊断的「一次性发射器」。
 *
 * ## 为什么需要一层中转
 *
 * [SoapClient] / [ControlUrls] 的判定结果必须在真机日志里可见，但有两个硬约束：
 *
 * 1. [CastNetworkLog.log] 首行就是 `Log.i`。`CastProbe.resolveControlUrl()` 与
 *    [SoapClient] 会被 JVM 单测直接调用，Android Log stub 会抛
 *    `RuntimeException("Method i in android.util.Log not mocked")`。所以生产调用侧
 *    也不能直接把 `CastNetworkLog::log` 交给这些路径——本类**默认 sink 是 noop**，
 *    由 [DlnaCastManager.init] 在 Application.onCreate 注入真实 sink。
 *    未注入时 `emit` 是纯 noop，JVM 单测可以无条件把 [emit] 传进纯函数。
 * 2. 1Hz 轮询每秒都会过一次 [SoapTargetPolicy]。域名 / IPv6 目标命中 `UNVERIFIED`
 *    时若每帧落一行，诊断文件会被刷爆（与 `own-state` 只记状态变化同理）。
 *    故按**诊断行全文**去重：verdict / host / reason 任一变化才会重新落一行。
 *
 * ## 边界
 *
 * 只负责「把已有的判定行送出去」，不参与任何判定、不解析 URL、不碰网络。
 * 去重集合有硬上限，避免异常设备上无限多种畸形 URL 撑爆内存；超限后新行静默丢弃，
 * 已在集合里的旧行仍可正常判重。生产侧重复 install 只替换 sink，不清空去重状态。
 */
internal object CastTargetDiagnostics {

    /** 去重集合上限：正常局域网设备数有限，64 条足够覆盖所有判定组合 */
    private const val MAX_KEYS = 64

    /** 判定行的判重键；并发轮询与发现协程会同时调用 */
    private val seen: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var sink: (String) -> Unit = {}

    /** Application.onCreate 注入一次真实落盘通道；测试里可换收集用 sink */
    fun install(target: (String) -> Unit) {
        sink = target
    }

    /**
     * 落一条目标校验诊断；同一行全文在进程内只落一次。
     * 未 [install] 时是纯 noop。
     */
    fun emit(line: String) {
        if (line.isEmpty()) return
        if (seen.size >= MAX_KEYS && !seen.contains(line)) return
        if (!seen.add(line)) return
        sink(line)
    }

    /** 仅供纯 JVM 单测：换 sink 并清空去重状态，避免测试间互相污染 */
    internal fun resetForTest(target: (String) -> Unit = {}) {
        seen.clear()
        sink = target
    }

    internal fun seenCountForTest(): Int = seen.size
}
