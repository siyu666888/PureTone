package com.puretone.player.playback.cast

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 投屏「自然播完」判定的边界测试。
 *
 * 这一层是 M3 自动续播的全部风险所在：判松了会在用户按暂停时擅自跳歌，
 * 判紧了则队列卡在曲末投屏只听一首。故把每一种可能的观测组合都钉死。
 *
 * 注意 [CastQueuePolicy] 是纯函数——不碰播放器、不发网络请求、不读协程状态，
 * 因此这里无需任何替身。
 */
class CastQueuePolicyTest {

    private val total = 300_000L

    private fun shouldAdvance(
        wasPlaying: Boolean = true,
        isPlaying: Boolean = false,
        playbackState: String = CastQueuePolicy.STATE_STOPPED,
        progressMs: Long = total,
        hasProgressInfo: Boolean = true,
        rendererDurationMs: Long = total,
        localDurationMs: Long = 0L,
        hasNext: Boolean = true
    ): Boolean = CastQueuePolicy.shouldAutoAdvance(
        wasPlaying = wasPlaying,
        isPlaying = isPlaying,
        playbackState = playbackState,
        progressMs = progressMs,
        hasProgressInfo = hasProgressInfo,
        rendererDurationMs = rendererDurationMs,
        localDurationMs = localDurationMs,
        hasNext = hasNext
    )

    // ---------- 正常路径 ----------

    @Test
    fun `渲染器播完（进度到末尾且已停止）判定为可推进`() {
        assertTrue(shouldAdvance())
    }

    @Test
    fun `进度略小于总时长但在容差内仍判定为播完`() {
        // 渲染器上报的位置常略小于总时长（网络往返 + 取整）
        assertTrue(shouldAdvance(progressMs = total - CastQueuePolicy.END_TOLERANCE_MS + 1))
    }

    @Test
    fun `渲染器播完后把进度复位为 0 也判定为播完`() {
        // 不少渲染器在曲目结束时把 GetPositionInfo 归零
        assertTrue(shouldAdvance(progressMs = 0L))
    }

    @Test
    fun `渲染器上报暂停态但进度已到末尾判定为播完`() {
        // 部分渲染器在自然结束时上报 PAUSED 而非 STOPPED
        assertTrue(shouldAdvance(playbackState = CastQueuePolicy.STATE_PAUSED))
    }

    @Test
    fun `渲染器不上报时长时用本地时长兜底仍能判定`() {
        assertTrue(shouldAdvance(rendererDurationMs = 0L, localDurationMs = total))
    }

    // ---------- 必须拦住的情况 ----------

    @Test
    fun `队列没有下一首时不推进`() {
        assertFalse(shouldAdvance(hasNext = false))
    }

    @Test
    fun `从未观测到在播不算播完`() {
        // 刚投送、还在转码/缓冲，渲染器从未进入播放态
        assertFalse(shouldAdvance(wasPlaying = false))
    }

    @Test
    fun `仍在播放时不推进`() {
        assertFalse(shouldAdvance(isPlaying = true))
    }

    @Test
    fun `用户中途暂停（进度远不到末尾）不推进`() {
        assertFalse(
            shouldAdvance(
                playbackState = CastQueuePolicy.STATE_PAUSED,
                progressMs = 30_000L
            )
        )
    }

    @Test
    fun `缓冲中不推进`() {
        assertFalse(shouldAdvance(playbackState = CastQueuePolicy.STATE_BUFFERING))
    }

    @Test
    fun `出错态不推进（交由失联回退处理）`() {
        assertFalse(shouldAdvance(playbackState = CastQueuePolicy.STATE_ERROR))
    }

    @Test
    fun `进度远未到末尾不推进`() {
        assertFalse(shouldAdvance(progressMs = 100_000L))
    }

    @Test
    fun `进度超出容差范围不推进`() {
        assertFalse(shouldAdvance(progressMs = total - CastQueuePolicy.END_TOLERANCE_MS - 1))
    }

    @Test
    fun `进度未知时不推进（不得把未知当成已到末尾）`() {
        // getProgress 返回 null → 进度与时长都是 0。此时若把 0 当成「渲染器复位」
        // 就会误判播完，故必须拦住。
        assertFalse(
            shouldAdvance(
                progressMs = 0L,
                hasProgressInfo = false,
                rendererDurationMs = 0L,
                localDurationMs = 0L
            )
        )
    }

    @Test
    fun `总时长未知时不推进`() {
        assertFalse(
            shouldAdvance(
                rendererDurationMs = 0L,
                localDurationMs = 0L
            )
        )
    }

    @Test
    fun `渲染器无进度信息但本地时长可用且进度为 0 时不推进`() {
        // 进度信息不可用时不能凭「进度为 0」断言已到末尾
        assertFalse(
            shouldAdvance(
                progressMs = 0L,
                hasProgressInfo = false,
                rendererDurationMs = 0L,
                localDurationMs = total
            )
        )
    }
}
