package com.puretone.player.playback.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「新一轮投送成功」的状态重置契约（第四轮审查 R4-1）。
 *
 * 旧实现用 `it.copy(...)` 逐字段重置，漏了 `lastSeekMs`：上一曲用户拖进度条留下的
 * 3s 保护窗会跨曲泄漏到新曲——新曲刚投上时若渲染器瞬间 STOPPED，会被误判成
 * 「拖动后复位」走就地 play，而不是该有的整套重投。抽成纯函数后，后续新增
 * 「按轮次」字段却忘记清零会直接被这里抓住。
 */
class CastStateResetTest {

    @Test
    fun `新一轮投送清零上一曲的拖动保护窗`() {
        val previous = CastState(
            isCasting = true,
            progressMs = 28_000L,
            durationMs = 300_000L,
            hasProgressInfo = true,
            lastSeekMs = 1_900L,
            lastFetchMs = 1_800L,
            castStartMs = 1_000L,
            isEstablished = true
        )

        val next = previous.forNewCast(
            nowMs = 2_000L,
            deviceName = "客厅电视",
            deviceAddress = "192.168.101.20",
            established = false
        )

        assertEquals(0L, next.lastSeekMs) // R4-1：3s 保护窗绝不能跨曲
        assertEquals(0L, next.progressMs)
        assertEquals(0L, next.durationMs)
        assertEquals(0L, next.lastFetchMs) // 红线：建立确认仍须等本轮真实取流
        assertFalse(next.hasProgressInfo)
    }

    @Test
    fun `新一轮投送写入本轮身份且不继承上一曲残留`() {
        val previous = CastState(
            isCasting = true,
            deviceName = "旧设备",
            deviceAddress = "192.168.101.21",
            error = "上一曲的错误",
            isConnectionLost = true,
            isEstablished = false,
            isPreparing = true
        )

        val next = previous.forNewCast(
            nowMs = 2_000L,
            deviceName = "客厅电视",
            deviceAddress = "192.168.101.20",
            established = false
        )

        assertTrue(next.isCasting)
        assertFalse(next.isPreparing)
        assertEquals("客厅电视", next.deviceName)
        assertEquals("192.168.101.20", next.deviceAddress)
        assertNull(next.error)
        assertFalse(next.isConnectionLost)
        assertEquals(2_000L, next.castStartMs)
        assertFalse(next.isEstablished) // 自有通道须等本轮取流确认
    }
}
