package com.puretone.player.playback.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「曲中 STOPPED」判据与确认窗口的纯 JVM 锁死（2026-11-07 真机「电视画面断开」修复）。
 *
 * 重点不是数值边界，而是**不能重蹈历史三次真机故障**：
 * - `tv=8000/193000` 是曲末（渲染器把 RelTime 复位到 8 秒），只看进度会误判成曲中；
 * - `tv=185000/0`、`0/0` 是渲染器不给总时长，必须保守按曲末推进，否则队列卡死；
 * - 只有「渲染器自报进度在曲中」**且**「本地墙钟也远没播到曲末」才是真曲中停止。
 */
class CastStopPolicyTest {

    private fun classify(
        progressMs: Long,
        rendererDurationMs: Long,
        localDurationMs: Long = 0L,
        castStartMs: Long = 1_000L,
        nowMs: Long = 189_000L,
        playbackState: String = CastQueuePolicy.STATE_STOPPED
    ) = CastStopPolicy.classify(
        playbackState = playbackState,
        progressMs = progressMs,
        rendererDurationMs = rendererDurationMs,
        localDurationMs = localDurationMs,
        castStartMs = castStartMs,
        nowMs = nowMs
    )

    // ---------- 真机现场：16:55:25 progress=28000 duration=344000，墙钟已播 188s ----------

    @Test
    fun `曲中停止_渲染器与墙钟双证据成立`() {
        assertEquals(
            CastStopPolicy.Kind.MID_TRACK,
            classify(progressMs = 28_000L, rendererDurationMs = 344_000L)
        )
    }

    @Test
    fun `曲末_渲染器把进度复位到8秒但墙钟已播满`() {
        // 20:43:58 真机 tv=8000/193000：本曲实际已播约 3 分钟，STOPPED 后不该重投当前曲
        assertEquals(
            CastStopPolicy.Kind.END,
            classify(
                progressMs = 8_000L,
                rendererDurationMs = 193_000L,
                castStartMs = 1_000L,
                nowMs = 194_000L
            )
        )
    }

    @Test
    fun `曲末_进度已接近总时长`() {
        assertEquals(
            CastStopPolicy.Kind.END,
            classify(progressMs = 342_500L, rendererDurationMs = 344_000L)
        )
    }

    @Test
    fun `seek到曲末附近自然播完_偏差2199ms仍判曲末`() {
        // 214943 真机：progress=255000 / 总长 257199，偏差 2199ms 略超 2s 旧容差，
        // 曾误判曲中 → recast + seek 回原位 → 又自然播完 → 无限重投死循环；
        // 曲中容差放宽到 10s 后判曲末、推进下一首。
        assertEquals(
            CastStopPolicy.Kind.END,
            classify(
                progressMs = 255_000L,
                rendererDurationMs = 0L,
                localDurationMs = 257_199L,
                castStartMs = 1_000L,
                nowMs = 5_000L
            )
        )
    }

    @Test
    fun `距曲末15秒_仍判曲中停止`() {
        // 10s 容差只覆盖「距曲末 ≤10s」；距曲末 15s 仍是曲中（buffer 耗尽），
        // 确认放宽没有过度牺牲曲中停止识别（真机 165555 的 28s/344s 仍在曲中）。
        assertEquals(
            CastStopPolicy.Kind.MID_TRACK,
            classify(
                progressMs = 242_199L, // total 257199 - 15000
                rendererDurationMs = 0L,
                localDurationMs = 257_199L,
                castStartMs = 1_000L,
                nowMs = 15_000L
            )
        )
    }

    @Test
    fun `渲染器不给总时长时按曲末处理`() {
        // v1.9.2 真机 tv=185000/0：绝不能因为「进度像曲中」就改队列推进语义
        assertEquals(
            CastStopPolicy.Kind.END,
            classify(progressMs = 185_000L, rendererDurationMs = 0L, localDurationMs = 0L)
        )
    }

    @Test
    fun `渲染器不给总时长时用本地时长兜底且仍判曲末`() {
        assertEquals(
            CastStopPolicy.Kind.END,
            classify(progressMs = 0L, rendererDurationMs = 0L, localDurationMs = 300_000L)
        )
    }

    @Test
    fun `投送完成时刻未知时按曲末处理`() {
        assertEquals(
            CastStopPolicy.Kind.END,
            classify(progressMs = 28_000L, rendererDurationMs = 344_000L, castStartMs = 0L)
        )
    }

    @Test
    fun `时钟回拨时按曲末处理`() {
        assertEquals(
            CastStopPolicy.Kind.END,
            classify(
                progressMs = 28_000L,
                rendererDurationMs = 344_000L,
                castStartMs = 500_000L,
                nowMs = 189_000L
            )
        )
    }

    @Test
    fun `PAUSED 不参与曲中判定`() {
        assertEquals(
            CastStopPolicy.Kind.END,
            classify(
                progressMs = 28_000L,
                rendererDurationMs = 344_000L,
                playbackState = CastQueuePolicy.STATE_PAUSED
            )
        )
    }

    // ---------- 确认窗口 ----------

    @Test
    fun `窗口未到期不确认中断`() {
        assertFalse(
            CastStopPolicy.isConfirmed(
                stoppedSinceMs = 10_000L,
                nowMs = 12_000L,
                lastFetchMs = 0L,
                confirmMs = 2_500L
            )
        )
    }

    @Test
    fun `窗口到期确认中断`() {
        assertTrue(
            CastStopPolicy.isConfirmed(
                stoppedSinceMs = 10_000L,
                nowMs = 12_600L,
                lastFetchMs = 0L,
                confirmMs = 2_500L
            )
        )
    }

    @Test
    fun `STOPPED 后电视又来取流则不确认_等它重连完`() {
        // 诊断 16:55:25 http-fetch 与 own-state STOPPED 同秒：这一帧绝不能换 URI
        assertFalse(
            CastStopPolicy.isConfirmed(
                stoppedSinceMs = 10_000L,
                nowMs = 20_000L,
                lastFetchMs = 10_500L,
                confirmMs = 2_500L
            )
        )
    }
}
