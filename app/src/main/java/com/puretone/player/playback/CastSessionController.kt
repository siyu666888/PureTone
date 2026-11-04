package com.puretone.player.playback

import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import com.puretone.player.data.local.entity.SongEntity
import com.puretone.player.playback.cast.CastDevice
import com.puretone.player.playback.cast.CastNetworkLog
import com.puretone.player.playback.cast.CastQueueSink
import com.puretone.player.playback.cast.CastResult
import com.puretone.player.playback.cast.CastResumePolicy
import com.puretone.player.playback.cast.CastState
import com.puretone.player.playback.cast.DlnaCastManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * 投屏会话控制器（P1-1 收尾抽取，对应原路线图的 PlaybackSessionController）。
 *
 * 把「投屏态」这一簇逻辑从 [PlaybackManager] 下沉到本类：
 * - 实现 [CastCommandSink]（门面回调：渲染器播放 / 暂停 / seek / 切歌）与 [CastQueueSink]
 *   （渲染器播完推进队列 / 失联回退本地），使 [CastPlaybackPlayer] 与
 *   `cast.CastQueueCoordinator` 的 sink 直接指向本类，[PlaybackManager] 不再 implements
 *   这两个接口；
 * - 持有投屏路由状态（castPlayer / routedToCast / castRoutingJob / routeSessionToCast），
 *   负责把 MediaSession 的 player 在「本地 ExoPlayer」与「投屏门面」之间切换；
 * - 提供 startCast / stopCast / fallbackToLocalPlayback 三个入口。
 *
 * recastCurrentIfCasting 与 warmUpFollowingApe 属 [PlaybackQueueSink]（被播放控制 / 队列编排
 * 回调），留在 [PlaybackManager] 上，其中重投失败路径经本类的 fallbackToLocalPlayback 回退本地。
 */
