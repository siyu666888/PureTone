package com.puretone.player.playback.cast

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 投屏播放态稳定化判据（2026-11-06 真机「投屏播着播着自己暂停」回归）。
 *
 * 真机诊断：18:24:25 `own-state PAUSED（原始 PAUSED_PLAYBACK）`、18:24:26 又
 * `PLAYING`，同秒还写入 `user-pause | 投屏态`。根因是 1Hz 轮询的瞬时 PAUSED 被
 * UI / 取反基准 / MediaSession 直接采信，命令与轮询又互相覆盖。这里逐条锁死。
 */
class CastPlayStatePolicyTest {

    private fun resolve(
        rawPlaying: Boolean,
        isPausedState: Boolean = false,
        isStoppedState: Boolean = false,
        previousStable: Boolean = true,
        commandExpected: Boolean? = null,
        guardRemainingMs: Long = 0L,
        pausedSinceMs: Long = 0L,
        nowMs: Long = 10_000L
    ) = CastPlayStatePolicy.resolve(
        rawPlaying = rawPlaying,
        isPausedState = isPausedState,
        previousStable = previousStable,
        commandExpected = commandExpected,
        guardRemainingMs = guardRemainingMs,
        pausedSinceMs = pausedSinceMs,
        nowMs = nowMs,
        isStoppedState = isStoppedState
    )

    // ---------- 命令宽限 ----------

    @Test
    fun `用户按暂停后轮询仍报 PLAYING 时保持期望的暂停态`() {
        val d = resolve(
            rawPlaying = true,
            previousStable = true,
            commandExpected = false,
            guardRemainingMs = 1_000L
        )

        assertFalse(d.isPlaying)
        assertFalse("渲染器还没确认，宽限不能提前解除", d.clearCommand)
    }

    @Test
    fun `渲染器回报与命令一致时确认并解除宽限`() {
        val d = resolve(
            rawPlaying = false,
            isPausedState = true,
            commandExpected = false,
            guardRemainingMs = 1_000L
        )

        assertFalse(d.isPlaying)
        assertTrue(d.clearCommand)
    }

    @Test
    fun `用户按播放后过渡帧的 PAUSED 不能把状态翻回暂停`() {
        val d = resolve(
            rawPlaying = false,
            isPausedState = true,
            previousStable = false,
            commandExpected = true,
            guardRemainingMs = 2_000L
        )

        assertTrue(d.isPlaying)
        assertFalse(d.clearCommand)
    }

    @Test
    fun `命令宽限过期后按渲染器真实态并清除期望态`() {
        val d = resolve(
            rawPlaying = false,
            isPausedState = true,
            previousStable = true,
            commandExpected = true,
            guardRemainingMs = 0L
        )

        assertTrue("宽限已过，PAUSED 首帧仍应先去抖", d.isPlaying)
        assertTrue(d.clearCommand)
    }

    // ---------- 渲染器自发的 PAUSED 去抖 ----------

    @Test
    fun `一帧 PAUSED 不采信避免误暂停`() {
        val d = resolve(rawPlaying = false, isPausedState = true)

        assertTrue(d.isPlaying)
        assertTrue(d.pausedSinceMs > 0L)
    }

    @Test
    fun `PAUSED 持续到确认时长才翻成暂停`() {
        val first = resolve(
            rawPlaying = false, isPausedState = true, nowMs = 10_000L
        )
        val confirmed = resolve(
            rawPlaying = false,
            isPausedState = true,
            pausedSinceMs = first.pausedSinceMs,
            nowMs = 10_000L + CastPlayStatePolicy.PAUSE_CONFIRM_MS
        )

        assertFalse(confirmed.isPlaying)
    }

    @Test
    fun `PAUSED 未达确认时长时保持上一稳定值`() {
        val d = resolve(
            rawPlaying = false,
            isPausedState = true,
            pausedSinceMs = 10_000L,
            nowMs = 10_000L + CastPlayStatePolicy.PAUSE_CONFIRM_MS - 1L
        )

        assertTrue(d.isPlaying)
    }

    @Test
    fun `去抖窗口内恢复 PLAYING 立即复位计时`() {
        val d = resolve(
            rawPlaying = true,
            previousStable = true,
            pausedSinceMs = 10_000L,
            nowMs = 10_500L
        )

        assertTrue(d.isPlaying)
        assertTrue(d.pausedSinceMs == 0L)
    }

    @Test
    fun `时钟回拨不会把 PAUSED 误判成已确认`() {
        val d = resolve(
            rawPlaying = false,
            isPausedState = true,
            pausedSinceMs = 20_000L,
            nowMs = 10_000L
        )

        assertTrue(d.isPlaying)
    }

    // ---------- 曲末与失联必须立即生效 ----------

    @Test
    fun `STOPPED 立即判定不在播不去抖`() {
        val d = resolve(rawPlaying = false, isPausedState = false)

        assertFalse(d.isPlaying)
        assertTrue(d.pausedSinceMs == 0L)
    }

    @Test
    fun `正常 PLAYING 不被上一轮暂停态拖住`() {
        val d = resolve(rawPlaying = true, previousStable = false)

        assertTrue(d.isPlaying)
    }

    // ---------- 终止态优先于命令宽限（防止曲末连跳，2026-11-06 diff 复查） ----------

    @Test
    fun `命令宽限内的 STOPPED 不能被伪造成在播`() {
        // 换曲 / Play 命令宽限里读到旧曲 STOPPED：若按 expectedPlaying=true 写成
        // 「在播」，CastQueueCoordinator.playingSeen 会被提前置位，宽限一过再读到
        // STOPPED 就被判成曲末、凭空多跳一首。
        val d = resolve(
            rawPlaying = false,
            isStoppedState = true,
            previousStable = false,
            commandExpected = true,
            guardRemainingMs = 2_000L
        )

        assertFalse(d.isPlaying)
        assertTrue(d.pausedSinceMs == 0L)
    }

    @Test
    fun `命令宽限内遇到 STOPPED 会清掉在途命令期望态`() {
        val d = resolve(
            rawPlaying = false,
            isStoppedState = true,
            commandExpected = true,
            guardRemainingMs = 1_000L
        )

        assertFalse(d.isPlaying)
        assertTrue(d.clearCommand)
    }

    @Test
    fun `终止态判据只作用于 STOPPED 不影响 PAUSED 过渡帧`() {
        // 起播缓冲的一帧 PAUSED 仍应按命令期望态忽略，否则会闪一下「已暂停」。
        val d = resolve(
            rawPlaying = false,
            isPausedState = true,
            previousStable = false,
            commandExpected = true,
            guardRemainingMs = 2_000L
        )

        assertTrue(d.isPlaying)
        assertFalse(d.clearCommand)
    }
}
