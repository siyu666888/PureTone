package com.puretone.player.playback.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B5：findWifiNetwork 的 active 优先 / allNetworks 兜底共用的可用 Wi-Fi 判据（纯 JVM）。
 *
 * 旧代码把「Wi-Fi transport + INTERNET capability」内联在两个分支里，测试只能刻意让
 * activeNetwork=null 走兜底，active 优先分支零覆盖。这里把判据抽成纯函数直接锁定：
 * 缺 INTERNET（无网局域网，A4 审查已知场景）必须判不可用，否则投屏链路起不来。
 */
class UsableWifiNetworkTest {

    @Test
    fun `B5 只有同时带Wi-Fi传输与上网能力才判可用`() {
        assertTrue(isUsableWifiNetwork(hasWifiTransport = true, hasInternet = true))
        assertFalse(isUsableWifiNetwork(hasWifiTransport = true, hasInternet = false))
        assertFalse(isUsableWifiNetwork(hasWifiTransport = false, hasInternet = true))
        assertFalse(isUsableWifiNetwork(hasWifiTransport = false, hasInternet = false))
    }

    // ---- B5 完整路由测试：active 优先 / allNetworks 兜底（纯 JVM，标识用 String）----

    private fun wifi(id: String) = WifiCandidate(id, hasWifiTransport = true, hasInternet = true)
    private fun cellular(id: String) = WifiCandidate(id, hasWifiTransport = false, hasInternet = true)
    private fun noInternetWifi(id: String) = WifiCandidate(id, hasWifiTransport = true, hasInternet = false)

    @Test
    fun `B5 active是可上网Wi-Fi时优先选active`() {
        val active = wifi("ACTIVE-WIFI")
        val picked = pickWifiNetwork(active, listOf(wifi("OTHER-WIFI")))
        assertEquals("双网卡必须优先 active（走对网卡取对 IP）", "ACTIVE-WIFI", picked)
    }

    @Test
    fun `B5 active是蜂窝时兜底选allNetworks里的Wi-Fi`() {
        val picked = pickWifiNetwork(cellular("ACTIVE-CELLULAR"), listOf(wifi("WIFI-1"), wifi("WIFI-2")))
        assertEquals("active=cellular 必须兜底到 Wi-Fi", "WIFI-1", picked)
    }

    @Test
    fun `B5 active缺失时兜底选allNetworks里的Wi-Fi`() {
        val picked = pickWifiNetwork(null, listOf(cellular("CELL"), wifi("WIFI-ONLY")))
        assertEquals("active=null 必须兜底到 Wi-Fi", "WIFI-ONLY", picked)
    }

    @Test
    fun `B5 active是无网Wi-Fi时不选它且无其他可用则返回null`() {
        assertNull(
            "无网局域网（缺 INTERNET）不可作为投屏网络",
            pickWifiNetwork(noInternetWifi("NO-INTERNET"), emptyList())
        )
    }

    @Test
    fun `B5 active是无网Wi-Fi时兜底到allNetworks的可上网Wi-Fi`() {
        val picked = pickWifiNetwork(noInternetWifi("NO-INTERNET"), listOf(wifi("GOOD-WIFI")))
        assertEquals("无网 active 应被跳过并兜底", "GOOD-WIFI", picked)
    }

    @Test
    fun `B5 全部候选均不可用时返回null`() {
        assertNull(pickWifiNetwork(cellular("CELL"), listOf(cellular("CELL2"), noInternetWifi("NO-NET"))))
    }
}
