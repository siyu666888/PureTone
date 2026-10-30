package com.puretone.player.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * 小部件接收器：系统在「添加到桌面 / 桌面重启 / 收到 AppWidget 更新」时回调。
 *
 * 只做一件事——把实现委派给 [PureToneWidget]；真正的渲染 Refresh 由静态方法
 * （[GlanceAppWidget.update]）在 App 内主动调用，不等系统的周期刷新。
 */
class PureToneWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PureToneWidget()
}
