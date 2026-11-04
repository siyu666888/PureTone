package com.puretone.player.playback.cast

/**
 * 投屏音量档位的纯规则（可纯 JVM 单测）。
 *
 * 真机上「音量忽高忽低」有三个来源，都在这里收敛成可验证的判定：
 * - 连按音量键若以滞后的状态值为基准，多次按键会被合并成同一个目标；
 * - 设备回读值（含量程取整误差）若反复覆盖 UI 初值，档位会逐次漂移；
 * - 能力判定若被单次回读反复改写，Media3 会在远程/本地两套档位间重建
 *   `VolumeProviderCompat`。
 */
internal object CastVolumePolicy {

    /** castVolume 的默认值，表示「本会话还没校准过」 */
    const val UNCALIBRATED = 100

    /** 相对调节：优先用最近一次已受理（可能还没下发）的请求值，连按才不丢档 */
    fun resolveRelative(pending: Int?, fallback: Int, delta: Int): Int =
        ((pending ?: fallback) + delta).coerceIn(0, 100)

    /** 只有尚未校准且用户没调过，才允许设备回读值写入 UI 初值 */
    fun shouldApplyInitialVolume(touchedByUser: Boolean, currentVolume: Int): Boolean =
        !touchedByUser && currentVolume == UNCALIBRATED

    /** 能力判定只写一次：已知就不再改写，避免 remoteVolume 翻转 */
    fun shouldSetSupport(current: Boolean?): Boolean = current == null
}
