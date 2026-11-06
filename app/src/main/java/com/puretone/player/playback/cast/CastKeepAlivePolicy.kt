package com.puretone.player.playback.cast

/**
 * 划掉任务卡时是否要停掉播放前台服务（纯函数，可单测）。
 *
 * 本地播放的原判据是「本地播放器没在播 或 队列为空 → stopSelf()」，但投屏期
 * 本地 ExoPlayer **恒暂停**，于是「投屏正在电视上放歌」会被判成空闲并停掉服务，
 * `PlaybackManager.unbind()` 随即取消投屏状态订阅与曲末推进协程。
 *
 * 更要命的是 PureTone 的 DLNA 是 **push + 手机做 HTTP 媒体源**：电视按
 * `http://手机IP:端口/...` 拉流，取流服务跑在 App 进程里。进程一停，电视断流
 * 直接 STOPPED，手机也再没有人轮询它的状态。所以投屏会话期间前台服务绝不能
 * 因「本地看起来没在播」而退出。
 */
internal object CastKeepAlivePolicy {

    /**
     * @param isCasting 当前是否处于投屏会话
     * @param playWhenReady 本地 ExoPlayer 是否在播（投屏期恒 false）
     * @param hasMediaItems 本地队列是否有曲目
     */
    fun shouldStopServiceOnTaskRemoved(
        isCasting: Boolean,
        playWhenReady: Boolean,
        hasMediaItems: Boolean
    ): Boolean = !isCasting && (!playWhenReady || !hasMediaItems)

    /**
     * 临时借用方（设备发现 / 能力探针）归还时，是否真的释放网络绑定与保活锁。
     *
     * 2026-11-06 06:56 真机：设备发现 `ownsNetwork=true`，用户在搜索尚未结束时
     * 点了设备投屏，随后关弹窗使搜索协程被取消，`finally` 无条件释放——把刚被
     * 投屏会话接管的锁一起清掉。那一轮的 `cast-keepalive=released` 甚至早于
     * `cast-ok`，于是 11 分钟会话全程裸奔，息屏进 Doze 后 1Hz 轮询被冻结，
     * 连续 3 次 null 被判成「设备已断开」。会话一旦接管，借用方的归还必须是无操作。
     *
     * @param owned 借用方本次是否真正建立了绑定（`holdCastNetwork()` 的返回值）
     * @param sessionActive 投屏会话是否已通过 `beginCastSession()` 接管
     */
    fun shouldReleaseOnBorrowerReturn(owned: Boolean, sessionActive: Boolean): Boolean =
        owned && !sessionActive
}
