package com.puretone.player.playback

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.puretone.player.playback.cast.CastState

/**
 * 投屏命令的落地通道（由 [PlaybackManager] 实现）。
 *
 * 门面本身不知道「怎么投屏」，只知道「该把命令交给谁」——这样它可被替换为任意实现，
 * 也让路由判断与 DLNA 调用分离。
 */
internal interface CastCommandSink {
    /** 当前投屏状态。必须实时读取（内部是 1Hz 轮询刷新的 StateFlow），不可缓存。 */
    val castState: CastState

    fun playOnRenderer()
    fun pauseOnRenderer()
    fun seekOnRenderer(positionMs: Long)
    fun skipToIndex(index: Int)
    fun skipToNext()
    fun skipToPrevious()
}

/**
 * 投屏态的 MediaSession 门面（M1 收尾）。
 *
 * ## 它解决什么问题
 *
 * `MediaSession` 直接把播放/暂停/切歌命令下发给它持有的 player。投屏时本地 ExoPlayer
 * 是**暂停**的（避免手机与电视同时出声），于是通知栏/锁屏按下去操作的还是本地播放器——
 * 用户感知就是「投屏中按通知栏没反应」。
 *
 * 本类在投屏期间接管 MediaSession 的 player 位置：命令转发给 DLNA 渲染器，状态取自
 * [CastState]；非投屏期全部原样转给本地播放器。
 *
 * ## 为什么用 ForwardingPlayer 而不是完整实现 Player
 *
 * 只重写「命令」与「会误导 UI 的状态」两类方法，其余（队列、元数据、播放模式、
 * availableCommands…）一律转发本地播放器。这样通知栏显示的曲名/歌手/封面、队列窗口、
 * 播放模式都与本地**天然一致**，不需要自己维护一份影子状态——那正是最容易写歪的地方。
 *
 * ## 状态来源
 *
 * - 播放/暂停态、进度、时长：投屏期来自 [CastState]（由 `DlnaCastManager` 1Hz 轮询
 *   渲染器填充），因此通知栏进度条会走；
 * - 队列与元数据：始终来自本地播放器（本地与投屏是同一份队列）；
 * - playbackState 投屏期固定为 [Player.STATE_READY]：渲染器已收到媒体，是可控状态。
 *
 * **不接管**：音量、播放模式等仍走本地语义（渲染器音量由 DLNA 侧独立控制）。
 */
@UnstableApi
internal class CastPlaybackPlayer(
    local: Player,
    private val sink: CastCommandSink
) : ForwardingPlayer(local) {

    /** 是否处于投屏期。每次都实时读——投屏可在任意时刻开始/结束。 */
    private val casting: Boolean get() = sink.castState.isCasting

    // ---------- 命令：投屏期转发渲染器 ----------

    override fun play() {
        if (casting) sink.playOnRenderer() else super.play()
    }

    override fun pause() {
        if (casting) sink.pauseOnRenderer() else super.pause()
    }

    override fun seekTo(positionMs: Long) {
        if (casting) sink.seekOnRenderer(positionMs.coerceAtLeast(0L))
        else super.seekTo(positionMs)
    }

    /**
     * 带索引的 seek：跨曲目时是「切歌」而非「拖进度」——必须重新投送新曲，
     * 否则渲染器会继续播旧的那首，而通知栏显示的是另一首。
     */
    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        if (casting) {
            if (mediaItemIndex == currentMediaItemIndex) sink.seekOnRenderer(positionMs.coerceAtLeast(0L))
            else sink.skipToIndex(mediaItemIndex)
        } else {
            super.seekTo(mediaItemIndex, positionMs)
        }
    }

    override fun seekToNextMediaItem() {
        if (casting) sink.skipToNext() else super.seekToNextMediaItem()
    }

    override fun seekToNext() {
        if (casting) sink.skipToNext() else super.seekToNext()
    }

    override fun seekToPreviousMediaItem() {
        if (casting) sink.skipToPrevious() else super.seekToPreviousMediaItem()
    }

    override fun seekToPrevious() {
        if (casting) sink.skipToPrevious() else super.seekToPrevious()
    }

    // ---------- 状态：投屏期取自渲染器 ----------

    override fun getPlayWhenReady(): Boolean =
        if (casting) sink.castState.isPlaying else super.getPlayWhenReady()

    override fun isPlaying(): Boolean =
        if (casting) sink.castState.isPlaying else super.isPlaying()

    override fun getPlaybackState(): Int =
        if (casting) Player.STATE_READY else super.getPlaybackState()

    override fun getCurrentPosition(): Long =
        if (casting) sink.castState.progressMs.coerceAtLeast(0L) else super.getCurrentPosition()

    override fun getDuration(): Long =
        if (casting) {
            val remote = sink.castState.durationMs
            // 渲染器上报的时长可能短暂为 0（刚投出去还没拿到），退回本地已知时长
            if (remote > 0L) remote else super.getDuration()
        } else {
            super.getDuration()
        }
}
