package com.puretone.player.playback

import android.util.Log
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.puretone.player.data.repository.MusicRepository
import com.puretone.player.data.settings.PureToneSettings
import com.puretone.player.effect.AudioEffectController
import com.puretone.player.widget.WidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 播放事件副作用落地（P1-1 拆分收尾第九刀）。
 *
 * [PlaybackEventListener] 只负责「解释发生了什么」（事件分类 + 完成去重），本类负责
 * 「宿主据此要做什么」：刷新 UI 镜像、重挂音效会话、去抖存档、ReplayGain 刷新、APE 预热、
 * 小部件同步、空闲停服等。把这一簇 [PlaybackEventSink] 实现与「事件触发的会话存档」从
 * [PlaybackManager] 搬出后，主类只剩字段声明 + 构造期依赖装配 + bind/unbind 生命周期。
 *
 * 本类需要的「宿主独有、不便外泄」的能力（下一首 APE 预热 / 当前曲 ReplayGain 刷新 /
 * 空闲停服）以回调注入，避免反向依赖回 [PlaybackManager]。
 */
internal class PlaybackEventEffects(
    private val playerProvider: () -> ExoPlayer?,
    private val uiState: MutableStateFlow<PlaybackUiState>,
    private val audioEffectController: AudioEffectController,
    private val lastSettingsProvider: () -> PureToneSettings,
    private val errorHandler: PlaybackErrorHandler,
    private val playbackFocus: PlaybackAudioFocus,
    private val widgetUpdater: WidgetUpdater,
    private val sessionStore: PlaybackSessionStore,
    private val scope: CoroutineScope,
    private val musicRepository: MusicRepository,
    private val crossfade: CrossfadeCoordinator,
    private val interTrackGap: InterTrackGap,
    private val sleepTimer: SleepTimerController,
    private val gaplessEnabledProvider: () -> Boolean,
    private val warmUpFollowingApe: () -> Unit,
    private val applyReplayGainForCurrent: () -> Unit,
    private val stopServiceIfIdle: () -> Unit
) : PlaybackEventSink {

    private companion object {
        const val TAG = "PlaybackEventEffects"
    }

    override fun onAudioSessionChanged(audioSessionId: Int) {
        audioEffectController.attachToSession(audioSessionId)
        // session 重建后把当前设置重新灌一遍（音效/播放模式不因换会话丢失）
        audioEffectController.applySettings(lastSettingsProvider())
    }

    override fun onSecondaryAudioSessionChanged(audioSessionId: Int) {
        audioEffectController.attachToSession(audioSessionId)
        audioEffectController.applySettings(lastSettingsProvider())
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        uiState.update { it.copy(isPlaying = isPlaying) }
        // 正常播起来了：连续失败计数归零（只有「一直在失败」才判定为坏队列）
        if (isPlaying) errorHandler.onPlaying()
        else PlaybackEventLog.recordPauseWithQueueIfAny(playerProvider(), uiState.value.currentSongId)
        // 焦点跟着「真的在发声」走：起播即申请、真正停住才放弃
        playbackFocus.onIsPlayingChanged(isPlaying)
        // 桌面小部件同步（内部去抖，连续变化只推一次 IPC）
        widgetUpdater.requestUpdate()
        saveSessionDebounced()
    }

    override fun onBufferingChanged(isBuffering: Boolean) {
        uiState.update { it.copy(isBuffering = isBuffering) }
    }

    override fun onTrackChanged(songId: Long, queueIndex: Int, durationMs: Long) {
        uiState.update { state ->
            state.copy(
                currentSongId = songId,
                queueIndex = queueIndex,
                durationMs = durationMs
            )
        }
    }

    override fun recordPlay(songId: Long) {
        scope.launch { musicRepository.recordPlay(songId) }
    }

    override fun onTrackSideEffects(reason: Int) {
        warmUpFollowingApe()
        // 曲目切换后立即刷新 ReplayGain 增益（取当前曲目标签 + 设置）
        applyReplayGainForCurrent()
        // 无缝播放关闭：自动切歌边界处停顿一小会再继续（必须「暂停 → 延时 → 继续」，
        // 只暂停不续播会让播放到此为止——历史缺陷）。
        if (!gaplessEnabledProvider() && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
            interTrackGap.start()
        }
        // 「播完本曲停止」到点：自然播完切下一首（AUTO）或单曲循环重头（REPEAT）。
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
            reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
        ) {
            sleepTimer.finishEndOfTrack()
        }
        // 曲目变化（含清空队列导致切到空）→ 同步桌面小部件
        widgetUpdater.requestUpdate()
    }

    override fun recordCompletion(songId: Long) {
        scope.launch { musicRepository.recordCompletion(songId) }
    }

    override fun onPlaybackEnded() {
        // 末首自然播完（无 AUTO/REPEAT 过渡）的收尾。完成去重已由事件层在触发处负责，
        // 此处只做：睡眠定时归零 + 立即存档 + 空闲停服。
        PlaybackEventLog.record("playback-ended", endedSnapshot())
        sleepTimer.finishEndOfTrack()
        saveSessionNow()
        stopServiceIfIdle()
    }

    override fun finalizeCrossfadeIfOutgoing(player: ExoPlayer): Boolean {
        val hit = crossfade.isCrossfading && crossfade.isOutgoing(player)
        if (hit) crossfade.finalize()
        return hit
    }

    override fun onIdleCleared() {
        PlaybackEventLog.record("idle-cleared", endedSnapshot())
        stopServiceIfIdle()
    }

    override fun onError(error: PlaybackException) {
        errorHandler.handle(error)
    }

    override fun onSecondaryError(error: PlaybackException) {
        Log.w(TAG, "secondary player error: ${error.errorCodeName} ${error.message}")
    }

    // ----- 会话存档（事件触发路径：暂停/播放态变化/播放结束） -----

    private fun saveSessionDebounced() {
        // 快照在写入时刻才求值：去抖的意义就是「以最新状态写一次」
        sessionStore.saveDebounced { sessionSnapshot() }
    }

    /** 立即把当前会话写入存档（停止 / 暂停 / 切歌等关键节点） */
    fun saveSessionNow() {
        val snapshot = sessionSnapshot() ?: return
        sessionStore.saveNow(snapshot)
    }

    /** 当前待存档的会话快照；队列为空（无可存档内容）时为 null */
    private fun sessionSnapshot(): SavedSession? {
        val state = uiState.value
        if (state.queue.isEmpty()) return null
        return SavedSession(
            songIds = state.queue.map { it.songId },
            index = state.queueIndex,
            positionMs = sessionStore.positionToSave(currentPosition(), currentDuration())
        )
    }

    /** 当前播放位置（毫秒，非负） */
    private fun currentPosition(): Long = (playerProvider()?.currentPosition ?: 0L).coerceAtLeast(0L)

    /** 当前曲目时长；未知（ExoPlayer 返回负值）时按 0 处理 */
    private fun currentDuration(): Long = playerProvider()?.duration?.takeIf { it > 0 } ?: 0L

    /** 播放结束类事件的现场快照（诊断报告靠它把「停服」与「淡化中的角色交换」对上账） */
    private fun endedSnapshot(): String =
        "idx=${playerProvider()?.currentMediaItemIndex} count=${playerProvider()?.mediaItemCount ?: 0} " +
            "cf=${crossfade.isCrossfading}"
}