@OptIn(UnstableApi::class)
internal class CastSessionController(
    private val dlnaCast: DlnaCastManager,
    private val playbackFocus: PlaybackAudioFocus,
    private val uiState: MutableStateFlow<PlaybackUiState>,
    private val scope: CoroutineScope,
    private val playerProvider: () -> ExoPlayer?,
    private val sessionProvider: () -> MediaSession?,
    private val pause: () -> Unit,
    private val play: () -> Unit,
    private val next: () -> Unit,
    private val previous: () -> Unit,
    private val playAtIndex: (Int) -> Unit,
    private val seekTo: (Long) -> Unit,
    private val followPhoneVolume: () -> Boolean,
) : CastCommandSink, CastQueueSink {

    private companion object {
        const val TAG = "CastSessionController"
    }

    /** 投屏态的 MediaSession 门面。在 bind 时按当前 player 重建（player 换了就要换包装） */
    private var castPlayer: CastPlaybackPlayer? = null
    /** MediaSession 当前是否挂在门面上（避免每次状态刷新都重复 setPlayer） */
    private var routedToCast = false
    private var castRoutingJob: Job? = null
    /** 进度外推 ticker：在 1Hz 轮询之间用墙钟流逝推进手机进度，消除与电视的 1~2s 偏差 */
    private var progressTickerJob: Job? = null
    /** 进度外推锚点（轮询到的电视位置 + 当时墙钟），由两个协程共享读写 */
    private val anchorMs = AtomicLong(0L)
    private val anchorWallMs = AtomicLong(0L)

    /**
     * bind 时按当前 player 重建门面并订阅投屏状态；unbind 时彻底释放。
     * 门面必须包住**当前**这个 player，故每次 bind 重建。
     */
    fun bind(player: ExoPlayer) {
        castPlayer = CastPlaybackPlayer(player, this)
        castRoutingJob?.cancel()
        progressTickerJob?.cancel()
        // 进度外推锚点：每次 1Hz 轮询把电视当前位置记为锚点（毫秒）+ 当时墙钟，
        // 外推 ticker 在两次轮询之间按墙钟流逝线性推进手机进度，使其实时跟随电视。
        anchorMs.set(0L)
        anchorWallMs.set(0L)
        observeCastState()
        startProgressProjection()
    }

    /** 订阅投屏状态：镜像进度/时长进 uiState，并在跨越投屏边界时切换 MediaSession 的 player */
    private fun observeCastState() {
        castRoutingJob = scope.launch {
            dlnaCast.state.collect { st ->
                if (st.isCasting) {
                    // 投屏期本地 ExoPlayer 恒暂停，ticker 只在 isPlaying 时写 position，
                    // 于是 App 内进度条冻结在投屏那一刻（切歌后更是 00:00）。这里把
                    // 1Hz 轮询到的渲染器进度镜像进 uiState，播放页 / 歌词 / 迷你条
                    // 无需各自分支就一起跟随。非投屏期绝不镜像，避免状态清零冲掉本地位置。
                    // 拖动进度后留一段乐观窗口：渲染器执行 Seek 是异步的，头几次回读
                    // 仍是旧值，直接镜像会把进度条拉回去。
                    val mirror = st.hasProgressInfo &&
                        System.currentTimeMillis() >= dlnaCast.progressHoldUntilMs
                    if (mirror) {
                        anchorMs.set(st.progressMs.coerceAtLeast(0L))
                        anchorWallMs.set(System.currentTimeMillis())
                    }
                    uiState.update {
                        it.copy(
                            isPlaying = st.isPlaying,
                            positionMs = if (mirror) anchorMs.get() else it.positionMs,
                            durationMs = if (mirror && st.durationMs > 0L) st.durationMs else it.durationMs
                        )
                    }
                }
                routeSessionToCast(st.isCasting)
            }
        }
    }

    /**
     * 进度外推：电视进度由 1Hz 轮询得到，手机若只在轮询瞬间刷新会落后电视最多 1s。
     * 在轮询之间按「锚点 + 流逝墙钟」线性推进 positionMs，使手机进度条实时跟随电视，
     * 消除用户感知的 1~2s 偏差。暂停 / 拖动窗口（mirror=false）时跳过，避免越界或拉回。
     */
    private fun startProgressProjection() {
        progressTickerJob = scope.launch {
            while (true) {
                delay(250)
                val st = dlnaCast.state.value
                if (!st.isCasting || !st.isPlaying) continue
                val mirror = st.hasProgressInfo &&
                    System.currentTimeMillis() >= dlnaCast.progressHoldUntilMs
                if (!mirror) continue
                val elapsed = System.currentTimeMillis() - anchorWallMs.get()
                if (elapsed <= 0) continue
                val projected = (anchorMs.get() + elapsed)
                    .coerceAtMost(if (st.durationMs > 0L) st.durationMs else Long.MAX_VALUE)
                uiState.update { it.copy(positionMs = projected) }
            }
        }
    }

    /** 解绑：停掉路由订阅，避免对已死对象调 setPlayer */
    fun unbind() {
        castRoutingJob?.cancel()
        castRoutingJob = null
        progressTickerJob?.cancel()
        progressTickerJob = null
        castPlayer = null
        routedToCast = false
    }

    /**
     * 把 MediaSession 的 player 在「本地 ExoPlayer」与「投屏门面」之间切换。
     *
     * 只在跨越投屏边界时调用：频繁 setPlayer 会让 Media3 反复重建通知与内部控制器。
     * 切换失败（session 已释放等）只记日志——投屏是附加能力，不能因此崩掉播放链路。
     */
    private fun routeSessionToCast(toCast: Boolean) {
        if (toCast == routedToCast) return
        val target = if (toCast) castPlayer else playerProvider()
        val s = sessionProvider()
        if (target == null || s == null) return
        runCatching { s.setPlayer(target) }
            .onFailure { Log.w(TAG, "session.setPlayer failed (toCast=$toCast)", it) }
            .onSuccess { routedToCast = toCast }
    }

    // ---------- 投屏入口（PlaybackManager 转发） ----------

    /**
     * 开始投屏：暂停本地 → 选定设备 → 投送当前曲。
     *
     * 投屏失败（设备拒绝 / 网络异常 / APE 转码失败）时**恢复本地播放**——否则用户点了投屏
     * 却两头都没声音，且看不出发生了什么。「已过期」不恢复：那是期间用户自己停了投屏或
     * 改了目标，本地播放已由那条路径恢复，这里再拉一次会把新一轮投送也打断。
     */
    fun startCast(device: CastDevice, song: SongEntity) {
        scope.launch {
            // 投屏前先记下落地的本地播放位置（蓝牙 / 本地播放都可能已播到中段）。
            // 投出去后把渲染器 seek 到该位置，避免「从头开始播放」。
            val localPos = (playerProvider()?.currentPosition ?: uiState.value.positionMs)
                .coerceAtLeast(0L)
            val localDur = playerProvider()?.duration?.takeIf { it > 0L }
                ?: song.duration.takeIf { it > 0L } ?: 0L
            if (uiState.value.isPlaying) pause()
            // 投屏态释放手机侧音频焦点（声音走电视，焦点无关且不应占用）
            playbackFocus.abandon()
            dlnaCast.selectDevice(device)
            when (val result = dlnaCast.castSong(song)) {
                is CastResult.Success -> {
                    // 不是从开头投的，就把渲染器 seek 到本地暂停那一刻的位置。
                    // 等到渲染器进入 PLAYING 再 seek 更稳；加乐观窗口防止 1Hz 轮询
                    // 把进度条又拉回 0。
                    if (localPos > 1_500L && (localDur <= 0L || localPos < localDur)) {
                        delay(600)
                        dlnaCast.seek(localPos)
                        dlnaCast.noteSeekOptimistic()
                        CastNetworkLog.log("cast-resume-on-start seek=$localPos dur=$localDur")
                    }
                }
                // 首投失败 → 恢复本地播放，否则用户点了投屏却两头都没声音。
                // 「已过期」不恢复：那是期间用户自己停了投屏或改了目标，
                // 本地播放已由那条路径恢复，这里再拉一次会把新一轮投送也打断。
                is CastResult.Failed -> {
                    val st = dlnaCast.state.value
                    if (!st.isCasting && !st.isPreparing) play()
                }
                is CastResult.Superseded -> Unit
            }
        }
    }

    /**
     * 停止投屏：停渲染器 → MediaSession 切回本地 → 本地从**电视当前进度**续播。
     *
     * 无条件续播而非「若本地非播放才播」：投屏期本地必然是暂停的，停止投屏的意图就是
     * 回到手机上继续听。若按 uiState 判断会误判——投屏期 uiState.isPlaying 跟随的是
     * 渲染器，停止后它仍是 true，会被读成「正在播放」而不恢复，用户就既听不到电视也听不到手机。
     *
     * 快照必须在 [DlnaCastManager.stopCast] **之前**取：它最后一行整包清零 [CastState]，
     * 电视进度随之丢失。seek 也必须排在 stopCast 之后，否则 `PlaybackController.seekTo`
     * 会把命令发去已经停掉的渲染器。
     */
    fun stopCast() {
        scope.launch {
            val snapshot = dlnaCast.state.value
            dlnaCast.stopCast()
            val localDuration = playerProvider()?.duration?.takeIf { it > 0L }
                ?: uiState.value.currentEntry?.durationMs?.takeIf { it > 0L }
                ?: snapshot.durationMs
            val target = CastResumePolicy.takeOverPositionMs(snapshot, localDuration)
            // 真机复测抓手：一眼看出是「接管电视进度」还是「设备没给进度、保持本地」
            CastNetworkLog.log(
                "cast-resume takeOver=${target ?: "keep-local"} " +
                    "tv=${snapshot.progressMs}/${snapshot.durationMs} " +
                    "progressKnown=${snapshot.hasProgressInfo} localDuration=$localDuration"
            )
            target?.let { seekTo(it) }
            play()
        }
    }

    /**
     * 投屏不可用时的统一回退：停投屏 → 本地续播 → 给出提示。
     *
     * 顺序不能颠倒：[DlnaCastManager.stopCast] 会把 [CastState] 整体重置（isCasting 归 false），
     * 只有在它之后调 [play]，本地播放才会真的起播——否则 [play] 会按「仍在投屏」
     * 分支去命令渲染器播放，而渲染器已经不可用了。
     */
    internal suspend fun fallbackToLocalPlayback(reason: String) {
        try {
            dlnaCast.stopCast()
        } catch (e: Exception) {
            Log.w(TAG, "stopCast during fallback failed", e)
        }
        uiState.update { it.copy(error = reason) }
        play()
    }

    // ---------- CastQueueSink：投屏队列推进（M3） ----------

    /**
     * 渲染器自然播完当前这一首 → 推进队列并重新投送下一首。
     *
     * 走 [next] 而非自己推进索引，是为了**复用本地播放模式**（循环 / 随机）——
     * 投屏的队列推进与本地切歌必须是同一套语义，否则会出现「本地按顺序、
     * 投屏按另一套」的分叉。
     *
     * [next] 内部已带重投（本地推进后调 recastCurrentIfCasting），故这里不再单独投送。
     */
    override fun onCastTrackEnded() {
        next()
    }

    /**
     * 投屏连接失效（设备离线 / 流错误）→ 停止投屏并把播放回退到本地。
     *
     * 与用户主动「停止投屏」走同一条路径：停渲染器 → MediaSession 切回本地 →
     * 本地续播。区别只在于多给一条提示——用户是**被动**失去投屏的，
     * 不提示就会以为手机静音了。
     */
    override fun onCastConnectionLost(reason: String) {
        scope.launch { fallbackToLocalPlayback(reason) }
    }

    // ---------- CastCommandSink：门面回调 ----------

    override val castState: CastState
        get() = dlnaCast.state.value

    override val castVolumeFollowPhone: Boolean
        get() = followPhoneVolume()

    /** 手机音量键 / 系统音量条 → DLNA `SetVolume`（合并与回读由 DlnaCastManager 负责） */
    override fun setCastVolume(volume: Int) {
        // 设备没有 RenderingControl 时不下发：门面的 getDeviceInfo 刻意不依赖能力探测，
        // 拦在 sink 这一层既能稳住 Media3 的 VolumeProvider，又不会白按音量键。
        if (castState.volumeSupported == false) return
        dlnaCast.setVolume(volume)
    }

    override fun adjustCastVolume(delta: Int) {
        if (castState.volumeSupported == false) return
        dlnaCast.adjustVolume(delta)
    }

    override fun playOnRenderer() {
        scope.launch { dlnaCast.play() }
    }

    override fun pauseOnRenderer() {
        scope.launch { dlnaCast.pause() }
    }

    override fun seekOnRenderer(positionMs: Long) {
        scope.launch { dlnaCast.seek(positionMs) }
    }

    /** 门面收到的是「切到第 N 首」：走常规点播路径，末尾会自动重投 */
    override fun skipToIndex(index: Int) {
        playAtIndex(index)
    }

    override fun skipToNext() {
        next()
    }

    override fun skipToPrevious() {
        previous()
    }
}
