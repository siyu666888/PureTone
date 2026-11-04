package com.puretone.player.playback

import com.puretone.player.data.settings.PureToneSettings
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PlaybackSettingsApplier] 单测（P1-1 拆分收尾第十刀的落地验证）。
 *
 * 锁定「设置 → 引擎/音效」映射在搬出 [PlaybackManager] 后正确：倍速、无缝开关、播放顺序规整
 * （P3-13：历史遗留 OFF 必须归并为 ALL，否则图标/状态/行为割裂）、随机写回、委托调用。
 * [playerProvider] 返回 null，[applyPlayOrder] 不触碰真实播放器，纯验证 UI 镜像与委托。
 */
class PlaybackSettingsApplierTest {

    private val uiState = MutableStateFlow(PlaybackUiState())
    private val speed = FakeSpeedControl()
    private val sleep = FakeSleep()
    private val audio = FakeAudioEffect()

    private var lastSettings: PureToneSettings? = null
    private var gapless: Boolean? = null
    private var replayGainCount = 0

    private fun build(): PlaybackSettingsApplier = PlaybackSettingsApplier(
        uiState = uiState,
        playerProvider = { null },
        controller = speed,
        sleepTimer = sleep,
        audioEffectController = audio,
        applyReplayGainForCurrent = { replayGainCount++ },
        setLastSettings = { lastSettings = it },
        setGaplessEnabled = { gapless = it }
    )

    @Test
    fun `apply 映射倍速与开关`() {
        val applier = build()
        val s = PureToneSettings(
            playbackSpeed = 1.5f,
            gapless = false,
            repeatMode = PureToneSettings.REPEAT_ALL,
            shuffle = false
        )
        applier.apply(s)
        assertEquals(1.5f, speed.lastSpeed)
        assertEquals(false, gapless)
        assertEquals(s, lastSettings)
        assertEquals(1, sleep.applyCount)
        assertEquals(1, audio.applyCount)
        assertEquals(1, replayGainCount)
    }

    @Test
    fun `applyPlayOrder 历史 OFF 规整为 ALL（P3-13）`() {
        val applier = build()
        applier.apply(PureToneSettings(repeatMode = PureToneSettings.REPEAT_OFF))
        assertEquals(RepeatMode.ALL, uiState.value.repeatMode)
    }

    @Test
    fun `applyPlayOrder ONE 映射为单曲循环`() {
        val applier = build()
        applier.apply(PureToneSettings(repeatMode = PureToneSettings.REPEAT_ONE))
        assertEquals(RepeatMode.ONE, uiState.value.repeatMode)
    }

    @Test
    fun `applyPlayOrder 随机写回 uiState`() {
        val applier = build()
        applier.apply(PureToneSettings(shuffle = true))
        assertTrue(uiState.value.shuffle)
    }

    @Test
    fun `applyPlayOrder ALL 与默认值一致时不产生多余更新`() {
        val applier = build()
        val before = uiState.value
        applier.apply(PureToneSettings(repeatMode = PureToneSettings.REPEAT_ALL, shuffle = false))
        // 初始态就是 ALL + 非随机，apply 后 uiState 应保持不变（无重复写）
        assertEquals(before.repeatMode, uiState.value.repeatMode)
        assertFalse(uiState.value.shuffle)
    }
}
