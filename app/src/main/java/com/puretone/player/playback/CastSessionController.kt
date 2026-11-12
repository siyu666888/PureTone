package com.puretone.player.playback

import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import com.puretone.player.data.local.entity.SongEntity
import com.puretone.player.playback.cast.CastDevice
import com.puretone.player.playback.cast.CastNetworkLog
import com.puretone.player.playback.cast.CastProgressPolicy
import com.puretone.player.playback.cast.CastQueuePolicy
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
    private val dlnaCast: CastSessionCastPort,
    private val playbackFocus: CastSessionFocusPort,
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
    /** 曲中停止后重投**当前曲**（宿主侧的 recastCurrentIfCasting，含失败回退本地） */
    private val recastCurrent: () -> Unit,
) : CastCommandSink, CastQueueSink {

    private companion object {
        const val TAG = "CastSessionController"

        /**
         * 外推锚点的「新鲜」阈值（毫秒），超过它就把墙钟增量折进锚点继续外推。
         *
         * 锚点由 1Hz 轮询刷新，正常间隔约 1000ms。轮询静默（息屏 / Doze / 渲染器
         * 不应答）时锚点会变旧，但电视仍在播：此时**不能停写**，否则手机进度冻结、
         * 与电视越差越大。前移锚点可避免每帧重复累加同一次 elapsed，同时保持连续。
         * 停写窗口（暂停 / 拖动）结束时的重锚由 [startProgressProjection] 单独负责。
         */
        const val PROJECTION_ANCHOR_MAX_AGE_MS = 1_500L

        /** 曲中停止重投后，等渲染器进入 PLAYING 再补 seek（与 startCast 的续播同款时序） */
        const val RECAST_SEEK_DELAY_MS = 800L

        /**
         * 曲末 STOPPED → 换下一首 URI 之间的「终止态握手」。
         *
         * 真机 20261107_184914：18:45:37 `own-state STOPPED` 与 `cast-attempt` 同秒，
         * 电视还在为旧 URI 做收尾（关连接 / 重启控制服务），App 立刻 `SetAVTransportURI`
         * 把新曲顶上去，DLNA 播放器直接退出画面；新曲此后 9 秒都没来取流，只能靠
         * B3 的 8 秒宽限事后重发救回。这里先让终止态稳定下来再换 URI。
         */
        const val TRACK_END_HANDSHAKE_MS = 1_800L

        /**
         * 通知栏 Play 的回读确认延迟：必须大于命令宽限（2500ms），
         * 否则读到的仍是命令期望态而不是渲染器真实状态。
         */
        const val PLAY_CONFIRM_DELAY_MS = 3_000L

        /**
         * 曲中 STOPPED seek-recover 时，下发 play 后等待渲染器回报 PLAYING 的观察窗口。
         * 与 [RECAST_SEEK_DELAY_MS] 同量级；期间用**原始 playbackState** 判恢复（命令宽限
         * 只遮蔽 isPlaying，见 [com.puretone.player.playback.cast.CastPlayStatePolicy]）。
         */
        const val SEEK_RECOVER_OBSERVE_MS = 800L
    }

    /** 投屏态的 MediaSession 门面。在 bind 时按当前 player 重建（player 换了就要换包装） */
    private var castPlayer: CastPlaybackPlayer? = null
    /** MediaSession 当前是否挂在门面上（避免每次状态刷新都重复 setPlayer） */
    private var routedToCast = false
    private var castRoutingJob: Job? = null
    /** 进度外推 ticker：在 1Hz 轮询之间用墙钟流逝推进手机进度，消除与电视的 1~2s 偏差 */
    private var progressTickerJob: Job? = null
    /** 曲末换曲握手 job：同一时刻只保留一个，避免两次 STOPPED 观测连跳队列 */
    private var trackEndHandshakeJob: Job? = null
    /** 通知栏 Play 的回读确认 job：暂停 / 末首收尾 / 解绑时取消 */
    private var playConfirmJob: Job? = null
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
                    // 未确认建立（自有通道还没观测到电视真来取流）时不镜像播放态/进度：
                    // 真机 20261107_165555 里 cast-ok + PLAYING 却一条 http-fetch 都没有，
                    // 手机卡片靠外推显示 00:24，而电视画面早已退出。默认 true 的库通道
                    // 与所有历史状态不受影响。
                    val established = st.isEstablished
                    val mirror = established && st.hasProgressInfo &&
                        System.currentTimeMillis() >= dlnaCast.progressHoldUntilMs
                    if (mirror) {
                        anchorMs.set(st.progressMs.coerceAtLeast(0L))
                        anchorWallMs.set(System.currentTimeMillis())
                    }
                    uiState.update { current ->
                        // 渲染器只回 RelTime、不回 TrackDuration 是常态（真机 185000/0）。
                        // 总时长退到本地已知值；本地播放器若从未起播过（冷启动直接投屏），
                        // 再退到队列条目里 Room 记录的时长，否则进度条会整个没有刻度。
                        current.copy(
                            isPlaying = st.isPlaying && established,
                            positionMs = if (mirror) anchorMs.get() else current.positionMs,
                            durationMs = CastProgressPolicy.displayDurationMs(
                                rendererDurationMs = st.durationMs,
                                mirrorRenderer = mirror,
                                uiDurationMs = current.durationMs,
                                entryDurationMs = current.currentEntry?.durationMs
                            )
                        )
                    }
                }
                routeSessionToCast(st.isCasting)
            }
        }
    }

    /**
     * 进度外推：电视进度由 1Hz 轮询得到，手机若只在轮询瞬间刷新会落后电视最多 1s。
     * 在轮询之间按「锚点 + 流逝墙钟」线性推进 positionMs，使手机进度条实时跟随电视。
     *
     * 只保留「停写窗口」这一个停写条件（暂停 / 非投屏 / 拖动乐观窗口）。旧实现还要
     * 求 `hasProgressInfo` 且锚点不超过 1.5s，于是息屏 / Doze / 渲染器短时不应答时
     * （`own-poll-null quiet=126s`）轮询不发新帧、锚点迅速过期，手机进度就**冻结**在
     * 最后一次成功值，电视却继续播；结束投屏时 `tv=112000` 与已播约 155s 的差距正是
     * 这么来的。现在静默期按墙钟继续外推并钳到总时长，下一次成功轮询的镜像会把位置
     * 校正回电视真实值。
     *
     * 但「停写」必须配「恢复时重锚」：锚点墙钟在停写期间并未停止，若恢复后仍用
     * 「旧锚点 + 累积墙钟」外推，暂停 10 秒就会把进度条一次推到曲末。旧实现靠
     * `isAnchorFresh(1500ms)` 停写天然规避了这一点，移除该保护时必须显式补回来。
     */
    private fun startProgressProjection() {
        progressTickerJob = scope.launch {
            // 任何停写窗口（暂停 / 非投屏 / 拖动乐观窗口）结束后都要重新锚定，
            // 不能只记「拖动窗口」：锚点墙钟在暂停期间同样在走。
            // 初值 true 还覆盖 bind() 后的首帧：此时 anchorWallMs 刚被置 0，若
            // hasProgressInfo=false 没有轮询帧来重锚，elapsed 会是一个巨大值，
            // 第一帧就把进度条外推到总时长。
            var projectionPaused = true
            while (true) {
                delay(250)
                val st = dlnaCast.state.value
                if (!st.isCasting || !st.isPlaying || !st.isEstablished) {
                    projectionPaused = true
                    continue
                }
                val nowMs = System.currentTimeMillis()
                if (nowMs < dlnaCast.progressHoldUntilMs) {
                    projectionPaused = true
                    continue
                }
                if (projectionPaused) {
                    // 以当前显示位置重新锚定：旧锚点 + 累积墙钟会把整段停写时长一起
                    // 外推，进度条瞬间跳到没播到的地方（甚至钳到曲末），再被下一帧拉回。
                    anchorMs.set(uiState.value.positionMs)
                    anchorWallMs.set(nowMs)
                    projectionPaused = false
                }
                val cap = CastProgressPolicy.projectionCapMs(st.durationMs, uiState.value.durationMs)
                val elapsed = nowMs - anchorWallMs.get()
                val projected = CastProgressPolicy.projectedPositionMs(
                    anchorMs.get(), elapsed, cap
                ) ?: continue
                // 锚点过旧时把墙钟增量折进锚点，下一帧从新锚点继续，进度保持连续。
                if (!CastProgressPolicy.isAnchorFresh(elapsed, PROJECTION_ANCHOR_MAX_AGE_MS)) {
                    anchorMs.set(projected)
                    anchorWallMs.set(nowMs)
                }
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
        trackEndHandshakeJob?.cancel()
        trackEndHandshakeJob = null
        playConfirmJob?.cancel()
        playConfirmJob = null
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
                "cast-resume source=ui-disconnect takeOver=${target ?: "keep-local"} " +
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
        // 与「用户点断开」区分开：这条才是被动失去投屏（设备离线 / 流错误 / 重投失败）。
        CastNetworkLog.log("cast-fallback reason=$reason")
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
     *
     * **但推进前要先做一次终止态握手**（C1，真机 20261107_184914）：协调器判定
     * 「播完」的那一帧与渲染器置 STOPPED 是同一秒，此时电视还在为旧 URI 收尾，
     * 立刻 `SetAVTransportURI` 会把它的重连/控制服务重启顶掉，画面直接退出。
     * 延迟 [TRACK_END_HANDSHAKE_MS] 再推进；期间用户切歌 / 停止投屏 / 电视自己
     * 恢复播放都要取消这次推进（代次 + 投屏态 + 播放态三重校验）。
     *
     * 握手刻意放在**宿主动作层**而非协调器判据层：协调器是被动等 1Hz 状态帧的
     * 状态机，轮询静默时窗口永远不会到期，会重新引入「曲末卡死」；这里由协程
     * delay 驱动，一定收敛，「STOPPED 一律推进」判据与全部历史时序测试零改动。
     */
    override fun onCastTrackEnded() {
        val generation = dlnaCast.state.value.pushGeneration
        // 曲末决策的落地证据：看到这一行说明策略判定为「播完」；
        // 只有 own-state STOPPED 却没有它，说明又卡在判据上（见 CastQueuePolicy）。
        CastNetworkLog.log(
            "cast-track-ended advance=1 handshake=${TRACK_END_HANDSHAKE_MS}ms gen=$generation"
        )
        trackEndHandshakeJob?.cancel()
        trackEndHandshakeJob = scope.launch {
            delay(TRACK_END_HANDSHAKE_MS)
            val st = dlnaCast.state.value
            if (!st.isCasting || st.pushGeneration != generation) {
                CastNetworkLog.log(
                    "cast-track-ended canceled casting=${st.isCasting} gen=${st.pushGeneration}"
                )
                return@launch
            }
            if (st.isPlaying) {
                CastNetworkLog.log("cast-track-ended canceled reason=renderer-resumed")
                return@launch
            }
            CastNetworkLog.log("cast-track-ended advance-committed gen=$generation")
            // 标记「下一首是曲末自动推进」：电视刚自然播完上一首、正在复位，
            // 取流延迟波动大（224000 实测 7~8s），建立确认要放宽且不重发，避免
            // 6s 一到就重发 SetAVTransportURI+Play 把电视的取流打断（开头重复/静默）。
            dlnaCast.markAutoAdvanceNext()
            next()
        }
    }

    /**
     * 队列最后一首播完（循环关闭）→ 结束投屏会话并回到本地（C2）。
     *
     * 产品语义（用户确认）：电视已经自行结束传输、退出投屏画面，手机不能再显示
     * 「已连接」这种幽灵状态；主动结束投屏、把展示态写回停止、给出提示，
     * **不自动续播**本地播放。stopCast 会把 [CastState] 整包清零，MediaSession
     * 随之切回本地播放器，但本地播放器仍是暂停的——这正是「回本地但不续播」。
     */
    override fun onCastQueueFinished() {
        CastNetworkLog.log("cast-queue-finished reason=last-track")
        trackEndHandshakeJob?.cancel()
        playConfirmJob?.cancel()
        scope.launch {
            dlnaCast.stopCast()
            uiState.update {
                it.copy(isPlaying = false, error = "投屏已播完最后一首，已结束投屏")
            }
        }
    }

    /**
     * 曲中停止（渲染器重连 / 缓冲耗尽后把 URI 置 STOPPED）→ 重投**当前曲**并续播。
     *
     * 关键是不能走 [next]：真机 20261107_165555 里电视正在重新拉全量流，App 同秒
     * `SetAVTransportURI` 换成下一首，把电视的重连顶掉，DLNA 播放器直接退出画面。
     * 这里只把同一首重新投出去，等它进入 PLAYING 再 seek 回中断位置，队列完全不动。
     */
    override fun onCastInterrupted(progressMs: Long) {
        CastNetworkLog.log("cast-interrupted recast progress=${progressMs}ms")
        // 提示落在统一错误通道（PlayerScreen 把它传给遥控页 notice 位，复用已有展示处）；
        // 诊断日志同时留一份可追溯证据，避免提示被 dismiss 后无从查证。
        uiState.update { it.copy(error = "投屏中断，已自动重连当前曲目") }
        recastCurrent()
        scope.launch {
            delay(RECAST_SEEK_DELAY_MS)
            if (!dlnaCast.state.value.isCasting) return@launch
            dlnaCast.seek(progressMs)
            dlnaCast.noteSeekOptimistic()
            CastNetworkLog.log("cast-interrupted-seek progress=${progressMs}ms")
        }
    }

    /**
     * 曲中 STOPPED 但处于「用户刚拖过进度条」的保护窗口内 → 先尝试**就地 play** 恢复，
     * 而非整套重投（A 方案，174553 体验层「拖进度条 2~4s 静音」修复）。
     *
     * 整套重投（[onCastInterrupted]）会 `SetAVTransportURI`+`Play`+`Seek`，电视重连取流
     * 带来 2~4s 静音；而用户拖进度条时渲染器本就会瞬间 STOPPED 再续播，多数情况只需补
     * 一次 `Play` 就能就地拉回，静音压到 ~1s 内。
     *
     * 恢复判据必须用**原始** `playbackState`（命中即恢复），不能看 `isPlaying`：命令宽限
     * （[com.puretone.player.playback.cast.CastPlayStatePolicy]）只遮蔽 `isPlaying`，
     * `play()` 之后宽限期内 `isPlaying` 恒为命令期望态，会伪造「已恢复」。
     * 观察窗口内渲染器仍 STOPPED/ERROR → 降级走全套重投（复用 [recastCurrent] + seek 回原位
     * + noteSeekOptimistic，**但不调 noteUserSeek**，否则会重置 lastSeekMs 再次进本分支递归）。
     */
    override fun onCastInterruptedSeekRecover(progressMs: Long) {
        CastNetworkLog.log("cast-interrupted-seek-recover progress=${progressMs}ms")
        uiState.update { it.copy(error = "投屏中断，正在尝试原地恢复") }
        scope.launch {
            if (!dlnaCast.state.value.isCasting) return@launch
            dlnaCast.play("seek-recover")
            delay(SEEK_RECOVER_OBSERVE_MS)
            val st = dlnaCast.state.value
            val recovered = st.isCasting && st.playbackState == CastQueuePolicy.STATE_PLAYING
            if (recovered) {
                CastNetworkLog.log("cast-interrupted-seek-recover ok progress=${progressMs}ms")
                uiState.update { it.copy(error = null) }
                return@launch
            }
            // 降级：整套重投当前曲（与 onCastInterrupted 同款时序，队列不动）
            CastNetworkLog.log("cast-interrupted-seek-recover failed -> recast progress=${progressMs}ms")
            uiState.update { it.copy(error = "投屏中断，已自动重连当前曲目") }
            recastCurrent()
            delay(RECAST_SEEK_DELAY_MS)
            if (!dlnaCast.state.value.isCasting) return@launch
            dlnaCast.seek(progressMs)
            dlnaCast.noteSeekOptimistic()
            CastNetworkLog.log("cast-interrupted-seek-recast-seek progress=${progressMs}ms")
        }
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

    /** 对外进度/时长统一取自 uiState：它已镜像电视轮询，并叠加外推与乐观窗口 */
    override val displayPositionMs: Long
        get() = uiState.value.positionMs

    override val displayDurationMs: Long
        get() {
            val ui = uiState.value
            return CastProgressPolicy.displayDurationMs(
                rendererDurationMs = 0L,
                mirrorRenderer = false,
                uiDurationMs = ui.durationMs,
                entryDurationMs = ui.currentEntry?.durationMs
            )
        }

    /** 对外播放态统一取 uiState：命令即时写入 + 稳定化轮询镜像，通知栏不再单读 1Hz 值 */
    override val displayIsPlaying: Boolean
        get() = uiState.value.isPlaying

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

    /**
     * 通知栏 / 锁屏的 Play：必须**即时写 uiState.isPlaying**（C3）。
     *
     * 门面 [CastPlaybackPlayer.isPlaying] 读的正是 uiState，而旧实现只把命令发给
     * 渲染器、不写展示态；1Hz 镜像又受命令宽限保护，展示态长期锁在「播放中」，
     * 于是取反基准仍是「正在播放」，用户点播放反而下发 Pause —— 真机
     * 20261107_184914 的「卡片可暂停、不可播放」。App 内路径
     * `PlaybackController.play()` 一直有这行 uiState 写入，这里把通知栏补齐。
     *
     * 另加回读确认：宽限到期后渲染器仍未 PLAYING 就自动重发一次 Play，仍失败则
     * 重投当前曲（与 B3「重新 SetAVTransportURI 才是有效动作」同一手法）。
     */
    override fun playOnRenderer() {
        uiState.update { it.copy(isPlaying = true) }
        playConfirmJob?.cancel()
        playConfirmJob = scope.launch {
            dlnaCast.play("notification")
            confirmPlayOnRenderer(retriesLeft = 1)
        }
    }

    /** Play 回读未达目标态时的补救：重发一次 → 仍失败则重投当前曲 */
    private suspend fun confirmPlayOnRenderer(retriesLeft: Int) {
        delay(PLAY_CONFIRM_DELAY_MS)
        val st = dlnaCast.state.value
        // 展示态已被别处改回暂停（例如用户在 App 内播放页点了暂停，那条路径不经过
        // pauseOnRenderer、不会 cancel 本 job）时绝不能补救播放，否则会把刚暂停的
        // 渲染器又拉起来。投屏结束 / 渲染器已在播同理都是「无需补救」。
        if (!st.isCasting || st.isPlaying || !uiState.value.isPlaying) return
        if (retriesLeft > 0) {
            CastNetworkLog.log("cast-cmd play retry reason=not-playing-after-guard source=notification")
            dlnaCast.play("notification-retry")
            confirmPlayOnRenderer(retriesLeft - 1)
            return
        }
        CastNetworkLog.log("cast-cmd play retry-exhausted action=recast-current")
        uiState.update { it.copy(error = "电视未响应播放，已重新投送当前曲目") }
        recastCurrent()
    }

    override fun pauseOnRenderer() {
        playConfirmJob?.cancel()
        uiState.update { it.copy(isPlaying = false) }
        scope.launch { dlnaCast.pause("notification") }
    }

    override fun seekOnRenderer(positionMs: Long) {
        // 与 App 内 PlaybackController.seekTo 的投屏分支对齐：先记乐观窗口挡住 1Hz
        // 轮询把「seek 前的旧值」镜像回来，再立即写 uiState 让通知栏/锁屏进度条
        // 跟着拖动走。旧实现只发 seek、两样都不做，拖完进度条立刻被拉回旧位置，
        // 与电视对不上（真机 20261107_203641 里连续拖两次正是这个表现）。
        val ms = positionMs.coerceAtLeast(0L)
        dlnaCast.noteSeekOptimistic()
        dlnaCast.noteUserSeek()
        uiState.update { it.copy(positionMs = ms) }
        scope.launch { dlnaCast.seek(ms) }
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
