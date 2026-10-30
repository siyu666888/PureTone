package com.puretone.player.widget

import android.content.Context
import android.util.Log
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.puretone.player.playback.PlaybackManager
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "WidgetActions"

/**
 * 小部件按钮回调。
 *
 * 两个约束：
 * 1. Glance 在后台线程执行 onAction，而 ExoPlayer 只能在主线程触碰 → 统一切 Main.immediate；
 * 2. 没有任何队列时（进程刚被回收）「上一首/下一首」点了不该没反应——先试着恢复
 *    上次会话到暂停态（[PlaybackManager.ensureQueueRestored]），恢复成功就这次只做恢复。
 */
class PlayPauseAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val playback = playbackManager(context)
        withContext(Dispatchers.Main.immediate) {
            // 后台冷启动：等播放器就绪，否则命令会落在 player == null 上被静默丢弃
            playback.ensureServiceReady()
            // 队列为空（进程被回收过）→ 先恢复上次会话再播放；否则按当前状态切换
            if (playback.ensureQueueRestored()) playback.play() else playback.togglePlayPause()
        }
        refresh(context, glanceId)
    }
}

class NextAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val playback = playbackManager(context)
        withContext(Dispatchers.Main.immediate) {
            playback.ensureServiceReady()
            if (!playback.ensureQueueRestored()) playback.next()
        }
        refresh(context, glanceId)
    }
}

class PreviousAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val playback = playbackManager(context)
        withContext(Dispatchers.Main.immediate) {
            playback.ensureServiceReady()
            if (!playback.ensureQueueRestored()) playback.previous()
        }
        refresh(context, glanceId)
    }
}

private fun playbackManager(context: Context): PlaybackManager =
    EntryPointAccessors.fromApplication(
        context.applicationContext,
        WidgetEntryPoint::class.java
    ).playbackManager()

/**
 * 立刻重画一次。
 *
 * 播放命令可能要等服务起来才真正生效（pending 机制），那时由 PlaybackManager
 * 的状态回调再触发一次刷新（见 [WidgetUpdater]），两拍互补。
 */
private suspend fun refresh(context: Context, glanceId: GlanceId) {
    runCatching { PureToneWidget().update(context, glanceId) }
        .onFailure { Log.w(TAG, "widget refresh failed", it) }
}
