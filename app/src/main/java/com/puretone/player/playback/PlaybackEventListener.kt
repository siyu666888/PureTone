package com.puretone.player.playback

import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * 播放事件处理（P1-1 拆分第六刀）。
 *
 * 把 ExoPlayer 的回调「解释 + 路由」从 [PlaybackManager] 拆出来：本类只负责
 * - 收听 [Player.Listener]（主 / 备两个）回调；
 * - 事件分类（转场原因 → 自然推进 / 新开始 / 用户手动切歌）；
 * - 播放完成去重（5s 窗口，驱动统计页播放次数，避免 AUTO 过渡与 STATE_ENDED 双计）；
 * - 按分类决定回调 [PlaybackEventSink] 的哪些副作用。
 *
 * 真正的副作用（UI 状态、小部件、会话存档、ReplayGain、APE 重试、服务停启等）
 * 仍由宿主 [PlaybackManager] 拥有，通过窄接口回传——与 [CrossfadeCoordinator.Host]
 * 同一手法。这样事件解释的纯逻辑可在纯 JVM 上单测（见 [PlaybackEventClassifier]
 * 与 [CompletionRecorder]），而无需 mock final 的 [ExoPlayer]。
 *
 * 两个监听器以 [primaryListenerFor] / [secondaryListener] 暴露给宿主，由宿主在 bind 时注册、
 * unbind 时注销。
 *
 * **为什么主监听器要按实例创建**：交叉淡化在曲目边界交换主/备角色，而 ExoPlayer 的回调
 * 是异步派发的——淡出方「播完」的 STATE_ENDED 可能直到换主之后才落到监听器上。此时
 * 监听器若按「当前主播放器」去解释事件，就会把**刚接上的那一首**当成「刚播完的那一首」
 * （误记完整播放 + 误触发播完本曲停止）。因此每个播放器实例各持一个监听器，回调里
 * 先确认「事件确实来自我现在的主人」（[playerProvider] 恒等比较），否则直接丢弃。
 */
