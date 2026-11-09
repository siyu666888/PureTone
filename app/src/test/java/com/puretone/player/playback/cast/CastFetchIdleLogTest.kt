package com.puretone.player.playback.cast

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 取流空窗诊断（R7 / 054751）的纯 JVM 锁死。
 *
 * 关键约束：诊断日志不能刷屏（1Hz 轮询 × 一首 4 分钟的歌），也不能因为
 * 换曲后档位没复位而整首不记录。logger 用注入的假实现，不碰 Android Log。
 */
class CastFetchIdleLogTest {

    private class Recorder {
        val lines = mutableListOf<String>()
        val log: (String) -> Unit = { lines.add(it) }
    }

    @Test
    fun `未建立时每跨一个档位只打一条`() {
        val r = Recorder()
        val subject = CastFetchIdleLog(logger = r.log)
        // 本轮从未取流，以投送时刻为基准
        assertEquals(4_000L, subject.sample(nowMs = 14_000L, fetchMs = 0L, castStartMs = 10_000L, established = false))
        assertEquals(0, r.lines.size)
        assertEquals(5_000L, subject.sample(nowMs = 15_000L, fetchMs = 0L, castStartMs = 10_000L, established = false))
        assertEquals(1, r.lines.size)
        assertEquals("cast-fetch-idle ms=5000 established=false", r.lines[0])
        // 同一档位内连续轮询不再写
        repeat(4) { subject.sample(nowMs = 19_000L, fetchMs = 0L, castStartMs = 10_000L, established = false) }
        assertEquals(1, r.lines.size)
        // 跨到下一档再写一条
        subject.sample(nowMs = 20_000L, fetchMs = 0L, castStartMs = 10_000L, established = false)
        assertEquals(2, r.lines.size)
        assertEquals("cast-fetch-idle ms=10000 established=false", r.lines[1])
    }

    @Test
    fun `已建立时不再按档位采样`() {
        val r = Recorder()
        val subject = CastFetchIdleLog(logger = r.log)
        subject.sample(nowMs = 40_000L, fetchMs = 0L, castStartMs = 10_000L, established = true)
        subject.sample(nowMs = 60_000L, fetchMs = 0L, castStartMs = 10_000L, established = true)
        assertEquals(0, r.lines.size)
    }

    @Test
    fun `取流后空窗以最近一次取流为基准`() {
        val r = Recorder()
        val subject = CastFetchIdleLog(logger = r.log)
        // 12s 取过流，17s 时空窗 5s（不是距投送的 7s）
        assertEquals(
            5_000L,
            subject.sample(nowMs = 17_000L, fetchMs = 12_000L, castStartMs = 10_000L, established = false)
        )
    }

    @Test
    fun `换曲复位后新曲重新采样`() {
        val r = Recorder()
        val subject = CastFetchIdleLog(logger = r.log)
        subject.sample(nowMs = 70_000L, fetchMs = 0L, castStartMs = 10_000L, established = false)
        val before = r.lines.size
        subject.reset()
        // 新曲：castStartMs 推后、lastSuccessfulFetchMs 归零，档位也从 0 重算。
        // 未复位时上一首的 step=12 会压住整首新曲，一条都不打。
        subject.sample(nowMs = 74_000L, fetchMs = 0L, castStartMs = 70_000L, established = false)
        assertEquals(before, r.lines.size)
        subject.sample(nowMs = 75_000L, fetchMs = 0L, castStartMs = 70_000L, established = false)
        assertEquals(before + 1, r.lines.size)
        assertEquals("cast-fetch-idle ms=5000 established=false", r.lines.last())
        subject.sample(nowMs = 80_000L, fetchMs = 0L, castStartMs = 70_000L, established = false)
        assertEquals(before + 2, r.lines.size)
        assertEquals("cast-fetch-idle ms=10000 established=false", r.lines.last())
    }

    @Test
    fun `时钟回拨不产生负空窗`() {
        val r = Recorder()
        val subject = CastFetchIdleLog(logger = r.log)
        assertEquals(
            0L,
            subject.sample(nowMs = 9_000L, fetchMs = 0L, castStartMs = 10_000L, established = false)
        )
        assertEquals(0, r.lines.size)
    }

    @Test
    fun `刚进入PAUSED时记一条且持续PAUSED不重复`() {
        val r = Recorder()
        val subject = CastFetchIdleLog(logger = r.log)
        subject.onState(paused = false, idleMs = 1_000L)
        subject.onState(paused = true, idleMs = 31_000L)
        assertEquals(1, r.lines.size)
        assertEquals("cast-fetch-idle ms=31000 state=PAUSED", r.lines[0])
        // 连续 PAUSED 帧不刷屏；恢复 PLAYING 后再次进入 PAUSED 才算新跳变
        subject.onState(paused = true, idleMs = 32_000L)
        assertEquals(1, r.lines.size)
        subject.onState(paused = false, idleMs = 33_000L)
        subject.onState(paused = true, idleMs = 34_000L)
        assertEquals(2, r.lines.size)
        assertEquals("cast-fetch-idle ms=34000 state=PAUSED", r.lines.last())
    }

    @Test
    fun `采样档位为5秒`() {
        assertEquals(5_000L, CastFetchIdleLog.STEP_MS)
    }
}
