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

    /**
     * 投屏期**对外展示**的进度与时长。
     *
     * 不要直接用 [CastState.progressMs] / [CastState.durationMs]：那是 1Hz 原始轮询值，
     * 既没有两次轮询之间的外推，也不含拖动后的乐观窗口；而不少渲染器只回 RelTime、
     * 不回 TrackDuration（真机 `GetPositionInfo` 为 `185000/0`），直接拿它当总时长会把
     * 进度条整个禁用。这里由宿主提供已经「镜像 + 外推 + 本地时长兜底」过的一份，
     * 播放页、投屏遥控页、通知栏共用，才不会出现三处进度各不相同。
     */
    val displayPositionMs: Long
    val displayDurationMs: Long

    /**
     * 投屏期对外展示的播放/暂停态。
     *
     * 必须取 uiState 那一份「命令即时写入 + 稳定化轮询镜像」的值，不能直读
     * [CastState.isPlaying]：后者比命令滞后 1~2 帧，渲染器偶发一帧 PAUSED 还会让
     * 通知栏/锁屏的媒体按键被 Media3 判成相反方向，用户点「播放」反而下发 Pause。
     */
    val displayIsPlaying: Boolean

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
 * - 播放/暂停态：投屏期来自 [CastCommandSink.displayIsPlaying]（uiState 的命令期望态
 *   叠加稳定化轮询镜像），与播放页 / 遥控页 / 迷你条同源；
 * - 进度 / 时长：投屏期来自 [CastCommandSink.displayPositionMs] 与
 *   [CastCommandSink.displayDurationMs]，即宿主已「镜像轮询 + 250ms 外推 + 本地时长兜底」
 *   的那一份，与播放页、投屏遥控页同源。直读 1Hz 的 [CastState.progressMs] 会让通知栏
 *   比电视慢一拍，拖动后还会被旧值拉回；渲染器不报 `TrackDuration` 时更会显示 0/0；
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

    /**
     * 投屏期「想播」恒为 true，**刻意不跟随渲染器瞬时 PAUSED**。
     *
     * Media3 1.6.0 的前台判据（反编译 `MediaNotificationManager.isAnySessionUserEngaged`
     * 确认）：对每个 session 读 `MediaController.getPlayWhenReady()`，为 true 且
     * `playbackState` 是 READY/BUFFERING 才判定「用户仍在用」→ `shouldRunInForeground()`
     * 为 true → 维持 FGS 与媒体通知。投屏门面的 `playbackState` 本来就固定 READY，
     * 所以 `playWhenReady` 就是唯一开关。
     *
     * 投屏是 push 模式：手机既是电视的 HTTP 音源，又是 1Hz 轮询遥控器，整个会话
     * 必须一直保持 FGS 等级。若这里跟着 `displayIsPlaying` 翻成 false（渲染器一帧
     * PAUSED_PLAYBACK、或用户短暂暂停），服务会被降级成普通后台进程，国产 ROM
     * 随即冻结进程：电视拉不到流 → 缓冲耗尽 → PAUSED/STOPPED（用户看到的
     * 「电视退出投屏」）；轮询协程整段停摆 → 曲末不推进、切歌不续投，直到用户
     * 切回前台才补投下一首。2026-11-06 20:08 真机诊断中四个保活锁全为 true，
     * 轮询仍静默 176747ms 且 failures=1，正是进程被冻结而非轮询持续失败。
     *
     * 播放/暂停的真实展示仍由 [isPlaying] 取 `displayIsPlaying`（已过命令宽限与
     * PAUSED 去抖），两个方法语义不同，不会把「投屏中」误显示成「正在播放」。
     */
    override fun getPlayWhenReady(): Boolean =
        if (casting) true else super.getPlayWhenReady()

    override fun isPlaying(): Boolean =
        if (casting) sink.displayIsPlaying else super.isPlaying()

    override fun getPlaybackState(): Int =
        if (casting) Player.STATE_READY else super.getPlaybackState()

    override fun getCurrentPosition(): Long =
        if (casting) sink.displayPositionMs.coerceAtLeast(0L) else super.getCurrentPosition()

    override fun getDuration(): Long =
        if (casting) {
            // 与 App 内进度条同源：渲染器不报 TrackDuration 时由本地时长兜底，
            // 避免通知栏出现「进度 0 / 总时长 0」而与电视实际播放脱节。
            val shown = sink.displayDurationMs
            if (shown > 0L) shown else super.getDuration()
        } else {
            super.getDuration()
        }

    /**
     * 锁屏 / 通知栏：补齐 `content*` 与 `buffered*` 四个 getter。
     *
     * 只 override `getCurrentPosition()` / `getDuration()` 是不够的：Media3 1.6.0 的
     * `ForwardingPlayer` 把 `getContentPosition()` / `getContentDuration()` /
     * `getContentBufferedPosition()` / `getBufferedPosition()` 逐个**直接转发给被包装的
     * 本地 ExoPlayer**（`Player` 接口这几个方法没有 default 实现，不会被我们的
     * `getCurrentPosition()` 间接覆盖）。而投屏期本地播放器是暂停的，于是：
     * - `DefaultMediaNotificationProvider.getPlaybackStartTimeEpochMs()` 读
     *   `getContentPosition()` → 通知 `setWhen` / Chronometer 起点用的是本地旧位置；
     * - `PlayerWrapper.createPositionInfoForBundling()` 的 `contentPositionMs` 与
     *   `createSessionPositionInfoForBundling()` 的 `contentDurationMs` 落到本地值；
     * - `PlaybackStateCompat` 的 bufferedPosition 取本地缓冲位置。
     *
     * 结果就是「锁屏 / 通知栏媒体卡片的进度条与电视实际进度对不上」。本项目无广告、
     * 无动态媒体项，`contentPosition == currentPosition`、`contentDuration == duration`
     * 成立，直接委托给已取镜像值的两个 getter 即可；非投屏分支仍原样转发本地。
     */
    override fun getContentPosition(): Long =
        if (casting) getCurrentPosition() else super.getContentPosition()

    override fun getContentDuration(): Long =
        if (casting) getDuration() else super.getContentDuration()

    override fun getContentBufferedPosition(): Long =
        if (casting) getCurrentPosition() else super.getContentBufferedPosition()

    override fun getBufferedPosition(): Long =
        if (casting) getCurrentPosition() else super.getBufferedPosition()

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

    /**
     * 关键修复：投屏期显式把设备音量命令加入可用命令集。
     *
     * Media3 的 `MediaSession` 只在「设备信息为 REMOTE **且** 播放器可用命令含
     * `COMMAND_SET_DEVICE_VOLUME` / `COMMAND_ADJUST_DEVICE_VOLUME`」时，才会为远程设备
     * 建立 `VolumeProviderCompat`，把硬件音量键 / 系统音量条 / 线控转成
     * [setDeviceVolume] / [increaseDeviceVolume] 回调。
     *
     * 本类是 `ForwardingPlayer`，[getAvailableCommands] 直接转发底层本地 ExoPlayer——
     * 本地设备本就不宣称这两个命令（音量由系统管），于是 Media3 读到的可用命令里没有它们，
     * 远程音量通道**从不建立**，音量键空转、命令永远到不了 [CastCommandSink]。
     * 投屏期补上这两个命令，Media3 才会把音量键路由到渲染器。本地期原样转发，不受影响。
     */
    override fun getAvailableCommands(): Player.Commands {
        val base = super.getAvailableCommands()
        return if (remoteVolume) {
            Player.Commands.Builder()
                .addAll(base)
                .add(Player.COMMAND_SET_DEVICE_VOLUME)
                .add(Player.COMMAND_ADJUST_DEVICE_VOLUME)
                .build()
        } else {
            base
        }
    }

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
