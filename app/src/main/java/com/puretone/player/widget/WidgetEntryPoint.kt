package com.puretone.player.widget

import com.puretone.player.playback.PlaybackManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * 小部件向 Hilt 取依赖的入口。
 *
 * AppWidget 的渲染（[PureToneWidget]）与点击回调（[PlayPauseAction] 等）都由系统唤醒，
 * 组件本身没法用 @Inject 构造（Glance 要求无参构造以便反射/广播到forms重建），
 * 因此改为向已 @HiltAndroidApp 的 Application 索取进程级单例。
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun playbackManager(): PlaybackManager
    fun widgetStateProvider(): WidgetStateProvider
}
