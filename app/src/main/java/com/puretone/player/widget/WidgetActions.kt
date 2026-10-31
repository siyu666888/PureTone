package com.puretone.player.widget

import android.content.Context
import android.util.Log
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.puretone.player.playback.PlaybackManager
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "WidgetActions"

/**
 * 等待「播放状态真正变化」的上限。
 *
 * 命令下发后 ExoPlayer 的回调（onMediaItemTransition / onIsPlayingChanged）不是同步到达的，
 * 立刻刷快照会把**切歌前**的旧曲目推出去。这里等状态真的变了再刷新；超时了也要照常刷，
 * 因为有些命令本就不改变状态（例如已是最后一首时点下一首）。
 */
private const val SETTLE_TIMEOUT_MS = 1_200L

/**
 * 小部件按钮回调。
 *
 * 两个约束：
 * 1. Glance 在后台线程执行 onAction，而 ExoPlayer 只能在主线程触碰 → 统一切 Main.immediate；
 * 2. 没有任何队列时（进程刚被回收）「上一首/下一首」点了不该没反应——先试着恢复
 *    上次会话到暂停态（[PlaybackManager.ensureQueueRestored]），恢复成功就这次只做恢复。
 *
 * 刷新时机是本次修正的核心：见 [runCommand]。
 */
class PlayPauseAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
        runCommand(context, glanceId) { playback ->
            // 队列为空（进程被回收过）→ 先恢复上次会话再播放；否则按当前状态切换
            if (playback.ensureQueueRestored()) playback.play() else playback.togglePlayPause()
        }
}

class NextAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
        runCommand(context, glanceId) { playback ->
            if (!playback.ensureQueueRestored()) playback.next()
        }
}

class PreviousAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
        runCommand(context, glanceId) { playback ->
            if (!playback.ensureQueueRestored()) playback.previous()
        }
}

/**
 * 统一下发播放命令并在**状态落定后**刷新小部件。
 *
 * 为什么必须先等一拍：旧的写法在下发命令之后立刻 `update()`，
 * 而此刻 uiState 仍是旧歌/旧播放态——ExoPlayer 的移行与 isPlaying 回调尚未投递，
 * 于是桌面拿到的快照和上一次完全一样（表现为「点了切歌，小部件纹丝不动」）。
 *
 * 刷新按顺序做两件事：
 * 1. 重画被点的这一个实例（即时反馈）；
 * 2. 再走 App 内的 [WidgetUpdater] 统一推一次——桌面上可能同时存在多个实例，
 *    且「播放器真正开始播放」可能晚于本协程返回（冷起播要先 prepare），
 *    后续状态变化仍会经该通道补上。
 */
private suspend fun runCommand(
    context: Context,
    glanceId: GlanceId,
    command: suspend (PlaybackManager) -> Unit
) {
    val playback = playbackManager(context)
    val before = withContext(Dispatchers.Main.immediate) { snapshotOf(playback) }
    withContext(Dispatchers.Main.immediate) {
        // 后台冷启动：等播放器就绪，否则命令会落在 player == null 上被静默丢弃
        playback.ensureServiceReady()
        command(playback)
    }
    awaitSettled(playback, before)
    refresh(context, glanceId)
    runCatching { widgetUpdater(context).requestUpdate() }
        .onFailure { Log.w(TAG, "global widget update failed", it) }
}

/** 快照「足以引起小部件重画」的两个字段 */
private fun snapshotOf(playback: PlaybackManager): Pair<Long?, Boolean> =
    playback.uiState.value.let { it.currentSongId to it.isPlaying }

private suspend fun awaitSettled(playback: PlaybackManager, before: Pair<Long?, Boolean>) {
    withContext(Dispatchers.Main.immediate) {
        withTimeoutOrNull(SETTLE_TIMEOUT_MS) {
            playback.uiState.first { snapshotOf(playback) != before }
        }
    }
}

private fun playbackManager(context: Context): PlaybackManager =
    entryPoint(context).playbackManager()

private fun widgetUpdater(context: Context): WidgetUpdater =
    entryPoint(context).widgetUpdater()

private fun entryPoint(context: Context): WidgetEntryPoint =
    EntryPointAccessors.fromApplication(
        context.applicationContext,
        WidgetEntryPoint::class.java
    )

/**
 * 重画一次指定实例。
 *
 * 失败只记日志：AppWidget 更新是尽力而为的事，不该把异常抛回 Glance 把 host 拖崩。
 */
private suspend fun refresh(context: Context, glanceId: GlanceId) {
    runCatching { PureToneWidget().update(context, glanceId) }
        .onFailure { Log.w(TAG, "widget refresh failed", it) }
}
