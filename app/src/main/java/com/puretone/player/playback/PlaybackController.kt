package com.puretone.player.playback

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.puretone.player.data.settings.PureToneSettings
import com.puretone.player.data.settings.SettingsRepository
import com.puretone.player.playback.cast.DlnaCastManager
import com.puretone.player.playback.cast.CastState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 播放控制编排（P1-1 拆分第七刀）。
 *
 * 把「用户命令如何落到播放器 / 投屏 / 命令门禁 / 设置」这一簇逻辑从 [PlaybackManager]
 * 下沉到本类。设计要点（与 [PlaybackEventListener] 同源手法）：
 * - 可共享的依赖（player 提供方、uiState、crossfade、gate、dlnaCast、settings、
 *   sessionStore、serviceLauncher、scope）直接持有，零间接层；
 * - 仅「宿主才拥有的副作用」经 [PlaybackControlSink] 窄接口回调：恢复会话、立即存档、
 *   投屏重投、空闲停服。这些是 [PlaybackManager] 的私有方法，且依赖第八刀才会下沉的
 *   队列编排（如 restoreLastSession 内部调 doSetQueueAndPlay），故留在宿主。
 * - cancelCrossfade 本就只是 [CrossfadeCoordinator.cancel] 的转发，这里直接持 crossfade。
 *
 * ExoPlayer 为 final、项目无 mock 框架，故播放控制不强行构造播放器替身；其行为等价性
 * 由逐行对齐 + 播放包回归测试 + CI 守门保证。
 */
