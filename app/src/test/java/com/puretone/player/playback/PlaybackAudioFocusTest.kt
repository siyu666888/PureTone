package com.puretone.player.playback

import android.content.Context
import android.media.AudioManager
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [PlaybackAudioFocus] 焦点编排层单测（B1 焦点状态机统一 + 重申请焦点 / B3 DUCK 超时兜底）。
 *
 * 用真实 [ExoPlayer]（headless）驱动 `dispatchFocusChange` 直接走 [AudioFocusController]，
 * 不经 ShadowAudioManager；日志断言读 [PlaybackEventLog.read()]。
 *
 * B1：本地播放跳过 PAUSE（不暂停）时，若是**瞬态**丢失且播放意图仍在（playWhenReady），
 *     应立刻重新申请焦点把 GAIN 夺回；永久丢失、或已暂停时都不抢。
 * B3：DUCK 压低音量后，若 30s 内对端不放手（无 UNDUCK/GAIN），超时强制恢复满音量；
 *     UNDUCK/GAIN 会取消该超时任务。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackAudioFocusTest {

    private lateinit var player: ExoPlayer

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setup() {
        player = ExoPlayer.Builder(context).build()
    }

    @After
    fun tearDown() {
        player.release()
        PlaybackEventLog.clear()
    }

    private fun buildFocus(scheduler: TestCoroutineScheduler): PlaybackAudioFocus =
        PlaybackAudioFocus(
            context = context,
            playerProvider = { player },
            isCrossfading = { false },
            isCasting = { false },
            scope = CoroutineScope(StandardTestDispatcher(scheduler))
        )

    @Test
    fun `B1 瞬态丢失跳过暂停且播放意图仍在时重申请焦点`() {
        val scheduler = TestCoroutineScheduler()
        val focus = buildFocus(scheduler)
        player.play() // playWhenReady = true
        assertTrue(player.playWhenReady)

        focus.dispatchFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)

        val lines = PlaybackEventLog.read()
        assertTrue("应记为跳过暂停", lines.contains("audio-focus-pause-skipped"))
        assertTrue("瞬态且仍在播应重申请焦点", lines.contains("audio-focus-request"))
        assertTrue("应成功拿到焦点", lines.contains("result=GRANTED"))
        assertTrue("从不暂停，播放意图应保持", player.playWhenReady)
    }

    @Test
    fun `B1 永久丢失不重申请焦点`() {
        val scheduler = TestCoroutineScheduler()
        val focus = buildFocus(scheduler)
        player.play()

        focus.dispatchFocusChange(AudioManager.AUDIOFOCUS_LOSS)

        val lines = PlaybackEventLog.read()
        assertTrue(lines.contains("audio-focus-pause-skipped"))
        assertFalse("永久丢失不应抢焦点", lines.contains("audio-focus-request"))
    }

    @Test
    fun `B1 已暂停时瞬态丢失不重申请焦点`() {
        val scheduler = TestCoroutineScheduler()
        val focus = buildFocus(scheduler)
        player.pause() // playWhenReady = false
        assertFalse(player.playWhenReady)

        focus.dispatchFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)

        val lines = PlaybackEventLog.read()
        assertTrue(lines.contains("audio-focus-pause-skipped"))
        assertFalse("已暂停不应抢焦点", lines.contains("audio-focus-request"))
    }

    @Test
    fun `B3 DUCK 后 30s 超时自动恢复满音量`() {
        val scheduler = TestCoroutineScheduler()
        val focus = buildFocus(scheduler)

        focus.dispatchFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        assertEquals("DUCK 应压到 0.25", PlaybackAudioFocus.DUCK_VOLUME, player.volume)
        assertFalse(
            "超时前不应恢复",
            PlaybackEventLog.read().contains("audio-focus-duck-timeout")
        )

        // advanceUntilIdle 会推进虚拟时钟并执行到期任务（含 30s 延时），边界更稳。
        scheduler.advanceUntilIdle()

        assertEquals("超时后恢复满音量", 1f, player.volume)
        assertTrue(
            "应记录超时恢复",
            PlaybackEventLog.read().contains("audio-focus-duck-timeout")
        )
    }

    @Test
    fun `B3 UNDUCK 取消超时且恢复音量`() {
        val scheduler = TestCoroutineScheduler()
        val focus = buildFocus(scheduler)

        focus.dispatchFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        assertEquals(PlaybackAudioFocus.DUCK_VOLUME, player.volume)

        // GAIN 且无恢复意图 → UNDUCK：恢复满音量并取消超时
        focus.dispatchFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(1f, player.volume)

        scheduler.advanceUntilIdle()
        // 超时已被取消：音量保持 1.0，且不写 duck-timeout
        assertEquals(1f, player.volume)
        assertFalse(
            "UNDUCK 后应已取消超时",
            PlaybackEventLog.read().contains("audio-focus-duck-timeout")
        )
    }

    @Test
    fun `B3 投屏态忽略手机侧焦点事件`() {
        val scheduler = TestCoroutineScheduler()
        val focus = PlaybackAudioFocus(
            context = context,
            playerProvider = { player },
            isCrossfading = { false },
            isCasting = { true },
            scope = CoroutineScope(StandardTestDispatcher(scheduler))
        )
        focus.dispatchFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        assertTrue(PlaybackEventLog.read().contains("audio-focus-ignored"))
        // 投屏态不 duck，音量保持默认
        assertEquals(1f, player.volume)
    }
}
