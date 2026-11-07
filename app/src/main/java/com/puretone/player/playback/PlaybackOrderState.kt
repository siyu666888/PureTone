package com.puretone.player.playback

import androidx.media3.common.Player

/**
 * 播放顺序设置的纯映射（可单测），供 [PlaybackSettingsApplier] 与换主重放共用。
 *
 * 单独抽出来的原因：交叉淡化的两个 ExoPlayer 实例各自持有自己的 `repeatMode` /
 * `shuffleModeEnabled` / `playbackParameters`，而设置流只在 bind 后发射一次、
 * 只作用于当时的主播放器。**角色交换不是「设置」，不会触发设置流**——若不显式重放，
 * 两个实例会逐首交替保持 ALL / OFF，队列走到末尾且主恰好为 OFF 时 Media3 直接
 * 进入 STATE_ENDED，界面停成暂停态（2026-11-07 07:38 现场）。
 *
 * 映射必须与 [PlaybackController.setRepeatMode] 保持一致；倍速边界与
 * [PlaybackController.setSpeed] 一致。
 */
internal object PlaybackOrderState {

    /** UI 三态 → Media3 repeatMode */
    fun playerRepeatModeOf(mode: RepeatMode): Int = when (mode) {
        RepeatMode.OFF -> Player.REPEAT_MODE_OFF
        RepeatMode.ALL -> Player.REPEAT_MODE_ALL
        RepeatMode.ONE -> Player.REPEAT_MODE_ONE
    }

    /** 倍速钳制：与设置入口同一边界，防止异常持久化值灌给引擎 */
    fun normalizedSpeed(speed: Float): Float = speed.coerceIn(0.25f, 3.0f)
}
