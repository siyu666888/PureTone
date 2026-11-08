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
 * v1.9.2 真机补充：STOPPED 态下电视可能对 GetPositionInfo 返回 500，
 * 此时「进度未知」是常态而非异常，判据不能再把它一票否决；
 * PAUSED（用户暂停）仍保持全部严格条件。
 *
 * v1.9.3 真机再补充（两份诊断）：STOPPED 时刻渲染器回报的 RelTime 完全不可预测
 * （185000/0、8000/193000、甚至直接 500），据此反推「是否停在曲中」必然误判。
 * 现 STOPPED 一律推进；所有「不应推进」的用例都必须在 PAUSED 态下验证。
 * PAUSED 的严格判据（含归零只在渲染器自报时长时才当播完）保持不动。
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
    fun `PAUSED 且进度远未到末尾不推进`() {
        assertFalse(shouldAdvance(playbackState = CastQueuePolicy.STATE_PAUSED, progressMs = 100_000L))
    }

    @Test
    fun `PAUSED 且进度超出容差范围不推进`() {
        assertFalse(
            shouldAdvance(
                playbackState = CastQueuePolicy.STATE_PAUSED,
                progressMs = total - CastQueuePolicy.END_TOLERANCE_MS - 1
            )
        )
    }

    @Test
    fun `STOPPED 且进度完全未知仍推进（真机 GetPositionInfo 返回 500）`() {
        // 极光TV 在曲目结束后 STOPPED，GetPositionInfo 却返回 500 → 进度/时长皆 0。
        // 旧判据在这里返回 false，导致 onCastTrackEnded 永不触发、投屏停在曲末。
        assertTrue(
            shouldAdvance(
                progressMs = 0L,
                hasProgressInfo = false,
                rendererDurationMs = 0L,
                localDurationMs = 0L
            )
        )
    }

    @Test
    fun `STOPPED 且渲染器无进度信息但本地时长可用也推进`() {
        assertTrue(
            shouldAdvance(
                progressMs = 0L,
                hasProgressInfo = false,
                rendererDurationMs = 0L,
                localDurationMs = total
            )
        )
    }

    @Test
    fun `PAUSED 且进度未知时不推进（用户暂停不能被当成播完）`() {
        assertFalse(
            shouldAdvance(
                playbackState = CastQueuePolicy.STATE_PAUSED,
                progressMs = 0L,
                hasProgressInfo = false,
                rendererDurationMs = 0L,
                localDurationMs = total
            )
        )
    }

    @Test
    fun `PAUSED 且总时长未知时不推进`() {
        assertFalse(
            shouldAdvance(
                playbackState = CastQueuePolicy.STATE_PAUSED,
                rendererDurationMs = 0L,
                localDurationMs = 0L
            )
        )
    }

    @Test
    fun `STOPPED 且进度落在曲中也推进（保守分支已移除）`() {
        // DLNA 的 STOPPED 不含「自然播完 / 用户按 Stop」的区分信息，
        // 宁可在用户按 Stop 时投下一首，也不能让播完卡死在曲末。
        assertTrue(
            shouldAdvance(
                playbackState = CastQueuePolicy.STATE_STOPPED,
                progressMs = 100_000L,
                hasProgressInfo = true,
                rendererDurationMs = total,
                localDurationMs = total
            )
        )
    }

    @Test
    fun `STOPPED 且总时长未知但已观测到播完跃迁仍推进`() {
        assertTrue(
            shouldAdvance(
                playbackState = CastQueuePolicy.STATE_STOPPED,
                rendererDurationMs = 0L,
                localDurationMs = 0L
            )
        )
    }

    // ---------- v1.9.3 真机回归：623(192.168.101.20) 只回 RelTime ----------

    @Test
    fun `STOPPED 且渲染器不给时长时中段进度也推进（真机 185000 比 0）`() {
        // 诊断原文：tv=185000/0，本地时长 207345。旧判据拿本地时长兜底算出
        // 「明确停在曲中」→ 判成用户在电视上按 Stop → 不续投、投屏卡在曲末。
        assertTrue(
            shouldAdvance(
                playbackState = CastQueuePolicy.STATE_STOPPED,
                progressMs = 185_000L,
                hasProgressInfo = true,
                rendererDurationMs = 0L,
                localDurationMs = 207_345L
            )
        )
    }

    @Test
    fun `STOPPED 且渲染器自报时长但进度复位到 8 秒仍推进（真机 20_43_58）`() {
        // 诊断原文：20:43:58 own-state STOPPED，此后 14 秒没有 cast-attempt，
        // 20:44:12 cast-resume takeOver=8000 tv=8000/193000 localDuration=193866。
        // 本曲实际已播约 3 分钟，电视却在 STOPPED 时把 RelTime 复位到 8 秒；
        // 上一版「渲染器自报时长>0 才启用曲中否决」在这里仍会误判，故彻底移除。
        assertTrue(
            shouldAdvance(
                playbackState = CastQueuePolicy.STATE_STOPPED,
                progressMs = 8_000L,
                hasProgressInfo = true,
                rendererDurationMs = 193_000L,
                localDurationMs = 193_866L
            )
        )
    }

    @Test
    fun `STOPPED 且渲染器自报时长且明确停在曲中也推进`() {
        assertTrue(
            shouldAdvance(
                playbackState = CastQueuePolicy.STATE_STOPPED,
                progressMs = 100_000L,
                hasProgressInfo = true,
                rendererDurationMs = total,
                localDurationMs = total
            )
        )
    }

    @Test
    fun `PAUSED 且渲染器自报时长并把进度归零仍判定播完`() {
        assertTrue(
            shouldAdvance(
                playbackState = CastQueuePolicy.STATE_PAUSED,
                progressMs = 0L,
                hasProgressInfo = true,
                rendererDurationMs = total,
                localDurationMs = total
            )
        )
    }

    @Test
    fun `PAUSED 且渲染器不给时长时进度归零不推进（按暂停不能跳歌）`() {
        assertFalse(
            shouldAdvance(
                playbackState = CastQueuePolicy.STATE_PAUSED,
                progressMs = 0L,
                hasProgressInfo = true,
                rendererDurationMs = 0L,
                localDurationMs = total
            )
        )
    }

    // ---------- 末首播完的收尾判据（C2，真机 20261107_184914） ----------

    private fun shouldFinish(
        wasPlaying: Boolean = true,
        isPlaying: Boolean = false,
        playbackState: String = CastQueuePolicy.STATE_STOPPED,
        hasNext: Boolean = false
    ) = CastQueuePolicy.shouldFinishQueue(
        wasPlaying = wasPlaying,
        isPlaying = isPlaying,
        playbackState = playbackState,
        hasNext = hasNext
    )

    @Test
    fun `末首STOPPED 且经历过在播跃迁时收尾`() {
        assertTrue(shouldFinish())
    }

    @Test
    fun `还有下一首时不收尾_交给自动推进`() {
        assertFalse(shouldFinish(hasNext = true))
    }

    @Test
    fun `用户中途暂停不收尾投屏`() {
        assertFalse(shouldFinish(playbackState = CastQueuePolicy.STATE_PAUSED))
    }

    @Test
    fun `仍在播放或从没播起过都不收尾`() {
        assertFalse(shouldFinish(isPlaying = true))
        assertFalse(shouldFinish(wasPlaying = false))
    }

    @Test
    fun `缓冲与错误态不收尾_交给失联回退处理`() {
        assertFalse(shouldFinish(playbackState = CastQueuePolicy.STATE_BUFFERING))
        assertFalse(shouldFinish(playbackState = CastQueuePolicy.STATE_ERROR))
    }
}
