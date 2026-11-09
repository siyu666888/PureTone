package com.puretone.player.playback.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 请求级取流取证（R7）的纯 JVM 锁死。
 *
 * 三条设计判据：① **不受 `http-fetch` 8 条上限影响**（累计请求数一直在涨）；
 * ② 换曲复位后上一首的请求不串进新曲；③ 3 个 HTTP worker 并发写不丢计数。
 */
class CastFetchTraceTest {

    @Test
    fun `从未取流时快照全为零`() {
        assertEquals("requests=0 bytes=0 ages=[] err=-", CastFetchTrace().snapshot(10_000L))
    }

    @Test
    fun `请求到达按由近到远记录ages`() {
        val subject = CastFetchTrace()
        subject.noteRequest(1_000L)
        subject.noteRequest(3_000L)
        subject.noteRequest(9_000L)
        assertEquals(
            "requests=3 bytes=0 ages=[1000,7000,9000] err=-",
            subject.snapshot(10_000L)
        )
    }

    @Test
    fun `环形容量只保留最近N次但累计请求数不丢`() {
        val subject = CastFetchTrace(capacity = 3)
        (1..5).forEach { subject.noteRequest(it * 1_000L) }
        assertEquals(
            "requests=5 bytes=0 ages=[1000,2000,3000] err=-",
            subject.snapshot(6_000L)
        )
    }

    @Test
    fun `写出字节累计且忽略非正数`() {
        val subject = CastFetchTrace()
        subject.noteWritten(0L)
        subject.noteWritten(-5L)
        subject.noteWritten(100L)
        subject.noteWritten(23L)
        assertEquals(
            "requests=0 bytes=123 ages=[] err=-",
            subject.snapshot(0L)
        )
    }

    @Test
    fun `写失败原因进入快照且超长会被截断`() {
        val subject = CastFetchTrace()
        subject.noteError("IOException:broken pipe")
        assertEquals(
            "requests=0 bytes=0 ages=[] err=IOException:broken pipe",
            subject.snapshot(0L)
        )
        subject.noteError("E".repeat(300))
        assertTrue(subject.snapshot(0L).endsWith("E".repeat(120)))
    }

    @Test
    fun `时钟回拨不产生负空窗`() {
        val subject = CastFetchTrace()
        subject.noteRequest(10_000L)
        assertEquals("requests=1 bytes=0 ages=[0] err=-", subject.snapshot(1_000L))
    }

    @Test
    fun `换曲复位清空请求字节与异常`() {
        val subject = CastFetchTrace()
        subject.noteRequest(1_000L)
        subject.noteWritten(4_096L)
        subject.noteError("boom")
        subject.reset()
        assertEquals("requests=0 bytes=0 ages=[] err=-", subject.snapshot(99_999L))
    }

    @Test
    fun `并发写不丢累计请求数`() {
        val subject = CastFetchTrace()
        val pool = Executors.newFixedThreadPool(4)
        val done = CountDownLatch(4)
        repeat(4) {
            pool.execute {
                repeat(50) { subject.noteRequest(1L) }
                done.countDown()
            }
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        pool.shutdown()
        assertTrue(subject.snapshot(1L).startsWith("requests=200 "))
    }
}
