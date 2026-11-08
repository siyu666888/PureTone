package com.puretone.player.playback

import android.content.Context
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import com.puretone.player.data.settings.PureToneSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
 * [PlaybackEventEffects] 单测（P1-1 拆分收尾第九刀的落地验证）。
 *
 * 锁定「事件副作用」这批逻辑在搬出 [PlaybackManager] 后仍行为正确：UI 镜像更新、音效会话重挂、
 * 连续失败归零、焦点跟随、曲间停顿（仅无缝关闭 + 自动切歌）、播完本曲停止（AUTO/REPEAT）、
 * 会话存档、空闲停服、交叉淡化收尾的恒等判定。
 *
 * [finalizeCrossfadeIfOutgoing] 需要真实的 [ExoPlayer] 作为引用载体，故整体跑在 Robolectric 下
 * （与 [PlaybackEventListenerTest] 同款做法）；其余方法都用 `playerProvider = { null }`，不触碰播放器。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackEventEffectsTest {

    private lateinit var exoPlayer: ExoPlayer

    private val uiState = MutableStateFlow(PlaybackUiState())
    private val audioEffect = FakeAudioEffect()
    private val errorHandler = FakeErrorHandler()
    private val focus = FakeFocus()
    private val widget = FakeWidget()
    private val sessionStore = FakeSessionStore()
    private val repo = FakeRepo()
    private val crossfade = FakeCrossfade()
    private val gap = FakeGap()
    private val sleep = FakeSleep()

    private var warmUpCount = 0
    private var replayGainCount = 0
    private var stopIdleCount = 0
    private val scope = CoroutineScope(UnconfinedTestDispatcher())

    private lateinit var effects: PlaybackEventEffects

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        exoPlayer = ExoPlayer.Builder(context).build()
        effects = buildEffects(gaplessEnabled = true)
    }

    @After
    fun tearDown() {
        exoPlayer.release()
    }

    @Test
    fun `onTrackChanged 更新当前曲目索引与时长`() {
        effects.onTrackChanged(songId = 7L, queueIndex = 2, durationMs = 1234L)
        assertEquals(7L, uiState.value.currentSongId)
        assertEquals(2, uiState.value.queueIndex)
        assertEquals(1234L, uiState.value.durationMs)
    }

    @Test
    fun `onBufferingChanged 更新缓冲态`() {
        effects.onBufferingChanged(true)
        assertTrue(uiState.value.isBuffering)
        effects.onBufferingChanged(false)
        assertFalse(uiState.value.isBuffering)
    }

    @Test
    fun `onIsPlayingChanged true 更新播放态并触发焦点与去抖存档`() {
        effects.onIsPlayingChanged(true)
        assertTrue(uiState.value.isPlaying)
        assertEquals(1, errorHandler.onPlayingCount)
        assertEquals(1, focus.calls)
        assertEquals(1, widget.updates)
        assertEquals(1, sessionStore.debouncedInvocations)
    }

    @Test
    fun `onIsPlayingChanged false 不再触发连续失败归零`() {
        effects.onIsPlayingChanged(true)
        effects.onIsPlayingChanged(false)
        // 连续失败归零只在「真的播起来」时调用一次
        assertEquals(1, errorHandler.onPlayingCount)
        assertFalse(uiState.value.isPlaying)
        assertEquals(2, focus.calls)
    }

    @Test
    fun `finalizeCrossfadeIfOutgoing 仅在淡化中且为淡出方时收尾`() {
        crossfade.crossfading = false
        assertFalse(effects.finalizeCrossfadeIfOutgoing(exoPlayer))
        assertEquals(0, crossfade.finalizeCount)

        crossfade.crossfading = true
        crossfade.outgoing = true
        assertTrue(effects.finalizeCrossfadeIfOutgoing(exoPlayer))
        assertEquals(1, crossfade.finalizeCount)

        // 当前状态仍 isCrossfading，但已非淡出方：幂等守卫不应再次 finalize
        crossfade.outgoing = false
        assertFalse(effects.finalizeCrossfadeIfOutgoing(exoPlayer))
        assertEquals(1, crossfade.finalizeCount)
    }

    @Test
    fun `onTrackSideEffects 无缝关闭且自动切歌时触发曲间停顿`() {
        val e = buildEffects(gaplessEnabled = false)
        e.onTrackSideEffects(Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        assertEquals(1, gap.startCount)
        assertEquals(1, sleep.finishCount)
        assertEquals(1, warmUpCount)
        assertEquals(1, replayGainCount)
        assertEquals(1, widget.updates)
    }

    @Test
    fun `onTrackSideEffects 无缝开启时不触发曲间停顿`() {
        effects.onTrackSideEffects(Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        assertEquals(0, gap.startCount)
        assertEquals(1, sleep.finishCount)
    }

    @Test
    fun `onTrackSideEffects REPEAT 也触发播完本曲停止`() {
        effects.onTrackSideEffects(Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)
        assertEquals(1, sleep.finishCount)
        assertEquals(0, gap.startCount)
    }

    @Test
    fun `onTrackSideEffects 手动切歌不触发收尾与停顿`() {
        val before = sleep.finishCount
        effects.onTrackSideEffects(Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
        assertEquals(before, sleep.finishCount)
        assertEquals(0, gap.startCount)
    }

    @Test
    fun `onPlaybackEnded 末首播完存档并停服`() {
        uiState.value = uiState.value.copy(
            queue = listOf(QueueEntry(1L, "t", "a", null, 1000L, false, "p")),
            queueIndex = 0
        )
        effects.onPlaybackEnded()
        assertEquals(1, sleep.finishCount)
        assertEquals(1, sessionStore.saveNowCount)
        assertEquals(1, stopIdleCount)
    }

    @Test
    fun `onPlaybackEnded 队列为空时不存档`() {
        // 默认 uiState.queue 为空
        effects.onPlaybackEnded()
        assertEquals(0, sessionStore.saveNowCount)
        assertEquals(1, stopIdleCount)
    }

    @Test
    fun `onIdleCleared 只停服不存档`() {
        effects.onIdleCleared()
        assertEquals(1, stopIdleCount)
        assertEquals(0, sessionStore.saveNowCount)
    }

    @Test
    fun `onPlaybackEnded 循环模式异常结束时回绕续播并跳过停服`() {
        var rewindCalled = false
        // isUnexpected 要求 playWhenReady=true：这里让 playerProvider 返回一个「播放意图为 true」的播放器。
        exoPlayer.play()
        val e = buildEffects(gaplessEnabled = true, rewindToStart = { rewindCalled = true; true }) { exoPlayer }
        uiState.value = uiState.value.copy(
            repeatMode = RepeatMode.ALL,
            queue = listOf(QueueEntry(1L, "t", "a", null, 1000L, false, "p")),
            queueIndex = 0
        )
        e.onPlaybackEnded()
        // 命中 isUnexpected → 触发回绕，且因为已续播而跳过空闲停服
        assertTrue(rewindCalled)
        assertEquals(0, stopIdleCount)
        assertEquals(1, sessionStore.saveNowCount)
    }

    @Test
    fun `onPlaybackEnded 回绕失败仍按原逻辑停服`() {
        var rewindCalled = false
        exoPlayer.play()
        val e = buildEffects(gaplessEnabled = true, rewindToStart = { rewindCalled = true; false }) { exoPlayer }
        uiState.value = uiState.value.copy(
            repeatMode = RepeatMode.ONE,
            queue = listOf(QueueEntry(2L, "t", "a", null, 1000L, false, "p")),
            queueIndex = 0
        )
        e.onPlaybackEnded()
        assertTrue(rewindCalled)
        // 回绕失败（返回 false）→ 不跳过停服
        assertEquals(1, stopIdleCount)
    }

    @Test
    fun `onCrossfadeFinalized 委托触发播完本曲停止`() {
        // 交叉淡入收尾 = 当前曲音频已播完并平滑交接到下一首，此处必须补触发 END_OF_TRACK 的到点逻辑
        // （非淡化路径由 onTrackSideEffects/onPlaybackEnded 触发，淡化路径被事件层按实例丢弃过滤，不会到达）。
        val before = sleep.finishCount
        effects.onCrossfadeFinalized()
        assertEquals(before + 1, sleep.finishCount)
    }

    @Test
    fun `recordPlay 经 scope 落到仓库`() {
        effects.recordPlay(5L)
        assertEquals(listOf(5L), repo.plays)
    }

    @Test
    fun `recordCompletion 经 scope 落到仓库`() {
        effects.recordCompletion(6L)
        assertEquals(listOf(6L), repo.completions)
    }

    @Test
    fun `onError 转交错误处理器`() {
        val err = PlaybackException("boom", null, 0)
        effects.onError(err)
        assertEquals(err, errorHandler.handled)
    }

    @Test
    fun `onAudioSessionChanged 重挂音效会话并回灌设置`() {
        effects.onAudioSessionChanged(42)
        assertEquals(1, audioEffect.attachCount)
        assertEquals(1, audioEffect.applyCount)
    }

    @Test
    fun `onSecondaryAudioSessionChanged 同样重挂副会话音效`() {
        effects.onSecondaryAudioSessionChanged(99)
        assertEquals(1, audioEffect.attachCount)
        assertEquals(1, audioEffect.applyCount)
    }

    @Test
    fun `onSecondaryError 仅记录不崩溃`() {
        effects.onSecondaryError(PlaybackException("x", null, 0))
    }

    private fun buildEffects(
        gaplessEnabled: Boolean,
        rewindToStart: () -> Boolean = { false },
        playerProvider: () -> ExoPlayer? = { null }
    ): PlaybackEventEffects = PlaybackEventEffects(
        playerProvider = playerProvider,
        uiState = uiState,
        audioEffectController = audioEffect,
        lastSettingsProvider = { PureToneSettings() },
        errorHandler = errorHandler,
        playbackFocus = focus,
        widgetUpdater = widget,
        sessionStore = sessionStore,
        scope = scope,
        musicRepository = repo,
        crossfade = crossfade,
        interTrackGap = gap,
        sleepTimer = sleep,
        gaplessEnabledProvider = { gaplessEnabled },
        warmUpFollowingApe = { warmUpCount++ },
        applyReplayGainForCurrent = { replayGainCount++ },
        stopServiceIfIdle = { stopIdleCount++ },
        rewindToStart = rewindToStart
    )
}
