package com.puretone.player.playback

import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 曲间停顿控制器（关闭「无缝播放」时的自动续播）。
 *
 * ## 背景：这里曾经是一个「永久暂停」缺陷
 *
 * 关闭无缝播放的用户诉求是「每首歌之间留一点空白」。旧实现在自动切歌回调里
 * 只写了 `player.pause()`，**没有任何续播**——于是每播完一首就永久停住，
 * 用户看到的现象就是「播着播着自动停了」。因为只在用户主动关掉无缝播放时命中，
 * 复现门槛不固定，听起来像「有时候会」。（见 git 历史 v1.2.0 起的实现。）
 *
 * 正确语义是「暂停 → 短延时 → 继续」，且必须能被打断：用户在这段延时里按了
 * 暂停/停止，就不能再自己响起来。
 *
 * ## 为什么独立成类
 *
 * 这段逻辑是纯时序状态机（等待标记 + 延时任务 + 三重放行条件），与播放编排的
 * 其余部分没有耦合；单拎出来后 [PlaybackManager] 不必再为它持有两个可变字段，
 * 也顺带把该文件的体量压回行数门禁之内。操作面收窄成 [GapPlayer] 后，
 * 「暂停后到底有没有续播」这件事可以在纯 JVM 上锁死（见 InterTrackGapTest）。
 */
internal class InterTrackGap(
    private val scope: CoroutineScope,
    private val playerProvider: () -> GapPlayer?,
    private val gapMs: Long = DEFAULT_GAP_MS
) : GapPort {

    private var job: Job? = null

    /** 是否仍打算在此次停顿结束后自动续播（用户命令会把这里置 false） */
    private var pending: Boolean = false

    /** 是否正处于停顿等待中（测试可读） */
    val isPending: Boolean get() = pending

    /** 在自动切歌边界开始一次停顿：立刻暂停，[gapMs] 后自动继续。 */
    override fun start() {
        val target = playerProvider() ?: return
        job?.cancel()
        pending = true
        target.pause()
        PlaybackEventLog.record("inter-track-gap", "idx=${target.currentIndex}")
        job = scope.launch {
            delay(gapMs)
            job = null
            if (!pending) return@launch
            pending = false
            // 三重放行条件：播放器没被换掉、没有被别人播起来、队列还在且没播到末尾。
            // 任一不满足都说明「现在不该由我起播」——宁可不响，也不要把用户按下的
            // 暂停或已经换掉的实例覆盖掉。
            if (playerProvider()?.token !== target.token) return@launch
            if (target.playWhenReady) return@launch
            if (target.mediaItemCount == 0 || target.playbackState == Player.STATE_ENDED) return@launch
            target.play()
            PlaybackEventLog.record("inter-track-gap-resume", "idx=${target.currentIndex}")
        }
    }

    /** 作废在途停顿：只取消「稍后自动续播」的意图，不改变当前播放态。 */
    fun cancel() {
        pending = false
        job?.cancel()
        job = null
    }

    companion object {
        /** 曲间留白时长：足够听出「一首结束了」，又不至于让人觉得卡住 */
        const val DEFAULT_GAP_MS = 1_200L
    }
}

/**
 * 曲间停顿需要的最小播放器操作面。
 *
 * 收窄的理由与 [CrossfadePlayer] 相同：[ExoPlayer] 是 final、项目无 mock 框架，
 * 不窄化就无法在 JVM 上验证「暂停后有没有续播」这个曾经出错的时序。
 */
internal interface GapPlayer {
    /** 实例身份令牌：延时期间若播放器被换掉（服务重建），不能再对这个旧目标起播 */
    val token: Any

    val playWhenReady: Boolean

    val mediaItemCount: Int

    val playbackState: Int

    val currentIndex: Int

    fun pause()

    fun play()
}

/** [GapPlayer] 的真身实现 */
internal class ExoGapPlayer(private val player: ExoPlayer) : GapPlayer {
    override val token: Any get() = player
    override val playWhenReady: Boolean get() = player.playWhenReady
    override val mediaItemCount: Int get() = player.mediaItemCount
    override val playbackState: Int get() = player.playbackState
    override val currentIndex: Int get() = player.currentMediaItemIndex
    override fun pause() = player.pause()
    override fun play() = player.play()
}