internal class PlaybackController(
    private val playerProvider: () -> ExoPlayer?,
    private val uiState: MutableStateFlow<PlaybackUiState>,
    private val crossfade: CrossfadeCoordinator,
    private val gate: PlaybackCommandGate,
    private val dlnaCast: DlnaCastManager,
    private val settings: SettingsRepository,
    private val sessionStore: PlaybackSessionStore,
    private val serviceLauncher: PlaybackServiceLauncher,
    private val scope: CoroutineScope,
    private val sink: PlaybackControlSink
) : SpeedControlPort {
    fun togglePlayPause() {
        crossfade.cancel()
        // 投屏态：必须按**已稳定的展示态**取反。本地 ExoPlayer 恒为暂停，不能用它；
        // 也不能直读 1Hz 轮询的 castState.isPlaying——它比命令滞后 1~2 帧，且渲染器
        // 偶发一帧 PAUSED_PLAYBACK 会让「点播放」被取反成 Pause（真机 18:24:25）。
        // uiState.isPlaying 由命令即时写入、由 CastSessionController 镜像稳定后的轮询值，
        // 与遥控页/迷你条/通知栏显示的是同一个值，取反基准才不会和 UI 打架。
        if (castState().isCasting) {
            if (uiState.value.isPlaying) pause() else play()
            return
        }
        val p = playerProvider()
        if (p == null) {
            gate.pendRestore()
            serviceLauncher.ensureStarted()
            return
        }
        if (p.isPlaying) pause() else play()
    }

    fun play() {
        crossfade.cancel()
        // 投屏态：命令属于渲染器。本地若也起播，手机与电视会同时出声。
        if (castState().isCasting) {
            scope.launch { dlnaCast.play("ui") }
            uiState.update { it.copy(isPlaying = true) }
            return
        }
        val p = playerProvider()
        if (p == null) {
            gate.pendRestore()
            serviceLauncher.ensureStarted()
            return
        }
        if (p.mediaItemCount == 0) {
            scope.launch { sink.restoreLastSession() }
            return
        }
        p.play()
    }

    fun pause() {
        crossfade.cancel()
        // 用户显式暂停：作废「曲间停顿」的延时续播，否则两秒后会把暂停又拉起来
        sink.cancelInterTrackGap()
        // 投屏态：暂停的是渲染器。本地本就是暂停的，这里不能再去 pause 本地
        // （否则 stopCast 后恢复本地播放时，位置/状态会被二次重置）。
        if (castState().isCasting) {
            // 幂等：连点/误触时，1Hz 轮询还没把上一次 Pause 反映进状态，
            // 重复下发不会让渲染器更「暂停」，只会把 user-pause 日志与状态抖动搅在一起。
            if (!uiState.value.isPlaying) {
                PlaybackEventLog.record("pause-skipped", "投屏态已暂停")
                return
            }
            scope.launch { dlnaCast.pause("ui") }
            uiState.update { it.copy(isPlaying = false) }
            sink.saveSessionNow()
            PlaybackEventLog.record("user-pause", "投屏态")
            return
        }
        PlaybackEventLog.record("user-pause", "本地 idx=${playerProvider()?.currentMediaItemIndex}")
        playerProvider()?.pause()
        // 同步刷新播放态：onIsPlayingChanged 监听是异步的，投屏 startCast 暂停本地后
        // 若不立即置 false，UI 会短暂显示「播放中」与投屏中提示条冲突（P0-2-③）。
        uiState.update { it.copy(isPlaying = false) }
        sink.saveSessionNow()
    }

    fun stop() {
        crossfade.cancel()
        sink.cancelInterTrackGap()
        PlaybackEventLog.record("user-stop", "清空队列")
        playerProvider()?.stop()
        playerProvider()?.clearMediaItems()
        // P3-21：旧实现整包重置 uiState，把 repeatMode / shuffle / speed 一并打回默认，
        // 而播放器实例上的这些属性并未被重置 → UI 与真实状态不符。
        // 与 clearQueue 对齐：只清「瞬时」字段，保留用户偏好。
        uiState.update { state ->
            state.copy(
                queue = emptyList(),
                queueIndex = -1,
                currentSongId = null,
                positionMs = 0L,
                durationMs = 0L,
                isTranscoding = false,
                isBuffering = false,
                error = null
            )
        }
        // 让仍在途的旧队列任务失效（例如 APE 转码挂起中），并作废 APE 重试标记
        // （P2-14：否则新队列同索引的 APE 首次出错会被误判「已重试过」，跳过转码卡 IDLE）
        gate.onQueueStructureChanged()
        // P3-16：作废全部挂起的播放请求——否则服务重启（bind 重放 pendingPlay）
        // 会把这次已停止的队列又播出来，与用户「显式停止」意图相反。
        gate.clearPending()
        // 显式停止即放弃续播：清会话，否则下次冷启动会把已停的队列又恢复出来
        sessionStore.clear()
        sink.stopServiceIfIdle()
    }

    fun next() {
        crossfade.cancel()
        val p = playerProvider() ?: return
        // 同步计算下一首索引（尊重 repeat/shuffle；INDEX_UNSET = 无下一首）。
        // 用 Timeline.getNextWindowIndex 与下方 seek 语义一致（顺序/随机/循环三态统一），
        // 但能立即拿到目标索引，用于同步刷新 UI 镜像——投屏态下 ViewModel 需马上读
        // currentSongId 决定投屏哪一首，仅靠异步 onMediaItemTransition 会有竞态。
        val target = p.currentTimeline.getNextWindowIndex(p.currentMediaItemIndex, p.repeatMode, p.shuffleModeEnabled)
        if (p.hasNextMediaItem()) p.seekToNextMediaItem()
        else if (uiState.value.repeatMode == RepeatMode.ALL && p.mediaItemCount > 0) {
            // P3-15：循环到末尾回绕时，须回到「当前播放顺序」的首位，而非 timeline 的
            // 首位——开启随机时 seekTo(0) 会跳到随机序列之外的第一首，打乱随机体验。
            // 这里改用 Timeline.getFirstWindowIndex(shuffleModeEnabled)——它正是
            // Media3 自身在 REPEAT_ALL 回绕时使用的索引，与播放器内部语义完全一致。
            val first = p.currentTimeline.getFirstWindowIndex(p.shuffleModeEnabled)
            p.seekTo(if (first >= 0) first else 0, 0L)
        }
        if (target != C.INDEX_UNSET) {
            val id = runCatching { p.getMediaItemAt(target)?.mediaId?.toLongOrNull() }.getOrNull()
            uiState.update { it.copy(currentSongId = id, queueIndex = target, positionMs = 0L, durationMs = 0L) }
        }
        sink.recastCurrentIfCasting()
    }

    fun previous() {
        crossfade.cancel()
        val p = playerProvider() ?: return
        // 同步计算上一首索引（尊重 repeat/shuffle；INDEX_UNSET = 无上一首→回到当前曲开头）。
        val target = p.currentTimeline.getPreviousWindowIndex(p.currentMediaItemIndex, p.repeatMode, p.shuffleModeEnabled)
        if (p.hasPreviousMediaItem()) p.seekToPreviousMediaItem() else p.seekTo(0L)
        if (target != C.INDEX_UNSET) {
            val id = runCatching { p.getMediaItemAt(target)?.mediaId?.toLongOrNull() }.getOrNull()
            uiState.update { it.copy(currentSongId = id, queueIndex = target, positionMs = 0L, durationMs = 0L) }
        } else {
            // 无上一首：回到当前曲开头（与原 else 分支 p.seekTo(0L) 一致）
            uiState.update { it.copy(positionMs = 0L) }
        }
        sink.recastCurrentIfCasting()
    }

    fun seekTo(positionMs: Long) {
        crossfade.cancel()
        val ms = positionMs.coerceAtLeast(0L)
        // 投屏态：进度条拖的是渲染器上的位置，手机进度由投屏轮询镜像同步。
        // 先记一段乐观窗口：渲染器执行 Seek 是异步的，头几次回读仍是旧值，
        // 不拦住就会把刚拖到的位置又拉回去。
        if (castState().isCasting) {
            dlnaCast.noteSeekOptimistic()
            scope.launch { dlnaCast.seek(ms) }
            uiState.update { it.copy(positionMs = ms) }
            return
        }
        val p = playerProvider() ?: return
        p.seekTo(ms)
        uiState.update { it.copy(positionMs = ms) }
    }

    /**
     * 循环模式：本地即时生效 + 写回 DataStore。
     * 冷启动后由 applyPlayOrder 从设置恢复，不再重置为 OFF（P0-3）。
     */
    fun setRepeatMode(mode: RepeatMode) {
        val p = playerProvider() ?: return
        p.repeatMode = when (mode) {
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
        }
        uiState.update { it.copy(repeatMode = mode) }
        scope.launch {
            settings.setRepeatMode(
                when (mode) {
                    RepeatMode.ALL -> PureToneSettings.REPEAT_ALL
                    RepeatMode.ONE -> PureToneSettings.REPEAT_ONE
                    RepeatMode.OFF -> PureToneSettings.REPEAT_OFF
                }
            )
        }
    }

    /** 随机播放：本地即时生效 + 写回 DataStore（P0-3） */
    fun setShuffle(enabled: Boolean) {
        playerProvider()?.shuffleModeEnabled = enabled
        uiState.update { it.copy(shuffle = enabled) }
        scope.launch { settings.setShuffle(enabled) }
    }

    override fun setSpeed(speed: Float) {
        val p = playerProvider() ?: return
        p.setPlaybackSpeed(speed.coerceIn(0.25f, 3.0f))
        uiState.update { it.copy(speed = p.playbackParameters.speed) }
    }

    /**
     * 睡眠定时：只写 DataStore（分钟数 + 到期时刻）。
     * 真正的倒计时由设置流触发 applySleepTimer 建立/取消 job，
     * 保证「UI 设的定时」与「播放器实际定时」同源，不会出现两套计时器。
     */
    fun setSleepTimer(minutes: Int) {
        scope.launch {
            if (minutes > 0) settings.setSleepTimerMinutes(minutes) else settings.clearSleepTimer()
        }
    }

    /** 「播完本曲停止」：当前这首自然播完就暂停（不限时长） */
    fun setSleepTimerEndOfTrack() {
        scope.launch { settings.setSleepTimerEndOfTrack() }
    }

    fun dismissError() = uiState.update { it.copy(error = null) }

    /** 投屏状态快照（控制逻辑多处需要同步读取渲染器播放态） */
    private fun castState(): CastState = dlnaCast.state.value
}

/**
 * 播放控制无法在 [PlaybackController] 内落地的宿主副作用。
 * [PlaybackManager] 实现本接口——这些方法依赖它独有的私有字段与第八刀才会下沉的
 * 队列编排（如 restoreLastSession 内部调 doSetQueueAndPlay）。
 */
interface PlaybackControlSink {
    /** 队列为空时恢复上次会话（autoPlay=false 重建并暂停）；play() 的兜底分支调用 */
    suspend fun restoreLastSession(): Boolean

    /** 立即把当前会话写入存档（pause / stop 调用） */
    fun saveSessionNow()

    /** 投屏期本地切歌后，把当前曲重新投给渲染器（next / previous 调用） */
    fun recastCurrentIfCasting()

    /** 播放器空闲（非播放且队列空）时停掉前台服务（stop 调用） */
    fun stopServiceIfIdle()

    /**
     * 作废在途的「曲间停顿」延时续播。
     *
     * 关闭无缝播放时，自动切歌边界会「暂停 → 延时 → 继续」。若用户在这段延时里
     * 按了暂停/停止，必须把续播意图一起取消，否则会出现「按了暂停，两秒后自己又响了」。
     */
    fun cancelInterTrackGap()
}
