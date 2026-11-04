package com.puretone.player.playback.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 结束投屏「手机从电视当前进度接管」的边界测试。
 *
 * 这里最容易出的事故是「进度不可得却当成 0 去 seek」：手机会从头开始，
 * 比保持原位置更糟。所以每一条保守分支都要有回归用例钉住。
 *
 * [CastResumePolicy] 是纯函数，不碰播放器、不发网络请求，无需任何替身。
 */
class CastResumePolicyTest {

    private val localDuration = 300_000L

    private fun snapshot(
        isCasting: Boolean = true,
        isPreparing: Boolean = false,
        progressMs: Long = 92_000L,
        durationMs: Long = localDuration,
        hasProgressInfo: Boolean = true
    ) = CastState(
        isCasting = isCasting,
        isPreparing = isPreparing,
        progressMs = progressMs,
        durationMs = durationMs,
        hasProgressInfo = hasProgressInfo
    )

    @Test
    fun `casting with valid progress takes over renderer position`() {
        assertEquals(92_000L, CastResumePolicy.takeOverPositionMs(snapshot(), localDuration))
    }

    @Test
    fun `not casting returns null`() {
        assertNull(CastResumePolicy.takeOverPositionMs(snapshot(isCasting = false), localDuration))
    }

    @Test
    fun `transcoding or recast in flight returns null`() {
        assertNull(CastResumePolicy.takeOverPositionMs(snapshot(isPreparing = true), localDuration))
    }

    @Test
    fun `unknown progress never becomes seek to zero`() {
        // 真机：部分电视在曲末对 GetPositionInfo 返回 500，state 里可能残留脏值
        val dirty = snapshot(progressMs = 88_000L, hasProgressInfo = false)
        assertNull(CastResumePolicy.takeOverPositionMs(dirty, localDuration))
    }

    @Test
    fun `zero progress returns null`() {
        assertNull(CastResumePolicy.takeOverPositionMs(snapshot(progressMs = 0L), localDuration))
    }

    @Test
    fun `just after recast the cleared snapshot returns null`() {
        // castSong 换曲时清 progressMs/durationMs/hasProgressInfo，
        // 此时退出投屏不能把上一首的进度套到新曲上。
        val cleared = snapshot(progressMs = 0L, durationMs = 0L, hasProgressInfo = false)
        assertNull(CastResumePolicy.takeOverPositionMs(cleared, localDuration))
    }

    @Test
    fun `unknown local duration returns null`() {
        assertNull(CastResumePolicy.takeOverPositionMs(snapshot(), 0L))
    }

    @Test
    fun `too short local duration returns null`() {
        assertNull(
            CastResumePolicy.takeOverPositionMs(
                snapshot(),
                CastResumePolicy.END_GUARD_MS
            )
        )
    }

    @Test
    fun `renderer position beyond local duration is clamped before the end`() {
        val beyond = snapshot(progressMs = 400_000L)
        assertEquals(
            localDuration - CastResumePolicy.END_GUARD_MS,
            CastResumePolicy.takeOverPositionMs(beyond, localDuration)
        )
    }

    @Test
    fun `seeking to duration never triggers an immediate track change`() {
        val atEnd = snapshot(progressMs = localDuration)
        val target = CastResumePolicy.takeOverPositionMs(atEnd, localDuration)
        assertEquals(localDuration - CastResumePolicy.END_GUARD_MS, target)
    }
}
