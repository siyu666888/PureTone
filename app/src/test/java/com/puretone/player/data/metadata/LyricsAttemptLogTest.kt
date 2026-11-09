package com.puretone.player.data.metadata

import com.puretone.player.data.metadata.MultiChannelLyricsProvider.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * [LyricsAttemptLog] 单测（P3-3）。
 *
 * 锁死三件事：初始/重置为空、一次 record 原子发布「命中 + 轨迹」、
 * 轨迹做防御性拷贝；并发用例验证读到的永远是一份配套快照，不会出现
 * 「A 次命中 + B 次轨迹」的错配。
 */
class LyricsAttemptLogTest {

    @Test
    fun `初始快照为空`() {
        val log = LyricsAttemptLog()
        assertNull(log.hit)
        assertTrue(log.attempted.isEmpty())
        assertEquals(LyricsAttemptLog.Snapshot(), log.snapshot())
    }

    @Test
    fun `一次 record 同时发布命中与轨迹`() {
        val log = LyricsAttemptLog()
        val attempted = listOf(Channel.SIDECAR_LRC, Channel.EMBEDDED, Channel.AMLL)

        log.record(Channel.AMLL, attempted)

        assertEquals(Channel.AMLL, log.hit)
        assertEquals(attempted, log.attempted)
        assertEquals(LyricsAttemptLog.Snapshot(Channel.AMLL, attempted), log.snapshot())
    }

    @Test
    fun `轨迹做防御性拷贝 后续修改可变列表不污染快照`() {
        val log = LyricsAttemptLog()
        val mutable = mutableListOf(Channel.AMLL)

        log.record(Channel.AMLL, mutable)
        mutable.add(Channel.LRCLIB_EXACT)
        mutable.clear()

        assertEquals(listOf(Channel.AMLL), log.attempted)
        assertEquals(Channel.AMLL, log.hit)
    }

    @Test
    fun `reset 清空命中与轨迹`() {
        val log = LyricsAttemptLog()
        log.record(Channel.EMBEDDED, listOf(Channel.SIDECAR_LRC, Channel.EMBEDDED))

        log.reset()

        assertNull(log.hit)
        assertTrue(log.attempted.isEmpty())
    }

    @Test
    fun `失败取词记录 null 命中与完整轨迹`() {
        val log = LyricsAttemptLog()
        val attempted = listOf(
            Channel.SIDECAR_LRC,
            Channel.EMBEDDED,
            Channel.AMLL,
            Channel.LRCLIB_EXACT,
            Channel.LRCLIB_SEARCH,
        )

        log.record(null, attempted)

        assertNull(log.snapshot().hit)
        assertEquals(attempted, log.snapshot().attempted)
    }

    @Test
    fun `并发写入下读到的快照始终配套`() {
        val log = LyricsAttemptLog()
        val pool = Executors.newFixedThreadPool(3)
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val rounds = 2_000
        val failure = StringBuilder()

        listOf(Channel.AMLL, Channel.LRCLIB_EXACT).forEach { channel ->
            pool.submit {
                start.await()
                repeat(rounds) { log.record(channel, listOf(channel)) }
                done.countDown()
            }
        }
        pool.submit {
            start.await()
            repeat(rounds) {
                val snapshot = log.snapshot()
                val only = snapshot.attempted.singleOrNull()
                // 允许「刚 reset 的空快照」，但不允许命中与轨迹来自不同轮
                if (snapshot.attempted.isNotEmpty() && snapshot.hit != only) {
                    synchronized(failure) {
                        failure.append("hit=${snapshot.hit} attempted=${snapshot.attempted}\n")
                    }
                }
            }
        }

        start.countDown()
        assertTrue("writer 超时", done.await(30, TimeUnit.SECONDS))
        pool.shutdown()
        assertTrue("reader 未在 30s 内结束", pool.awaitTermination(30, TimeUnit.SECONDS))
        assertEquals("并发读到错配快照", "", failure.toString())
    }
}