internal class PlaybackEventListener(
    /** 惰性读取当前主播放器：构造时可能尚未 bind，故用 provider 而非直接引用 */
    private val playerProvider: () -> ExoPlayer?,
    /** 惰性读取当前曲目 id：转场回调里的 prevId 来自 UI 镜像，而非播放器当前项 */
    private val currentSongIdProvider: () -> Long?,
    private val sink: PlaybackEventSink
) {

    /**
     * 每个播放器实例一份主监听器（角色交换后原实例的残留回调因此可被识别并丢弃）。
     * 用弱引用键：服务反复重建会换新实例，旧条目不该被这里钉住。
     */
    private val primaryListeners = java.util.WeakHashMap<ExoPlayer, Player.Listener>()

    /** 取 [player] 对应的主监听器（同一实例重复取返回同一个对象，便于 add/remove 配对） */
    fun primaryListenerFor(player: ExoPlayer): Player.Listener =
        primaryListeners.getOrPut(player) { createPrimaryListener(player) }

    private fun createPrimaryListener(owner: ExoPlayer): Player.Listener = object : Player.Listener {

        /** 事件是否来自「我现在的主播放器」；不是就说明是角色交换前残留的回调 */
        private fun fromCurrentPrimary(): Boolean = playerProvider() === owner

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            if (!fromCurrentPrimary()) return
            sink.onAudioSessionChanged(audioSessionId)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!fromCurrentPrimary()) return
            sink.onIsPlayingChanged(isPlaying)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (!fromCurrentPrimary()) return
            sink.onBufferingChanged(playbackState == Player.STATE_BUFFERING)
            if (playbackState == Player.STATE_ENDED) {
                // 「谁结束了」必须在收尾换主之前取，否则记到的是刚接上的那一首
                val endedSongId = currentSongIdProvider()
                // 淡出兜底收尾（若本实例正是淡出方）：返回 true 表示「这是一场淡化收尾」——
                // 收尾后下一首仍在播，不应走「播放结束」语义（否则误消费「播完本曲停止」并 pause 下一首）。
                val finalized = sink.finalizeCrossfadeIfOutgoing(owner)
                endedSongId?.let { id ->
                    completionRecorder.record(id)?.let { sink.recordCompletion(it) }
                }
                if (!finalized) sink.onPlaybackEnded()
            }
            if (playbackState == Player.STATE_IDLE && owner.mediaItemCount == 0) {
                sink.onIdleCleared()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (!fromCurrentPrimary()) return
            val prevId = currentSongIdProvider()
            val songId = mediaItem?.mediaId?.toLongOrNull()
            // 上一首「完整播放完成」：自然播完（AUTO）或单曲循环回放（REPEAT）。
            // 用户手动上/下一首（SEEK）与换队列（PLAYLIST_CHANGED）不计。
            // 5s 去重：STATE_ENDED 与过渡回调可能同时指向同一首。
            if (prevId != null && songId != null &&
                PlaybackEventClassifier.isNaturalAdvance(reason)
            ) {
                completionRecorder.record(prevId)?.let { sink.recordCompletion(it) }
            }
            if (songId != null) {
                val p = playerProvider()
                sink.onTrackChanged(
                    songId = songId,
                    queueIndex = p?.currentMediaItemIndex ?: -1,
                    durationMs = p?.duration?.coerceAtLeast(0L) ?: 0L
                )
                // 只有「真的开始听这一首」才写播放历史（见分类器注释）：
                // 手动上/下一首（SEEK）会把最近播放 / 累计听歌时长 / 活跃天数整片灌水。
                if (PlaybackEventClassifier.isNaturalAdvance(reason) ||
                    PlaybackEventClassifier.isFreshStart(prevId, songId, reason)
                ) {
                    sink.recordPlay(songId)
                }
                // 切歌后的副作用簇：预热下一首 APE / 刷新 ReplayGain / 曲间停顿 /
                // 「播完本曲停止」到点 / 小部件刷新（顺序与改造前 onMediaItemTransition 一致）。
                sink.onTrackSideEffects(reason)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            // 错误只上报「当前主播放器」的：已降级的旧主在收尾清空过程中的异常若走
            // 自愈路径（跳歌/暂停），会把正在播的新主一起带走。
            if (!fromCurrentPrimary()) {
                android.util.Log.w("PlaybackEventListener", "ignored error from non-primary: $error")
                return
            }
            sink.onError(error)
        }
    }

    /**
     * 备援（standby）播放器的轻量监听器。
     *
     * 差异化设计：备援播放器不参与 UI / 切歌编排（淡入淡出由协调器统一驱动），
     * 仅需——① 音频会话变化时把音效挂到它的会话上，使交叉淡化期间两路声音都受音效影响；
     * ② 错误兜底记录日志。它不会触发 onMediaItemTransition（淡化窗口内只承载单首）。
     */
    val secondaryListener: Player.Listener = object : Player.Listener {
        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            sink.onSecondaryAudioSessionChanged(audioSessionId)
        }

        override fun onPlayerError(error: PlaybackException) {
            sink.onSecondaryError(error)
        }
    }

    /** 播放完成去重状态（迁移自原 [PlaybackManager.recordCompletionOnce]） */
    private val completionRecorder = CompletionRecorder()
}

/**
 * 转场原因分类（纯函数，可单测）。
 *
 * Media3 用 [Player.MEDIA_ITEM_TRANSITION_REASON_*] 区分「为什么切到了这一首」，
 * 不同原因对应不同的统计与行为语义：
 * - AUTO / REPEAT：自然播完续播、单曲循环回放 → 计「完整播放完成」+ 写播放历史；
 * - PLAYLIST_CHANGED 且换了歌：用户新选了一首开始播（换队列起播）→ 写播放历史但不计完成；
 * - SEEK：用户手动上/下一首 → 两者都不计（否则连点 next 会灌水统计）。
 */
internal object PlaybackEventClassifier {
    /** 自然推进：自动续播（AUTO）或单曲循环回放（REPEAT） */
    fun isNaturalAdvance(reason: Int): Boolean =
        reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
            reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT

    /**
     * 新开始：换队列后起播了不同的一首（PLAYLIST_CHANGED 且换了歌）。
     * 用户手动上/下一首（SEEK）或与当前曲相同（如重复点击）不算。
     */
    fun isFreshStart(prevId: Long?, songId: Long?, reason: Int): Boolean =
        reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED &&
            songId != null && songId != prevId
}

