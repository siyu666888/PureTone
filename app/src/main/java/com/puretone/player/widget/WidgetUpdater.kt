package com.puretone.player.widget

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidgetManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** 去抖窗口：连续状态变化（如暂停→播放→切歌）只做一次 IPC */
private const val UPDATE_DEBOUNCE_MS = 300L

/**
 * 桌面小部件刷新器（App 进程内主动推）。
 *
 * 主动推而非轮询：updatePeriodMillis 最短 30 分钟且会让进程周期性空转，
 * 而用户真正在意的是「切歌 / 播放暂停 / 定时到期」这几个瞬间的同步。
 *
 * 去抖是必要的：一次 aboard 切歌会连发 isPlaying + 曲目 + 时长多个变化，
 * 每个 AppWidget 更新都是一次跨进程 binder 调用，不该按事件数量放大。
 */
@Singleton
class WidgetUpdater @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pending: Job? = null

    /** 请求刷新桌面上的所有本 App 小部件 */
    fun requestUpdate() {
        pending?.cancel()
        pending = scope.launch {
            delay(UPDATE_DEBOUNCE_MS)
            runCatching {
                val manager = GlanceAppWidgetManager(context)
                val ids = manager.getGlanceIds(PureToneWidget::class.java)
                if (ids.isEmpty()) return@runCatching
                val widget = PureToneWidget()
                ids.forEach { id -> widget.update(context, id) }
            }.onFailure { Log.w(TAG, "widget update failed", it) }
        }
    }

    private companion object {
        const val TAG = "WidgetUpdater"
    }
}
