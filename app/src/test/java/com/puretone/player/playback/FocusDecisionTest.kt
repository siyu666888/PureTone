package com.puretone.player.playback

import android.media.AudioManager
import com.puretone.player.playback.AudioFocusController.Command
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 焦点变化 → 播放命令的语义锁定。
 *
 * 这是「接完电话音乐不回来 / 没打电话却自己响起来」两类缺陷的唯一源头：
 * 只有**瞬态**丢失才允许拿到 GAIN 后自动续播，永久丢失必须老实待着。
 */
class FocusDecisionTest {

    @Test
    fun `永久丢失：暂停且不自动恢复`() {
        val next = FocusDecision.onChange(
            AudioManager.AUDIOFOCUS_LOSS,
            holding = true,
            resumeOnGain = true
        )

        assertEquals(Command.PAUSE, next.command)
        assertFalse(next.holding)
        assertFalse("永久丢失不能留恢复意图", next.resumeOnGain)
    }

    @Test
    fun `瞬态丢失：暂停但记住恢复意图`() {
        val next = FocusDecision.onChange(
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            holding = true,
            resumeOnGain = false
        )

        assertEquals(Command.PAUSE, next.command)
        assertFalse(next.holding)
        assertTrue(next.resumeOnGain)
    }

    @Test
    fun `可 duck 的丢失：只压低音量，不暂停`() {
        val next = FocusDecision.onChange(
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
            holding = true,
            resumeOnGain = false
        )

        assertEquals(Command.DUCK, next.command)
        assertTrue("duck 期间仍持有焦点", next.holding)
        assertFalse(next.resumeOnGain)
    }

    @Test
    fun `拿到焦点且有恢复意图：续播并消费掉意图`() {
        val next = FocusDecision.onChange(
            AudioManager.AUDIOFOCUS_GAIN,
            holding = false,
            resumeOnGain = true
        )

        assertEquals(Command.RESUME, next.command)
        assertTrue(next.holding)
        assertFalse("意图只能消费一次，否则下一次 GAIN 会自己响起来", next.resumeOnGain)
    }

    @Test
    fun `拿到焦点但没有恢复意图：只恢复音量`() {
        val next = FocusDecision.onChange(
            AudioManager.AUDIOFOCUS_GAIN,
            holding = false,
            resumeOnGain = false
        )

        assertEquals(Command.UNDUCK, next.command)
        assertTrue(next.holding)
    }

    @Test
    fun `未知变化：状态不变也不下命令`() {
        val next = FocusDecision.onChange(change = 12345, holding = true, resumeOnGain = false)

        assertNull(next.command)
        assertTrue(next.holding)
        assertFalse(next.resumeOnGain)
    }
}
