package com.puretone.player.playback.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 投屏展示进度的兜底判据（v1.9.3 真机回归）。
 *
 * 真机 623(192.168.101.20) 的 `GetPositionInfo` 是 `185000/0`：只给 RelTime、
 * 不给 TrackDuration。若把 0 当总时长，投屏遥控页会判「进度未知」把进度条禁用；
 * 若外推锚点在拖动窗口后仍拿旧值算，进度条又会瞬间跳到没播到的位置。
 * 这里把两条兜底的每一档都钉死。
 */
class CastProgressPolicyTest {

    private val renderer = 195_000L
    private val local = 207_345L
    private val entry = 207_000L

    @Test
    fun `渲染器自报时长且允许镜像时优先用渲染器时间轴`() {
        assertEquals(
            renderer,
            CastProgressPolicy.displayDurationMs(renderer, true, local, entry)
        )
    }

    @Test
    fun `拖动乐观窗口内不镜像渲染器时长避免拖动中总时长突变`() {
        assertEquals(
            local,
            CastProgressPolicy.displayDurationMs(renderer, false, local, entry)
        )
    }

    @Test
    fun `渲染器不报时长时退到本地播放器已知时长`() {
        assertEquals(
            local,
            CastProgressPolicy.displayDurationMs(0L, true, local, entry)
        )
    }

    @Test
    fun `冷启动直接投屏本地未起播时退到队列条目时长`() {
        assertEquals(
            entry,
            CastProgressPolicy.displayDurationMs(0L, true, 0L, entry)
        )
    }

    @Test
    fun `三处都没有时长时返回 0 表示确实未知`() {
        assertEquals(0L, CastProgressPolicy.displayDurationMs(0L, true, 0L, null))
    }

    @Test
    fun `渲染器上报负数时长不采信`() {
        assertEquals(
            local,
            CastProgressPolicy.displayDurationMs(-1L, true, local, entry)
        )
    }

    @Test
    fun `外推上限优先用渲染器时长`() {
        assertEquals(renderer, CastProgressPolicy.projectionCapMs(renderer, local))
    }

    @Test
    fun `渲染器不给时长时外推上限用本地时长`() {
        assertEquals(local, CastProgressPolicy.projectionCapMs(0L, local))
    }

    @Test
    fun `时长全未知时外推不封顶`() {
        assertEquals(Long.MAX_VALUE, CastProgressPolicy.projectionCapMs(0L, 0L))
    }

    @Test
    fun `锚点在 1Hz 轮询间隔内视为新鲜`() {
        assertTrue(CastProgressPolicy.isAnchorFresh(1L, 1_500L))
        assertTrue(CastProgressPolicy.isAnchorFresh(1_500L, 1_500L))
    }

    @Test
    fun `锚点过旧或未初始化时不外推`() {
        assertFalse(CastProgressPolicy.isAnchorFresh(0L, 1_500L))
        assertFalse(CastProgressPolicy.isAnchorFresh(-1L, 1_500L))
        assertFalse(CastProgressPolicy.isAnchorFresh(1_501L, 1_500L))
    }

    // ---------- 轮询静默期的连续外推（2026-11-06 真机回归） ----------

    @Test
    fun `轮询间隔内按墙钟线性外推`() {
        assertEquals(
            112_750L,
            CastProgressPolicy.projectedPositionMs(112_000L, 750L, local)
        )
    }

    @Test
    fun `轮询静默两分钟仍继续外推而不是冻结在最后一次成功值`() {
        // 真机 18:26:36 own-poll-null quiet=126252ms，旧实现在锚点超过 1.5s 后
        // 直接停写，手机进度钉死在 112s，电视却已播到约 155s。
        assertEquals(
            238_000L,
            CastProgressPolicy.projectedPositionMs(112_000L, 126_000L, 300_000L)
        )
    }

    @Test
    fun `静默外推钳到总时长不会越过曲目末尾`() {
        assertEquals(
            local,
            CastProgressPolicy.projectedPositionMs(112_000L, 600_000L, local)
        )
    }

    @Test
    fun `锚点未初始化或时钟回拨时本帧不写`() {
        assertEquals(null, CastProgressPolicy.projectedPositionMs(112_000L, 0L, local))
        assertEquals(null, CastProgressPolicy.projectedPositionMs(112_000L, -1L, local))
    }

    @Test
    fun `负锚点外推结果不会小于零`() {
        assertEquals(0L, CastProgressPolicy.projectedPositionMs(-5_000L, 250L, local))
    }

    @Test
    fun `停写窗口结束后重锚不会把停写时长累计进进度`() {
        // 暂停 / 拖动 / 非投屏期间投影停写，但锚点墙钟仍在走。恢复时若沿用旧锚点，
        // 10 秒暂停会一次外推出 122000（甚至钳到曲末）；正确做法是以当前展示位置
        // 112000 重新锚定，恢复后第一帧只推进 250ms。
        val staleProjection = CastProgressPolicy.projectedPositionMs(112_000L, 10_250L, local)
        assertEquals(122_250L, staleProjection)
        val reanchored = CastProgressPolicy.projectedPositionMs(
            /* 恢复时以展示位置重锚 */ 112_000L, 250L, local
        )
        assertEquals(112_250L, reanchored)
    }
}
