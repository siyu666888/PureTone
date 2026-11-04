package com.puretone.player.playback

import androidx.media3.common.DeviceInfo
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

    /** 投屏音量是否跟随手机音量键（设置项，实时读取） */
    val castVolumeFollowPhone: Boolean

    /** 设置渲染器输出音量（0~100 百分比） */
    fun setCastVolume(volume: Int)

    /** 相对调节音量：以管理器里「最近一次请求值」为基准，连按音量键不丢档 */
    fun adjustCastVolume(delta: Int)

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
 * ## 音量（跟随手机音量键）
 *
 * 投屏时音频**完全不经手机的输出链路**（本地播放器暂停、流由电视通过 HTTP 拉取），
 * 于是手机音量键原本是「空转」的。开启设置项后把会话声明为 `PLAYBACK_TYPE_REMOTE`：
 * Media3 会据此建立 `VolumeProviderCompat`，把音量键 / 系统音量条 / 线控的命令
 * 转成 [setDeviceVolume] 回调，再由本类转发给 DLNA `RenderingControl`。
 * 起点用 [CastState.castVolume]（投屏建立时已回读电视真实音量），所以是「接着调」
 * 而不是从假 100% 突然跳下去。
 *
 * [remoteVolume] **刻意不依赖** `volumeSupported`：Media3 只在 `setPlayer` 时读一次
 * `getDeviceInfo()` 来建立 `VolumeProviderCompat`，而能力探测是异步的、且旧实现会被
 * 单次回读反复改写。一旦判定在投屏中途翻转，音量键就会在「远程档位」与「本地媒体
 * 档位」之间来回跳——正是「忽高忽低」的成因。设备确实不支持时由 sink 拦住下发，
 * UI 仍显示「请用遥控器」。
 */
@UnstableApi
@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
internal class CastPlaybackPlayer(
    local: Player,
    private val sink: CastCommandSink
) : ForwardingPlayer(local) {

    /** 是否处于投屏期。每次都实时读——投屏可在任意时刻开始/结束。 */
    private val casting: Boolean get() = sink.castState.isCasting

    /** 音量键是否应交给渲染器：只看「投屏中 + 设置开启」，不随异步能力探测翻转 */
    private val remoteVolume: Boolean
        get() = casting && sink.castVolumeFollowPhone

    /** 静音前的档位：取消静音时恢复，避免一直停在 0 */
    private var preMuteVolume: Int? = null

    private companion object {
        /** 相对调节（音量键 +/- 一档）对应的百分比步长 */
        const val VOLUME_STEP = 5
        /** 没有静音记录时（例如会话中途重建）取消静音恢复到的档位 */
        const val DEFAULT_UNMUTE_VOLUME = 30
    }

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

    // ---------- 设备音量：投屏期映射到渲染器 ----------

    override fun getDeviceInfo(): DeviceInfo =
        if (remoteVolume) {
            DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE)
                .setMinVolume(0)
                .setMaxVolume(100)
                .build()
        } else {
            super.getDeviceInfo()
        }

    override fun getDeviceVolume(): Int =
        if (remoteVolume) sink.castState.castVolume.coerceIn(0, 100) else super.getDeviceVolume()

    override fun isDeviceMuted(): Boolean =
        if (remoteVolume) sink.castState.castVolume <= 0 else super.isDeviceMuted()

    override fun setDeviceVolume(volume: Int) {
        if (remoteVolume) sink.setCastVolume(volume.coerceIn(0, 100)) else super.setDeviceVolume(volume)
    }

    override fun setDeviceVolume(volume: Int, flags: Int) {
        if (remoteVolume) sink.setCastVolume(volume.coerceIn(0, 100))
        else super.setDeviceVolume(volume, flags)
    }

    override fun increaseDeviceVolume() {
        if (remoteVolume) sink.adjustCastVolume(VOLUME_STEP) else super.increaseDeviceVolume()
    }

    override fun decreaseDeviceVolume() {
        if (remoteVolume) sink.adjustCastVolume(-VOLUME_STEP) else super.decreaseDeviceVolume()
    }

    override fun increaseDeviceVolume(flags: Int) {
        if (remoteVolume) sink.adjustCastVolume(VOLUME_STEP) else super.increaseDeviceVolume(flags)
    }

    override fun decreaseDeviceVolume(flags: Int) {
        if (remoteVolume) sink.adjustCastVolume(-VOLUME_STEP) else super.decreaseDeviceVolume(flags)
    }

    override fun setDeviceMuted(muted: Boolean) {
        if (!remoteVolume) {
            super.setDeviceMuted(muted)
            return
        }
        if (muted) {
            if (preMuteVolume == null) preMuteVolume = sink.castState.castVolume
            sink.setCastVolume(0)
        } else {
            sink.setCastVolume(preMuteVolume ?: DEFAULT_UNMUTE_VOLUME)
            preMuteVolume = null
        }
    }
}
