package com.puretone.player.playback.cast

/**
 * 投屏遥控页「连接中 / 进度未知」展示态的纯判据（2026-11-09 真机 170435）。
 *
 * ## 背景
 *
 * 170435：Knock 投送后电视一直在出声，手机遥控页却把进度条冻结在 00:00、胶囊
 * 显示「已投屏」，直到 15s 后 KICK 才一次性跳到约 15s。主因在 [CastEstablishPolicy]
 * 丢了早到的首次取流（已修），但 UI 还有第二层问题：未确认建立时编排层会停写
 * 播放态与进度外推，此时把尚未更新的 `positionMs=0` 显示成「00:00」，等于用
 * 一个假数值冒充「电视停在开头」。
 *
 * ## 判据
 *
 * 只有**本轮确实一次都没取流**（`lastFetchMs <= 0`）且尚未确认建立时，才把遥控页
 * 切成明确的「连接中」不确定态：胶囊 spinner + 「连接中…」，进度条禁用并显示
 * `--:-- / 进度未知`。一旦取流到达，[CastEstablishPolicy] 下一帧就会置
 * `isEstablished=true`，本态自动消失，进度回到正常的 uiState 镜像。
 *
 * 失联时走既有的橙色「设备失联」；库通道 `isEstablished` 直通 true；两者都不进本态。
 * 传输控制按钮**不受**本态影响：054751 真机正是靠用户手动点播放下发单独 Play 在
 * 1s 内救回，连接慢时禁用播放键会剥夺唯一的人工兜底。
 *
 * 收成纯函数是为了能在纯 JVM 单测里锁死，与 [CastProgressPolicy] /
 * [CastResumePolicy] 一致。
 */
internal object CastEstablishUiPolicy {

    /**
     * @param lastFetchMs 本轮最近一次取流墙钟（0 = 本轮从未取流）
     * @return true 表示遥控页应显示「连接中」而不是冻结的 00:00
     */
    fun awaitingEstablishment(
        isCasting: Boolean,
        isConnectionLost: Boolean,
        isEstablished: Boolean,
        lastFetchMs: Long
    ): Boolean = isCasting && !isConnectionLost && !isEstablished && lastFetchMs <= 0L
}