/**
 * 播放完成去重（纯状态机，可单测）。
 *
 * [record] 返回应上报的 songId；若落在同一首的 5s 窗口内则返回 null（跳过），
 * 防止 AUTO 过渡与 STATE_ENDED 回调针对同一首重复计一次播放完成（统计页播放次数虚高）。
 * 时钟可注入以便测试。
 */
internal class CompletionRecorder(
    private val clock: () -> Long = { SystemClock.elapsedRealtime() }
) {
    private var lastId: Long = -1L
    private var lastAt: Long = 0L

    fun record(songId: Long): Long? {
        val now = clock()
        if (songId == lastId && now - lastAt < 5_000L) return null
        lastId = songId
        lastAt = now
        return songId
    }
}

/**
 * 事件处理需要宿主执行的副作用面。
 *
 * 宿主（[PlaybackManager]）实现该接口，把「如何更新 UI / 存会话 / 重试 APE / 停服务」等
 * 它才拥有的能力回传给事件层。与 [CrossfadeCoordinator.Host] 同一手法：事件层只解释
 * 「发生了什么」，副作用留在宿主，便于事件层的纯逻辑单测。
 */
internal interface PlaybackEventSink {
    /** 主播放器音频会话变化：重挂音效会话并刷新设置 */
    fun onAudioSessionChanged(audioSessionId: Int)

    /** 备援播放器音频会话变化：同主播放器 */
    fun onSecondaryAudioSessionChanged(audioSessionId: Int)

    /** 播放态变化：刷新 UI 播放态 + 小部件 + 去抖存档 */
    fun onIsPlayingChanged(isPlaying: Boolean)

    /** 缓冲态变化：刷新 UI 缓冲标记 */
    fun onBufferingChanged(isBuffering: Boolean)

    /** 曲目切换：刷新 currentSongId / queueIndex / duration 的 UI 镜像 */
    fun onTrackChanged(songId: Long, queueIndex: Int, durationMs: Long)

    /** 写播放历史（最近播放 / 活跃天数 / 累计时长） */
    fun recordPlay(songId: Long)

    /**
     * 切歌后副作用簇：预热下一首 APE / 刷新 ReplayGain / 曲间停顿（关闭无缝时）/
     * 「播完本曲停止」到点 / 小部件刷新。顺序与改造前 onMediaItemTransition 一致。
     */
    fun onTrackSideEffects(reason: Int)

    /** 上报播放完成（驱动统计页播放次数，已去重） */
    fun recordCompletion(songId: Long)

    /** 末首自然播完的收尾：睡眠定时归零 + 立即存档 + 空闲停服（不含完成去重） */
    fun onPlaybackEnded()

    /**
     * 交叉淡化收尾（曲末自然交接完成）：补触发「播完本曲停止」的到点逻辑。
     *
     * 交叉淡入路径下，曲末交接由 [CrossfadeCoordinator] 在淡化 ramp 结束 / 淡出方 STATE_ENDED 兜底时
     * 完成，主播放器不会发出 AUTO 过渡、淡出方的 STATE_ENDED 又被「按实例丢弃残留回调」过滤
     * （见本类主监听器），于是 [onTrackSideEffects] 与 [onPlaybackEnded] 都不会到达——若不在此补触发，
     * 开启交叉淡入后「播完本曲停止」在非末位曲目上永不停止。此处即该语义的闭合点：淡化收尾 =
     * 当前曲音频已播完、已平滑交接到下一首。挂接在 [finalizeCrossfadeIfOutgoing] 之后由宿主在换主后调用。
     * [com.puretone.player.playback.SleepTimerController.finishEndOfTrack] 幂等，仅 END_OF_TRACK 模式生效。
     */
    fun onCrossfadeFinalized()

    /** 主播放器自然播到结尾的淡化兜底收尾；返回 true 表示本次确实执行了淡化收尾（本实例正是当前淡出方） */
    fun finalizeCrossfadeIfOutgoing(player: ExoPlayer): Boolean

    /** 播放器 IDLE 且队列空：空闲停服 */
    fun onIdleCleared()

    /** 主播放器错误：走 APE 重试 / 错误 UI 分支 */
    fun onError(error: PlaybackException)

    /** 备援播放器错误：仅记录日志 */
    fun onSecondaryError(error: PlaybackException)
}
