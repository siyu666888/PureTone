package com.puretone.player.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 交叉淡化触发决策单测。
 *
 * 锁定 [CrossfadePolicy.shouldTrigger] 的边界条件：关闭/无下一首/时长未知/远离结尾/
 * 越过窗口/APE 退化均不触发；临近结尾的时间窗内（含两端边界）才触发。
 */
class CrossfadePolicyTest {

    // ---------- 不触发 ----------

    @Test
    fun `淡化关闭 crossfadeMs=0 不触发`() {
        assertFalse(CrossfadePolicy.shouldTrigger(180_000, 1000, 0, hasNext = true, isApe = false))
    }

    @Test
    fun `不存在下一首不触发`() {
        assertFalse(CrossfadePolicy.shouldTrigger(180_000, 179_000, 3000, hasNext = false, isApe = false))
    }

    @Test
    fun `当前曲目时长未知 durationMs 为 0 或负 不触发`() {
        assertFalse(CrossfadePolicy.shouldTrigger(0, 1000, 3000, hasNext = true, isApe = false))
        assertFalse(CrossfadePolicy.shouldTrigger(-1, 1000, 3000, hasNext = true, isApe = false))
    }

    @Test
    fun `剩余时间仍远大于淡化窗口不触发`() {
        // duration=180s, position=10s → remain=170s，远超 3s+400ms 窗口
        assertFalse(CrossfadePolicy.shouldTrigger(180_000, 10_000, 3000, hasNext = true, isApe = false))
    }

    @Test
    fun `剩余时间到结尾或越过 不触发`() {
        assertFalse(CrossfadePolicy.shouldTrigger(180_000, 180_000, 3000, hasNext = true, isApe = false))
        assertFalse(CrossfadePolicy.shouldTrigger(180_000, 200_000, 3000, hasNext = true, isApe = false))
    }

    @Test
    fun `下一首为 APE 需转码格式不触发`() {
        // remain=500ms 本应落在窗口内，但 APE 退化为无缝
        assertFalse(CrossfadePolicy.shouldTrigger(180_000, 179_500, 3000, hasNext = true, isApe = true))
    }

    @Test
    fun `剩余时间恰好越过窗口上界（crossfadeMs+401）不触发`() {
        // crossfadeMs=3000 → 上界 3400ms；remain=3401ms 越界
        assertFalse(CrossfadePolicy.shouldTrigger(180_000, 176_599, 3000, hasNext = true, isApe = false))
    }

    // ---------- 触发 ----------

    @Test
    fun `剩余时间落在窗口内触发`() {
        // duration=180s, position=177s → remain=3000ms = crossfadeMs，应触发
        assertTrue(CrossfadePolicy.shouldTrigger(180_000, 177_000, 3000, hasNext = true, isApe = false))
        // remain=100ms（非常临近结尾）触发
        assertTrue(CrossfadePolicy.shouldTrigger(180_000, 179_900, 3000, hasNext = true, isApe = false))
        // remain=2000ms（窗口中部）触发
        assertTrue(CrossfadePolicy.shouldTrigger(180_000, 178_000, 3000, hasNext = true, isApe = false))
    }

    @Test
    fun `剩余时间恰好等于窗口上界 crossfadeMs+400 触发`() {
        // crossfadeMs=3000 → 上界 3400ms；remain=3400ms 命中边界（<= 上界）
        assertTrue(CrossfadePolicy.shouldTrigger(180_000, 176_600, 3000, hasNext = true, isApe = false))
    }

    @Test
    fun `较短淡化时长同样在窗口内触发`() {
        // crossfadeMs=500 → 上界 900ms；remain=800ms 命中
        assertTrue(CrossfadePolicy.shouldTrigger(180_000, 179_200, 500, hasNext = true, isApe = false))
    }
}
