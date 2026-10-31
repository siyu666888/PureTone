package com.puretone.player.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「添加到桌面」入口。
 *
 * 桌面小部件通常由用户长按桌面 → 小组件 → 翻列表找到 App 手动拖出来：路径深、
 * 而且不少第三方桌面的小组件列表里压根不显示新装 App。这里走 Android 8+ 的
 * [AppWidgetManager.requestPinAppWidget]，由系统弹窗确认后 launcher 自己放置，
 * 不依赖本报 res/any 拖拽手势。
 *
 * 不可用时的态度明确：[isSupported] 返回 false（低于 API 26 或桌面不支持 pin），
 * UI 直接禁用入口，而不是让用户点一下没反应。
 */
@Singleton
class WidgetPinner @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /** 当前桌面是否支持「固定到桌面」 */
    fun isSupported(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return runCatching { AppWidgetManager.getInstance(context).isRequestPinAppWidgetSupported }
            .getOrDefault(false)
    }

    /**
     * 请求把本 App 小部件固定到桌面。
     *
     * @return 是否真的发起了请求（false = 桌面不支持，UI 层据此给提示）
     */
    fun requestPin(): Boolean {
        if (!isSupported()) return false
        return runCatching {
            val manager = AppWidgetManager.getInstance(context)
            manager.requestPinAppWidget(provider(), null, null)
            Log.i(TAG, "requested pin widget")
            true
        }.onFailure { Log.w(TAG, "request pin widget failed", it) }
            .getOrDefault(false)
    }

    /**
     * 桌面上当前存在的本 App 小部件数量。
     *
     * 设置页的开关反映的必须是「真实有没有添加」，而不是一个偏好位：
     * 用户可能在系统确认弹窗里取消，也可能添加完后又在桌面上长按删掉——
     * 以 [AppWidgetManager.getAppWidgetIds] 的实际记录为准，每次刷新重新读。
     */
    fun pinnedCount(): Int = runCatching {
        AppWidgetManager.getInstance(context).getAppWidgetIds(provider()).size
    }.onFailure { Log.w(TAG, "query appWidgetIds failed", it) }
        .getOrDefault(0)

    /** 桌面上是否已经存在本 App 的小部件 */
    fun isAdded(): Boolean = pinnedCount() > 0

    private fun provider(): ComponentName =
        ComponentName(context, PureToneWidgetReceiver::class.java)

    private companion object {
        const val TAG = "WidgetPinner"
    }
}
