package com.puretone.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 交叉淡化渐变步进单测。
 *
 * 锁定 [CrossfadeRamp] 的三个听感关键点：
 * 1. 实际时长与剩余时间对齐（剩余不足时按剩余做完，不被切歌打断）；
 * 2. 步数/步长的退化行为（极短剩余仍需至少 1 步，否则备援停在静音态起播）；
 * 3. 音量曲线端点精确落在 0 / 1，且每步两者之和恒为 1（无整体音量凹陷、无收尾爆音）。
 */
class CrossfadeRampTest {

    // ---------- rampDurationMs：与剩余时间对齐 ----------

    @Test
    fun `配置时长小于剩余时取配置值`() {
        assertEquals(4000L, CrossfadeRamp.rampDurationMs(4000, 30_000L))
    }

    @Test
    fun `剩余时间小于配置时长时取剩余值`() {
        // 只剩 1.2s，配置 4s → 按 1.2s 做完，避免被切歌打断
        assertEquals(1200L, CrossfadeRamp.rampDurationMs(4000, 1200L))
    }

    @Test
    fun `剩余时间恰好等于配置时长`() {
        assertEquals(4000L, CrossfadeRamp.rampDurationMs(4000, 4000L))
    }

    @Test
    fun `剩余时间为零时钳到 1ms`() {
        assertEquals(1L, CrossfadeRamp.rampDurationMs(4000, 0L))
    }

    @Test
    fun `剩余时间为负时钳到 1ms`() {
        // position 已越过 duration 的退化场景
        assertEquals(1L, CrossfadeRamp.rampDurationMs(4000, -500L))
    }

    @Test
    fun `关闭态防御性返回零`() {
        // shouldTrigger 已保证 crossfadeMs>0 才会走到这里，此处仅锁定安全降级
        assertEquals(0L, CrossfadeRamp.rampDurationMs(0, 30_000L))
        assertEquals(0L, CrossfadeRamp.rampDurationMs(-100, 30_000L))
    }

    // ---------- steps：步数与退化 ----------

    @Test
    fun `四秒淡化按 50ms 切成 80 步`() {
        assertEquals(80, CrossfadeRamp.steps(4000L))
    }

    @Test
    fun `一百毫秒恰好 2 步`() {
        assertEquals(2, CrossfadeRamp.steps(100L))
    }

    @Test
    fun `九十九毫秒退化为 1 步`() {
        // 99/50 = 1（整数除法），不大于 1 → 1 步
        assertEquals(1, CrossfadeRamp.steps(99L))
    }

    @Test
    fun `五十毫秒退化为 1 步`() {
        assertEquals(1, CrossfadeRamp.steps(50L))
    }

    @Test
    fun `小于步长仍保留 1 步`() {
        // 关键：不能让步数为 0，否则备援播放器停在 setVolume(0) 静音起播
        assertEquals(1, CrossfadeRamp.steps(49L))
        assertEquals(1, CrossfadeRamp.steps(1L))
        assertEquals(1, CrossfadeRamp.steps(0L))
        assertEquals(1, CrossfadeRamp.steps(-10L))
    }

    // ---------- stepMs：每步延时 ----------

    @Test
    fun `四秒八十步每步五十毫秒`() {
        assertEquals(50L, CrossfadeRamp.stepMs(4000L))
    }

    @Test
    fun `一百毫秒两步每步五十毫秒`() {
        assertEquals(100L / 2, CrossfadeRamp.stepMs(100L))
    }

    @Test
    fun `极短时长退化为单步且延时等于时长`() {
        assertEquals(49L, CrossfadeRamp.stepMs(49L))
        assertEquals(1L, CrossfadeRamp.stepMs(1L))
    }

    @Test
    fun `零时长延时为零`() {
        assertEquals(0L, CrossfadeRamp.stepMs(0L))
    }

    @Test
    fun `总延时不超过配置时长`() {
        // 整数除法的零头被丢弃，只会更短，不会超时
        val ramp = 4000L
        val total = CrossfadeRamp.stepMs(ramp) * CrossfadeRamp.steps(ramp)
        assertTrue("总延时 $total 不应超过 $ramp", total <= ramp)
    }

    // ---------- 音量曲线：端点与守恒 ----------

    @Test
    fun `最后一步淡出方精确为零`() {
        val steps = CrossfadeRamp.steps(4000L)
        assertEquals(0f, CrossfadeRamp.outgoingVolume(steps - 1, steps), 0f)
    }

    @Test
    fun `最后一步淡入方精确为一`() {
        val steps = CrossfadeRamp.steps(4000L)
        assertEquals(1f, CrossfadeRamp.incomingVolume(steps - 1, steps), 0f)
    }

    @Test
    fun `单步退化时端点依然精确`() {
        // 极短剩余（1 步）也必须一次到位，否则旧主残留音量 / 新曲起播突跳
        assertEquals(0f, CrossfadeRamp.outgoingVolume(0, 1), 0f)
        assertEquals(1f, CrossfadeRamp.incomingVolume(0, 1), 0f)
    }

    @Test
    fun `淡出方单调递减且不越界`() {
        val steps = CrossfadeRamp.steps(4000L)
        var prev = 1.01f
        for (i in 0 until steps) {
            val v = CrossfadeRamp.outgoingVolume(i, steps)
            assertTrue("第 $i 步淡出音量 $v 应落在 [0,1]", v in 0f..1f)
            assertTrue("第 $i 步淡出音量 $v 应小于上一步 $prev", v < prev)
            prev = v
        }
    }

    @Test
    fun `淡入方单调递增且不越界`() {
        val steps = CrossfadeRamp.steps(4000L)
        var prev = -0.01f
        for (i in 0 until steps) {
            val v = CrossfadeRamp.incomingVolume(i, steps)
            assertTrue("第 $i 步淡入音量 $v 应落在 [0,1]", v in 0f..1f)
            assertTrue("第 $i 步淡入音量 $v 应大于上一步 $prev", v > prev)
            prev = v
        }
    }

    @Test
    fun `同一步两个音量之和恒为一`() {
        // 线性交叉，保证过渡期间整体响度不出现明显凹陷
        val steps = CrossfadeRamp.steps(4000L)
        for (i in 0 until steps) {
            val sum = CrossfadeRamp.outgoingVolume(i, steps) + CrossfadeRamp.incomingVolume(i, steps)
            assertEquals("第 $i 步音量之和应为 1", 1f, sum, 1e-6f)
        }
    }

    @Test
    fun `步数非正时给出安全默认值`() {
        // 防御：不应出现，但也不能除以零
        assertEquals(0f, CrossfadeRamp.outgoingVolume(0, 0), 0f)
        assertEquals(1f, CrossfadeRamp.incomingVolume(0, 0), 0f)
        assertEquals(0f, CrossfadeRamp.outgoingVolume(0, -5), 0f)
        assertEquals(1f, CrossfadeRamp.incomingVolume(0, -5), 0f)
    }
}
