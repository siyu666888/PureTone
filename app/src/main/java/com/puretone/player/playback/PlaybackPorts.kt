package com.puretone.player.playback

import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import com.puretone.player.data.local.entity.SongEntity
import com.puretone.player.data.settings.PureToneSettings

/**
 * 播放核心协作者的窄端口接口（拆分收尾测试化）。
 *
 * [PlaybackEventEffects] / [PlaybackSettingsApplier] / [PlaybackSessionRestorer] 在 P1-1 拆分中
 * 从 [PlaybackManager] 抽出，但它们依赖的协作者（AudioEffectController / SleepTimerController /
 * 等）都是 `final class`，单元测试无法继承造替身。这里把「被测类实际调用的方法」收敛成最小
 * 接口（端口），由具体类 `implement`；被测类依赖端口而非具体类，测试即可注入 fake。
 *
 * 具体类实现接口后，[PlaybackManager] 构造处传的仍是这些实例，类型自动匹配，**无需改动构造处**。
 * 符合项目既定方法论：测播放逻辑先收窄窄接口再写替身。
 */

/** 音效控制器端口 */
internal interface AudioEffectPort {
    fun attachToSession(audioSessionId: Int)
    fun applySettings(settings: PureToneSettings)
}

/** 播放错误处理器端口 */
internal interface PlaybackErrorPort {
    fun onPlaying()
    fun handle(error: PlaybackException)
}

/** 音频焦点端口 */
internal interface AudioFocusPort {
    fun onIsPlayingChanged(isPlaying: Boolean)
}

/** 桌面小部件更新端口 */
internal interface WidgetUpdatePort {
    fun requestUpdate()
}

/**
 * 会话存档端口：写（[PlaybackEventEffects]）与读（[PlaybackSessionRestorer]）共用。
 * 把 [PlaybackSessionStore] 的存档/恢复能力收敛成端口，便于两个协作者各自注入 fake。
 */
internal interface SessionStorePort {
    fun saveDebounced(snapshot: () -> SavedSession?)
    fun saveNow(snapshot: SavedSession)
    fun positionToSave(rawPositionMs: Long, durationMs: Long): Long
    suspend fun restoreQueue(): RestoredQueue?
}

/** 音乐仓库端口（播放/完成计数 + 收听时长结算） */
internal interface PlaybackRepoPort {
    suspend fun recordPlay(songId: Long)
    suspend fun recordCompletion(songId: Long)
    /** 暂停/切歌时结算已收听毫秒（需求4 听歌报告） */
    suspend fun settlePlayback(songId: Long, playedMs: Long)
}

/** 交叉淡化协调器端口 */
internal interface CrossfadePort {
    val isCrossfading: Boolean
    fun isOutgoing(player: ExoPlayer): Boolean
    fun finalize()
}

/** 曲间停顿端口 */
internal interface GapPort {
    fun start()
}

/** 睡眠定时端口（[PlaybackEventEffects] 用 finishEndOfTrack，[PlaybackSettingsApplier] 用两者） */
internal interface SleepTimerPort {
    fun applySettings(settings: PureToneSettings)
    fun finishEndOfTrack()
}

/** 播放速度/命令控制器端口（[PlaybackSettingsApplier] 依赖） */
internal interface SpeedControlPort {
    fun setSpeed(speed: Float)
}

/** 队列编排端口（[PlaybackSessionRestorer] 依赖） */
internal interface QueueApplyPort {
    suspend fun applyQueueAndPlay(
        songs: List<SongEntity>,
        startIndex: Int,
        startPositionMs: Long,
        autoPlay: Boolean
    )
}
