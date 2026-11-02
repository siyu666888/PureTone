package com.puretone.player.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log

/**
 * 应用级音频焦点（交叉淡化双播放器专用）。
 *
 * ## 为什么不用 ExoPlayer 自带的 handleAudioFocus
 *
 * 交叉淡化是**双 ExoPlayer 实例真重叠**（[PlaybackService] 里的 player / playerB）。每个
 * ExoPlayerImpl 内部各有一个 AudioFocusManager，就是一个独立的焦点客户端，clientId 由
 * 各自的监听器对象派生（实测日志：
 * `requestAudioFocus ... AudioFocusManager$$ExternalSyntheticLambda0@d09a7bf` 与
 * `onAudioFocusChange(-1) → ...@b9d4ab0`）。于是备援在淡化起点 `play()` 申请 GAIN 时，
 * 系统按「新客户端抢占」把 **AUDIOFOCUS_LOSS 派给仍在正常播放的主播放器**：
 *
 * - 淡出方被当场暂停（音量还没降下来就被掐断，听感上是「咔嚓一下」，不是淡化）；
 * - 主播放器随后以「已暂停」进入收尾，把「自动暂停」的现场带进空闲停服判定。
 *
 * 角色交换还会让「谁持有焦点」永久错位：交换后新的主播放器是原本的备援，焦点状态却留在
 * 已降级的旧主身上——外部焦点事件（来电、拔耳机）从此不再作用于正在播的那个实例。
 *
 * 结论：焦点必须由**应用层单点**持有，与「哪个实例在播」解耦。两个播放器都用
 * `setAudioAttributes(attrs, handleAudioFocus = false)` 构建，本类负责申请/放弃并把
 * 焦点变化翻译成播放命令（[Command]），由宿主落到「当前主播放器」上。
 *
 * 决策逻辑抽到 [FocusDecision]（纯函数）：焦点回调是最容易写歪的地方——把「瞬态丢失后
 * 该不该自动恢复」写错，就会出现「接完电话音乐不再响」或「打游戏时插广告音乐又自己起来」。
 */
internal class AudioFocusController(
    context: Context,
    private val onCommand: (Command) -> Unit
) {

    /** 焦点变化翻译出的播放命令；宿主负责落到当前主播放器 */
    enum class Command {
        /** 暂停（焦点丢失 / 被其他应用抢占） */
        PAUSE,

        /** 恢复播放（瞬态丢失后重新拿到焦点） */
        RESUME,

        /** 压低音量（导航播报等可 duck 的场景） */
        DUCK,

        /** 恢复音量 */
        UNDUCK
    }

    private val audioManager: AudioManager? =
        context.applicationContext.getSystemService(AudioManager::class.java)

    /** 当前是否持有焦点（[abandon] 的幂等依据，也是「瞬态丢失后不该放弃」的判据） */
    private var holding: Boolean = false

    /** 是否因**瞬态**丢失而暂停（只有它才允许在拿到焦点时自动续播） */
    private var resumeOnGain: Boolean = false

    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        val next = FocusDecision.onChange(change, holding, resumeOnGain)
        holding = next.holding
        resumeOnGain = next.resumeOnGain
        next.command?.let(onCommand)
    }

    /** AudioFocusRequest 构建较重且可复用；minSdk 26，无需兼容旧 API */
    private val request: AudioFocusRequest by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            // 允许被 duck 而不是直接暂停：导航播报期间压低音量继续播，体验更接近系统播放器
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener(listener)
            .build()
    }

    /**
     * 申请焦点（已持有则直接返回 true）。
     *
     * @return 是否获得焦点。注意：未获得焦点时**不再**自行暂停——
     *         本地播放策略改为「不被其他软件打断」，宁可不持焦点也继续播；
     *         「焦点丢失是否暂停」统一交由 [PlaybackAudioFocus.handle] 在编排层决定。
     */
    fun request(): Boolean {
        if (holding) return true
        val am = audioManager ?: return false
        val result = runCatching { am.requestAudioFocus(request) }
            .onFailure { Log.w(TAG, "requestAudioFocus failed", it) }
            .getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        holding = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return holding
    }

    /**
     * 放弃焦点（暂停/停止时调用）。
     *
     * 焦点已丢失（holding=false）时直接返回：瞬态丢失期间放弃请求，就再也拿不回 GAIN，
     * 「来电挂断后继续播」会失效。
     */
    fun abandon() {
        if (!holding) return
        holding = false
        resumeOnGain = false
        runCatching { audioManager?.abandonAudioFocusRequest(request) }
    }

    private companion object {
        const val TAG = "AudioFocusController"
    }
}

/**
 * 焦点变化 → 命令与状态迁移（纯函数，便于单测）。
 *
 * 语义对齐 ExoPlayer 的 AudioFocusManager（同一套 Android 约定）：
 * - `LOSS`：永久丢失（其他应用开始放音乐、通话中）→ 暂停且**不**自动恢复；
 * - `LOSS_TRANSIENT`：临时丢失（来电、语音助手）→ 暂停，拿到 GAIN 后恢复；
 * - `LOSS_TRANSIENT_CAN_DUCK`：可压低音量（导航播报）→ 只 duck，不暂停；
 * - `GAIN`：拿回焦点 → 有恢复意图则继续播，否则仅恢复音量。
 */
internal object FocusDecision {

    /**
     * @param command 需要宿主执行的命令（null = 不改变播放态）
     * @param holding 迁移后的「是否持有焦点」
     * @param resumeOnGain 迁移后的「是否允许拿到焦点时自动续播」
     */
    data class Next(
        val command: AudioFocusController.Command?,
        val holding: Boolean,
        val resumeOnGain: Boolean
    )

    fun onChange(change: Int, holding: Boolean, resumeOnGain: Boolean): Next = when (change) {
        AudioManager.AUDIOFOCUS_LOSS ->
            Next(AudioFocusController.Command.PAUSE, holding = false, resumeOnGain = false)

        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ->
            Next(AudioFocusController.Command.PAUSE, holding = false, resumeOnGain = true)

        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
            Next(AudioFocusController.Command.DUCK, holding, resumeOnGain)

        AudioManager.AUDIOFOCUS_GAIN ->
            // 恢复意图只对「瞬态丢失」有效；消费掉，避免下一次 GAIN 又自己响起来
            Next(
                if (resumeOnGain) AudioFocusController.Command.RESUME
                else AudioFocusController.Command.UNDUCK,
                holding = true,
                resumeOnGain = false
            )

        else -> Next(null, holding, resumeOnGain)
    }
}
