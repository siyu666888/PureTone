package com.puretone.player.playback

/**
 * 「播放结束」事件是否属于不该发生的收尾（纯判据，可单测）。
 *
 * 循环模式下队列非空、用户也没暂停（playWhenReady 仍为 true）、且不在淡化收尾中，
 * 播放器却进了 STATE_ENDED，只可能是引擎侧的播放顺序/随机状态与 UI 镜像脱节
 * （典型：交叉淡化换主后新主仍是 REPEAT_MODE_OFF）。此时界面会停成「暂停」态，
 * 又因为 playWhenReady 恒 true，`paused-with-queue` 不会写——旧诊断里这正是取证盲区。
 *
 * 只用于**打一条日志**，不参与任何播放控制；真正的修复在换主重放
 * [PlaybackSettingsApplier.applyOrderTo]。
 */
internal object PlaybackEndDiagnostics {

    fun isUnexpected(
        repeatMode: RepeatMode,
        queueSize: Int,
        playWhenReady: Boolean,
        crossfading: Boolean
    ): Boolean = repeatMode != RepeatMode.OFF && queueSize > 0 && playWhenReady && !crossfading
}
