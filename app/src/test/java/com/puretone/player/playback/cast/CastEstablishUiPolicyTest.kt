package com.puretone.player.playback.cast

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 遥控页「连接中」展示态的纯 JVM 锁死（170435）。
 *
 * 核心是「零取流才显示连接中」：取流已到达（含早于投送完成时刻的首次 GET）时
 * 不能把冻结的 00:00 伪装成进度，也不能一直挂在连接中态不恢复。
 */
class CastEstablishUiPolicyTest {

    private fun awaiting(
        isCasting: Boolean = true,
        isConnectionLost: Boolean = false,
        isEstablished: Boolean = false,
        lastFetchMs: Long = 0L
    ) = CastEstablishUiPolicy.awaitingEstablishment(
        isCasting = isCasting,
        isConnectionLost = isConnectionLost,
        isEstablished = isEstablished,
        lastFetchMs = lastFetchMs
    )

    @Test
    fun `未建立且本轮零取流时显示连接中`() {
        assertTrue(awaiting())
    }

    @Test
    fun `取流已到达但尚未确认建立时不冒充连接中`() {
        // 早到的首次 GET：fetchMs 可以小于投送完成时刻，只要 > 0 就属于本轮。
        assertFalse(awaiting(lastFetchMs = 9_950L))
    }

    @Test
    fun `确认建立后立刻退出连接中态`() {
        assertFalse(awaiting(isEstablished = true))
    }

    @Test
    fun `设备失联优先显示失联态而非连接中`() {
        assertFalse(awaiting(isConnectionLost = true))
    }

    @Test
    fun `未在投屏时不显示连接中`() {
        assertFalse(awaiting(isCasting = false))
    }

    @Test
    fun `库通道直通已建立即使没有取流时间戳也不误判`() {
        assertFalse(awaiting(isEstablished = true, lastFetchMs = 0L))
    }
}
