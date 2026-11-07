package com.puretone.player.playback

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [PlaybackOrderState] 纯映射单测。
 *
 * 锁死 UI 三态与 Media3 repeatMode 的对应关系：换主重放若把 ALL 误写成 OFF，
 * 队列末尾就会 STATE_ENDED 停成暂停态（2026-11-07 07:38 现场）。
 */
class PlaybackOrderStateTest {

    @Test
    fun `OFF 映射为不循环`() {
        assertEquals(Player.REPEAT_MODE_OFF, PlaybackOrderState.playerRepeatModeOf(RepeatMode.OFF))
    }

    @Test
    fun `ALL 映射为列表循环`() {
        assertEquals(Player.REPEAT_MODE_ALL, PlaybackOrderState.playerRepeatModeOf(RepeatMode.ALL))
    }

    @Test
    fun `ONE 映射为单曲循环`() {
        assertEquals(Player.REPEAT_MODE_ONE, PlaybackOrderState.playerRepeatModeOf(RepeatMode.ONE))
    }

    @Test
    fun `倍速上下界钳制与设置入口一致`() {
        assertEquals(0.25f, PlaybackOrderState.normalizedSpeed(0.01f))
        assertEquals(3.0f, PlaybackOrderState.normalizedSpeed(9.0f))
    }

    @Test
    fun `合法倍速原样保留`() {
        assertEquals(1.5f, PlaybackOrderState.normalizedSpeed(1.5f))
    }
}
