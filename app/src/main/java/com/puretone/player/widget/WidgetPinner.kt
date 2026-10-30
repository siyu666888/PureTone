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
            val provider = ComponentName(context, PureToneWidgetReceiver::class.java)
            manager.requestPinAppWidget(provider, null, null)
            Log.i(TAG, "requested pin widget")
            true
        }.onFailure { Log.w(TAG, "request pin widget failed", it) }
            .getOrDefault(false)
    }

    private companion object {
        const val TAG = "WidgetPinner"
    }
}
