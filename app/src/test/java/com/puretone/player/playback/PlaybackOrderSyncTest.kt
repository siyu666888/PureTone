package com.puretone.player.playback

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import com.puretone.player.data.settings.PureToneSettings
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 换主重放播放顺序的落地验证。
 *
 * 2026-11-07 07:38 现场：设置流只在 bind 后发射一次、只作用于当时的主播放器，
 * 交叉淡化换主又不重放，于是两个 ExoPlayer 实例的 repeatMode 逐首交替 ALL / OFF。
 * 本测试用两个真实实例锁死「双方同步」与「不带副作用」两条契约；
 * 真实 ExoPlayer 需要 Robolectric（与 [PlaybackEventEffectsTest] 同款做法）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackOrderSyncTest {

    private lateinit var primary: ExoPlayer
    private lateinit var standby: ExoPlayer

    private val uiState = MutableStateFlow(PlaybackUiState())
    private val speedControl = FakeSpeedControl()
    private val sleep = FakeSleep()
    private val audio = FakeAudioEffect()
    private var replayGainCount = 0
    private var lastSettings: PureToneSettings? = null
    private var gapless: Boolean? = null
    private var currentPlayer: () -> ExoPlayer? = { null }

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        primary = ExoPlayer.Builder(context).build()
        standby = ExoPlayer.Builder(context).build()
    }

    @After
    fun tearDown() {
        primary.release()
        standby.release()
    }

    @Test
    fun `换主重放把列表循环随机与倍速同步给两个实例`() {
        listOf(primary, standby).forEach {
            it.repeatMode = Player.REPEAT_MODE_OFF
            it.shuffleModeEnabled = false
            it.setPlaybackSpeed(1f)
        }
        uiState.value = uiState.value.copy(repeatMode = RepeatMode.ALL, shuffle = true, speed = 1.5f)

        val applier = applier()
        applier.applyOrderTo(primary, 1.5f)
        applier.applyOrderTo(standby, 1.5f)

        listOf(primary, standby).forEach { p ->
            assertEquals(Player.REPEAT_MODE_ALL, p.repeatMode)
            assertTrue(p.shuffleModeEnabled)
            assertEquals(1.5f, p.playbackParameters.speed)
        }
    }

    @Test
    fun `单曲循环时双方都变单曲循环且清掉随机`() {
        primary.shuffleModeEnabled = true
        standby.shuffleModeEnabled = true
        uiState.value = uiState.value.copy(repeatMode = RepeatMode.ONE, shuffle = false)

        val applier = applier()
        applier.applyOrderTo(primary, 1f)
        applier.applyOrderTo(standby, 1f)

        assertEquals(Player.REPEAT_MODE_ONE, primary.repeatMode)
        assertEquals(Player.REPEAT_MODE_ONE, standby.repeatMode)
        assertFalse(primary.shuffleModeEnabled)
        assertFalse(standby.shuffleModeEnabled)
    }

    @Test
    fun `重放不触发睡眠定时音效与 ReplayGain 副作用`() {
        applier().applyOrderTo(primary, 1f)

        assertEquals(0, sleep.applyCount)
        assertEquals(0, audio.applyCount)
        assertEquals(0, speedControl.setSpeedCalls)
        assertEquals(0, replayGainCount)
        assertNull(lastSettings)
        assertNull(gapless)
    }

    @Test
    fun `设置流仍把播放顺序与倍速落到主播放器`() {
        currentPlayer = { primary }
        primary.repeatMode = Player.REPEAT_MODE_OFF
        applier().apply(
            PureToneSettings(
                repeatMode = PureToneSettings.REPEAT_ALL,
                shuffle = true,
                playbackSpeed = 1.25f
            )
        )

        assertEquals(Player.REPEAT_MODE_ALL, primary.repeatMode)
        assertTrue(primary.shuffleModeEnabled)
        assertEquals(1.25f, primary.playbackParameters.speed)
        // 备援完全不受设置流影响——这正是换主必须显式重放的原因
        assertEquals(Player.REPEAT_MODE_OFF, standby.repeatMode)
    }

    private fun applier(): PlaybackSettingsApplier = PlaybackSettingsApplier(
        uiState = uiState,
        playerProvider = currentPlayer,
        controller = speedControl,
        sleepTimer = sleep,
        audioEffectController = audio,
        applyReplayGainForCurrent = { replayGainCount++ },
        setLastSettings = { lastSettings = it },
        setGaplessEnabled = { gapless = it }
    )
}
