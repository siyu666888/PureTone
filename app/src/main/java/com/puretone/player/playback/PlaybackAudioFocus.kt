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
 *
 * ## 取证（2026-11-07 蓝牙后台「自动暂停」）
 *
 * 四个命令分支都写一行播放事件：命令、实际落地动作（含被跳过的原因）、主播放器
 * 状态快照与 [AudioRouteSnapshot] 的音频路由快照。日志只观察不干预——PAUSE 仍是
 * 跳过，RESUME / DUCK / UNDUCK 的跳过条件、音量赋值与 `play()` 调用全部保持原样。
 * 有了 `route=` 才能把「蓝牙 A2DP 断连」与「单纯的系统夺焦点」区分开。
 */
internal class PlaybackAudioFocus(
    context: Context,
    private val playerProvider: () -> ExoPlayer?,
    private val isCrossfading: () -> Boolean,
    /** 投屏态：声音在电视上，手机侧不参与焦点（问题2 修复） */
    private val isCasting: () -> Boolean
) : AudioFocusPort, CastSessionFocusPort {

    private val appContext = context.applicationContext
    private val controller = AudioFocusController(context, ::handle)

    /** 已获得焦点则直接返回 true；未获得会立刻发一条暂停命令 */
    fun request(): Boolean = controller.request()

    /** 放弃焦点（暂停 / 停止 / 服务解绑） */
    override fun abandon() = controller.abandon()

    /**
     * 播放态变化入口：跟着「真的在发声」申请/放弃焦点。
     *
     * 所有起播路径（点歌、续播、曲间停顿续播、焦点恢复）都经过 [PlaybackManager.onIsPlayingChanged]，
     * 因此不必在每个命令里各申请一次。
     */
    override fun onIsPlayingChanged(isPlaying: Boolean) {
        // 投屏态：声音在电视上，手机侧不应申请/持有焦点（否则占着焦点会让其他 App
        // 在手机上静音，或被其他 App 抢焦点误暂停本地播放器、进而经 CastPlaybackPlayer
        // 把电视上的投屏也停掉）。焦点由 startCast 在投屏开始时统一 abandon。
        if (isCasting()) return
        if (isPlaying) {
            controller.request()
            return
        }
        // 交叉淡化收尾会暂停旧主（此刻它已降级为备援，主播放器仍在播）：
        // 那种情形必须继续持有焦点，否则下一次来电就管不住正在播的这一路。
        if (playerProvider()?.isPlaying != true) controller.abandon()
    }

    private fun handle(command: AudioFocusController.Command) {
        // 投屏态：手机侧焦点事件一律忽略，投屏播放不受手机上其他软件干扰（问题2 修复）。
        if (isCasting()) {
            PlaybackEventLog.record("audio-focus-ignored", "reason=casting cmd=${command.name}")
            return
        }
        when (command) {
            // 本地播放：被其他软件抢占/丢失焦点时**不再暂停**——用户要求本地播放不被打断。
            // （导航播报等瞬态仍走 DUCK 压低音量，属于「非暂停」的软降级；若希望连音量
            //  变化也消除，把这一支也 early-return 即可。）
            AudioFocusController.Command.PAUSE -> {
                PlaybackEventLog.record(
                    "audio-focus-pause-skipped",
                    "reason=local-keep-playing ${snapshot()}"
                )
            }

            AudioFocusController.Command.RESUME -> {
                val p = playerProvider()
                val action = when {
                    p == null -> "no-player"
                    isCrossfading() -> "skipped-crossfading"
                    p.mediaItemCount == 0 -> "skipped-empty-queue"
                    else -> {
                        p.volume = 1f
                        p.play()
                        "play"
                    }
                }
                PlaybackEventLog.record("audio-focus-resume", "action=$action ${snapshot()}")
            }

            AudioFocusController.Command.DUCK -> {
                val p = playerProvider()
                val action = when {
                    p == null -> "no-player"
                    isCrossfading() -> "skipped-crossfading"
                    else -> {
                        p.volume = DUCK_VOLUME
                        "duck"
                    }
                }
                PlaybackEventLog.record("audio-focus-duck", "action=$action target=$DUCK_VOLUME ${snapshot()}")
            }

            AudioFocusController.Command.UNDUCK -> {
                val p = playerProvider()
                val action = when {
                    p == null -> "no-player"
                    isCrossfading() -> "skipped-crossfading"
                    else -> {
                        p.volume = 1f
                        "unduck"
                    }
                }
                PlaybackEventLog.record("audio-focus-unduck", "action=$action target=1.0 ${snapshot()}")
            }
        }
    }

    /** 只读快照：取设备信息失败也要照常落一行，日志永远不能影响播放。 */
    private fun snapshot(): String = buildString {
        val p = playerProvider()
        append("idx=${p?.currentMediaItemIndex ?: -1}")
        append(" vol=${p?.volume ?: -1f}")
        append(" pwr=${p?.playWhenReady}")
        append(" playing=${p?.isPlaying}")
        append(" state=${p?.playbackState ?: -1}")
        append(" cf=${isCrossfading()}")
        append(' ')
        append(AudioRouteSnapshot.compact(AudioRouteSnapshot.read(appContext)))
    }

    private companion object {
        /** 被 duck 时保留的音量：导航播报期间压低继续播，而不是直接静音 */
        const val DUCK_VOLUME = 0.25f
    }
}
