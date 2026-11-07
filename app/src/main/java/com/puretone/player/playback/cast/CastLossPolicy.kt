package com.puretone.player.playback.cast

/**
 * 「换曲窗口」内是否抑制失联判定（2026-11-06 真机回归）。
 *
 * ## 背景
 *
 * 曲末 `STOPPED` → `cast-track-ended` → `recastCurrentIfCasting()` → `castSong()` 这一串里，
 * 渲染器正在执行 `SetAVTransportURI` + `Play` 换 URI：控制服务可能重启、短暂不应答，
 * 或先回报一句旧曲的 `STOPPED` / `ERROR`。此时**上一首的轮询协程还没停**
 * （`startPolling()` 只在 `onCastSucceeded()` 里调用），于是这些过渡帧会被当成
 * 「设备离线」：`markConnectionLost()` 一帧把 `isCasting` 写成 false，遥控页整页跳回
 * 普通播放页；随后在途投送成功、`onCastSucceeded()` 又把 `isCasting` 写回 true——
 * 真机表现就是「切歌时投屏断开，下一首开始播放后又连上」。
 *
 * 自有通道的 `pollOwnTransport()` 早已对「连续 null」做了 12s 换曲保护，但**库通道的
 * `PlaybackState.ERROR`、连续异常，以及自有通道的「最终复探」都不受保护**，三条路径
 * 都能直达 `markConnectionLost()`。这里把判据收成纯函数，在同一个出口统一拦截。
 *
 * 与 [CastPlayStatePolicy] / [CastProgressPolicy] / [CastQueuePolicy] 一样收成纯函数，
 * 便于纯 JVM 逐条锁死。
 */
internal object CastLossPolicy {

    /**
     * 距最近一次 `castSong()` 开始多久内不判失联。
     *
     * 与 `pollOwnTransport()` 原有的 null 计数保护同值（原来硬编码 12_000L），
     * 覆盖投送 + 首次取流 + 头几次 1Hz 轮询；超过它仍连不上就是真离线。
     */
    const val SWITCH_GRACE_MS = 12_000L

    /**
     * @param lastCastStartMs 最近一次 `castSong()` 开始时刻，0 表示本次会话还没投送过
     * @param nowMs 当前墙钟（由调用方传入，保持本函数纯）
     * @param graceMs 宽限时长，便于单测注入边界
     * @return true=仍处在换曲窗口，本次失联判定应忽略并让轮询继续
     */
    fun isWithinSwitchGrace(
        lastCastStartMs: Long,
        nowMs: Long,
        graceMs: Long = SWITCH_GRACE_MS
    ): Boolean {
        if (lastCastStartMs <= 0L) return false
        val since = nowMs - lastCastStartMs
        // 时钟回拨（since<0）不抑制：那是系统事件，不该把真实的失联吞掉
        return since >= 0L && since < graceMs
    }
}
