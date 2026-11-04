package com.puretone.player.playback

import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.puretone.player.data.settings.PureToneSettings
import com.puretone.player.effect.AudioEffectController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * 设置 → 播放引擎 / 音效 的映射（P1-1 拆分收尾第十刀）。
 *
 * 倍速 / 无缝播放 / 播放顺序 / 随机 / 音效 / ReplayGain 全部在此落到引擎与 UI 镜像。
 * 主类只通过订阅设置流调用 [apply]，不再持有这段映射逻辑——它依赖的 `lastSettings` /
 * `gaplessEnabled` 仍以回调回写，因为这两份快照还被交叉淡化协调器与 ReplayGain 刷新读取。
 */
internal class PlaybackSettingsApplier(
    private val uiState: MutableStateFlow<PlaybackUiState>,
    private val playerProvider: () -> ExoPlayer?,
    private val controller: PlaybackController,
    private val sleepTimer: SleepTimerController,
    private val audioEffectController: AudioEffectController,
    private val applyReplayGainForCurrent: () -> Unit,
    private val setLastSettings: (PureToneSettings) -> Unit,
    private val setGaplessEnabled: (Boolean) -> Unit
) {
    /** 将用户设置映射到播放引擎与音效（唯一入口） */
    fun apply(s: PureToneSettings) {
        setLastSettings(s)
        setGaplessEnabled(s.gapless)
        controller.setSpeed(s.playbackSpeed)
        applyPlayOrder(s)
        sleepTimer.applySettings(s)
        audioEffectController.applySettings(s)
        // ReplayGain：开关 / 模式 / preamp 变化时刷新当前曲目的增益
        applyReplayGainForCurrent()
    }

    /**
     * 播放顺序（顺序/随机/单曲）来自设置：既灌给播放器，也写回 uiState。
     *
     * 任何非 ALL/ONE 的持久化值（含历史遗留的 OFF 默认）统归并为 ALL——三态循环
     * （顺序→随机→单曲）不包含「停止」态，OFF 在 UI 被显示为「顺序播放」循环图标却
     * 「放完即停」，图标/状态/行为割裂。规整后 SEQUENTIAL 始终表示「列表循环」。
     */
    private fun applyPlayOrder(s: PureToneSettings) {
        val repeat = when (s.repeatMode) {
            PureToneSettings.REPEAT_ALL -> RepeatMode.ALL
            PureToneSettings.REPEAT_ONE -> RepeatMode.ONE
            else -> RepeatMode.ALL
        }
        if (repeat != uiState.value.repeatMode) {
            uiState.update { it.copy(repeatMode = repeat) }
        }
        if (s.shuffle != uiState.value.shuffle) {
            uiState.update { it.copy(shuffle = s.shuffle) }
        }
        playerProvider()?.let { p ->
            p.repeatMode = when (repeat) {
                RepeatMode.OFF -> Player.REPEAT_MODE_OFF
                RepeatMode.ALL -> Player.REPEAT_MODE_ALL
                RepeatMode.ONE -> Player.REPEAT_MODE_ONE
            }
            p.shuffleModeEnabled = s.shuffle
        }
    }
}
