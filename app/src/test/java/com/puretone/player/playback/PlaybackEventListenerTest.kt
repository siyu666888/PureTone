package com.puretone.player.playback

import android.content.Context
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [PlaybackEventListener] 在交叉淡化边界的「播放结束」语义单测（覆盖 v1.8.9 修复）。
 *
 * 核心契约：当某一路播放器的 STATE_ENDED 被识别为「淡化兜底收尾」（即它正是当前淡出方、
 * 收尾后下一首仍在播）时，**不应**走 [PlaybackEventSink.onPlaybackEnded] 的「播放结束」语义
 * ——否则会误消费「播完本曲停止」并 pause 正在播的下一首。
 *
 * 用 Robolectric 提供 Android 运行时以构造真实 [ExoPlayer]（仅作类型载体与引用恒等比较，
 * 不实际播放；回调直接手动触发）。[PlaybackEventSink] 用 [FakeSink] 替身，控制
 * [PlaybackEventSink.finalizeCrossfadeIfOutgoing] 的返回值并观察 [PlaybackEventSink.onPlaybackEnded]。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackEventListenerTest {

    private lateinit var context: Context
    private lateinit var exoPlayer: ExoPlayer

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        exoPlayer = ExoPlayer.Builder(context).build()
    }

    @After
    fun tearDown() {
        exoPlayer.release()
    }

    private fun buildListener(finalizeReturns: Boolean): Pair<Player.Listener, FakeSink> {
        val sink = FakeSink(finalizeReturns)
        val listener = PlaybackEventListener(
            playerProvider = { exoPlayer },
            currentSongIdProvider = { 1L },
            sink = sink
        ).primaryListenerFor(exoPlayer)
        return listener to sink
    }

    @Test
    fun `淡出兜底收尾时不触发 onPlaybackEnded（下一首仍在播，语义A）`() {
        val (listener, sink) = buildListener(finalizeReturns = true)
        listener.onPlaybackStateChanged(Player.STATE_ENDED)
        assertFalse("淡出收尾不应走播放结束语义", sink.onPlaybackEndedCalled)
        assertTrue("统计完成记录仍应发生", sink.recordCompletionCalled)
    }

    @Test
    fun `普通末首播完仍触发 onPlaybackEnded`() {
        val (listener, sink) = buildListener(finalizeReturns = false)
        listener.onPlaybackStateChanged(Player.STATE_ENDED)
        assertTrue("普通末首播完应走播放结束语义", sink.onPlaybackEndedCalled)
        assertTrue("统计完成记录应发生", sink.recordCompletionCalled)
    }

    @Test
    fun `非当前主的迟到回调被丢弃，不触发 onPlaybackEnded`() {
        // 构造一个与 playerProvider 不同的 ExoPlayer，模拟「角色交换后残留的旧主回调」
        val stale = ExoPlayer.Builder(context).build()
        val sink = FakeSink(finalizeReturns = false)
        val listener = PlaybackEventListener(
            playerProvider = { exoPlayer },
            currentSongIdProvider = { 1L },
            sink = sink
        ).primaryListenerFor(stale)
        listener.onPlaybackStateChanged(Player.STATE_ENDED)
        assertFalse("非当前主的回调必须被丢弃", sink.onPlaybackEndedCalled)
        assertFalse("非当前主的回调不得记录完成", sink.recordCompletionCalled)
        stale.release()
    }

    /** [PlaybackEventSink] 替身：仅观察关键副作用，其余空实现 */
    private class FakeSink(private val finalizeReturns: Boolean) : PlaybackEventSink {
        var onPlaybackEndedCalled = false
        var recordCompletionCalled = false

        override fun onAudioSessionChanged(audioSessionId: Int) = Unit
        override fun onSecondaryAudioSessionChanged(audioSessionId: Int) = Unit
        override fun onIsPlayingChanged(isPlaying: Boolean) = Unit
        override fun onBufferingChanged(isBuffering: Boolean) = Unit
        override fun onTrackChanged(songId: Long, queueIndex: Int, durationMs: Long) = Unit
        override fun recordPlay(songId: Long) = Unit
        override fun onTrackSideEffects(reason: Int) = Unit
        override fun recordCompletion(songId: Long) {
            recordCompletionCalled = true
        }
        override fun onPlaybackEnded() {
            onPlaybackEndedCalled = true
        }
        override fun finalizeCrossfadeIfOutgoing(player: ExoPlayer): Boolean = finalizeReturns
        override fun onIdleCleared() = Unit
        override fun onError(error: PlaybackException) = Unit
        override fun onSecondaryError(error: PlaybackException) = Unit
    }
}
