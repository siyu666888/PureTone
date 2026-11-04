package com.puretone.player.playback.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 投屏音量档位的纯规则回归：连按不丢档、初值只校准一次、能力只判一次。 */
class CastVolumePolicyTest {

    @Test
    fun `relative adjustment uses pending request instead of stale state`() {
        // 连按两次：状态还停在 15，但已受理的请求是 20 → 下一次应到 25 而不是 20
        assertEquals(25, CastVolumePolicy.resolveRelative(pending = 20, fallback = 15, delta = 5))
    }

    @Test
    fun `relative adjustment falls back to state when nothing is pending`() {
        assertEquals(20, CastVolumePolicy.resolveRelative(pending = null, fallback = 15, delta = 5))
    }

    @Test
    fun `relative adjustment is clamped to the 0 to 100 range`() {
        assertEquals(100, CastVolumePolicy.resolveRelative(pending = 98, fallback = 15, delta = 5))
        assertEquals(0, CastVolumePolicy.resolveRelative(pending = 2, fallback = 15, delta = -5))
    }

    @Test
    fun `initial volume is applied only once per session`() {
        assertTrue(CastVolumePolicy.shouldApplyInitialVolume(touchedByUser = false, currentVolume = 100))
        assertFalse(CastVolumePolicy.shouldApplyInitialVolume(touchedByUser = false, currentVolume = 15))
    }

    @Test
    fun `user adjusted volume is never overwritten by device readback`() {
        assertFalse(CastVolumePolicy.shouldApplyInitialVolume(touchedByUser = true, currentVolume = 100))
        assertFalse(CastVolumePolicy.shouldApplyInitialVolume(touchedByUser = true, currentVolume = 15))
    }

    @Test
    fun `support verdict is written only while unknown`() {
        assertTrue(CastVolumePolicy.shouldSetSupport(null))
        assertFalse(CastVolumePolicy.shouldSetSupport(true))
        assertFalse(CastVolumePolicy.shouldSetSupport(false))
    }
}
