package com.puretone.player.playback

import android.content.Context
import androidx.media3.exoplayer.ExoPlayer

/**
 * 音频焦点落到播放指令的编排层（交叉淡化双播放器专用）。
 *
 * 与 [CrossfadeCoordinator] / [InterTrackGap] 同一手法：Android 侧的申请/放弃与
 * 「焦点变化 → 命令」的翻译都在 [AudioFocusController]（纯函数 [FocusDecision] 可单测），
 * 本类只负责把命令作用到**当前主播放器**并回调宿主更新 UI / 存档。
 *
 * 独立成文件的实际原因：这段逻辑放在 [PlaybackManager] 里会把该文件顶过 1200 行门禁，
 * 而它本身与队列编排无关——焦点只关心「现在谁在播」。
 *
 * 三条不变式：
 * 1. 只动主播放器：淡化窗口内的音量归 [CrossfadeCoordinator] 的渐变独占；
 * 2. 交叉淡化进行中不 duck/unduck（否则会把淡化曲线打断成「音量跳一下」）；
 * 3. 只有在主播放器确实停了才放弃焦点——淡化收尾暂停的是已降级的旧主。
 */
internal class PlaybackAudioFocus(
    context: Context,
    private val playerProvider: () -> ExoPlayer?,
    private val isCrossfading: () -> Boolean,
    /** 宿主的播放态刷新（焦点丢失时暂停，UI/小部件要跟着变） */
    private val onPlayingFlagChanged: (Boolean) -> Unit,
    /** 宿主的会话存档（焦点丢失同样要落盘，否则冷启动会把「已被打断」恢复成正在播） */
    private val onSessionChanged: () -> Unit
) {

    private val controller = AudioFocusController(context, ::handle)

    /** 已获得焦点则直接返回 true；未获得会立刻发一条暂停命令 */
    fun request(): Boolean = controller.request()

    /** 放弃焦点（暂停 / 停止 / 服务解绑） */
    fun abandon() = controller.abandon()

    /**
     * 播放态变化入口：跟着「真的在发声」申请/放弃焦点。
     *
     * 所有起播路径（点歌、续播、曲间停顿续播、焦点恢复）都经过 [PlaybackManager.onIsPlayingChanged]，
     * 因此不必在每个命令里各申请一次。
     */
    fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) {
            controller.request()
            return
        }
        // 交叉淡化收尾会暂停旧主（此刻它已降级为备援，主播放器仍在播）：
        // 那种情形必须继续持有焦点，否则下一次来电就管不住正在播的这一路。
        if (playerProvider()?.isPlaying != true) controller.abandon()
    }

    private fun handle(command: AudioFocusController.Command) {
        val p = playerProvider() ?: return
        when (command) {
            AudioFocusController.Command.PAUSE -> {
                PlaybackEventLog.record(
                    "audio-focus-pause",
                    "crossfading=${isCrossfading()} idx=${p.currentMediaItemIndex}"
                )
                p.pause()
                onPlayingFlagChanged(false)
                onSessionChanged()
            }

            AudioFocusController.Command.RESUME -> {
                if (isCrossfading() || p.mediaItemCount == 0) return
                p.volume = 1f
                p.play()
            }

            AudioFocusController.Command.DUCK -> {
                if (!isCrossfading()) p.volume = DUCK_VOLUME
            }

            AudioFocusController.Command.UNDUCK -> {
                if (!isCrossfading()) p.volume = 1f
            }
        }
    }

    private companion object {
        /** 被 duck 时保留的音量：导航播报期间压低继续播，而不是直接静音 */
        const val DUCK_VOLUME = 0.25f
    }
}
