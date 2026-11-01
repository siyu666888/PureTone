package com.puretone.player.playback

import androidx.media3.common.Player
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 曲间停顿时序测试。
 *
 * 锁定的是「关闭无缝播放后，音乐不会在换曲边界永久停住」这条回归线：
 * 旧实现只 `pause()` 不 `play()`，用户听到的是「播着播着自动停了」。
 *
 * 另外覆盖三条「不该由停顿自己起播」的放行条件——它们错了会表现为
 * 「明明按了暂停，两秒后又自己响了」或「服务重建后往死播放器上发命令」。
 */
class InterTrackGapTest {

    private class FakeGapPlayer(
        override var playWhenReady: Boolean = true,
        override var mediaItemCount: Int = 3,
        override var playbackState: Int = Player.STATE_READY,
        override var currentIndex: Int = 1,
        override val token: Any = Any()
    ) : GapPlayer {
        var pauseCount = 0
        var playCount = 0

        override fun pause() {
            pauseCount++
            playWhenReady = false
        }

        override fun play() {
            playCount++
            playWhenReady = true
        }
    }

    @Test
    fun `停顿后会自动续播，而不是永久暂停`() = runTest {
        val player = FakeGapPlayer()
        val gap = InterTrackGap(scope = this, playerProvider = { player }, gapMs = 1_000L)

        gap.start()
        // 先暂停
        assertEquals(1, player.pauseCount)
        assertFalse(player.playWhenReady)
        assertTrue(gap.isPending)

        advanceTimeBy(1_001L)

        // 延时到点必须自己续播——这正是历史缺陷缺失的一步
        assertEquals(1, player.playCount)
        assertTrue(player.playWhenReady)
        assertFalse(gap.isPending)
    }

    @Test
    fun `用户暂停会作废续播意图`() = runTest {
        val player = FakeGapPlayer()
        val gap = InterTrackGap(scope = this, playerProvider = { player }, gapMs = 1_000L)

        gap.start()
        gap.cancel()
        advanceTimeBy(2_000L)

        assertEquals(0, player.playCount)
        assertFalse(player.playWhenReady)
    }

    @Test
    fun `延时期间已被播起来就不再重复起播`() = runTest {
        val player = FakeGapPlayer()
        val gap = InterTrackGap(scope = this, playerProvider = { player }, gapMs = 1_000L)

        gap.start()
        // 期间别的路径把它播起来了
        player.playWhenReady = true
        advanceTimeBy(1_001L)

        assertEquals(0, player.playCount)
    }

    @Test
    fun `队列已到末尾时不再起播`() = runTest {
        val player = FakeGapPlayer(playbackState = Player.STATE_ENDED)
        val gap = InterTrackGap(scope = this, playerProvider = { player }, gapMs = 1_000L)

        gap.start()
        advanceTimeBy(1_001L)

        assertEquals(0, player.playCount)
    }

    @Test
    fun `播放器被换掉时不对旧实例起播`() = runTest {
        val old = FakeGapPlayer()
        var current: GapPlayer? = old
        val gap = InterTrackGap(scope = this, playerProvider = { current }, gapMs = 1_000L)

        gap.start()
        // 服务重建：换成了新的播放器实例
        current = FakeGapPlayer()
        advanceTimeBy(1_001L)

        assertEquals(0, old.playCount)
    }
}
