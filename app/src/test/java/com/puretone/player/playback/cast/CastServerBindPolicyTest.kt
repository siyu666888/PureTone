package com.puretone.player.playback.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CastServerBindPolicy] 单测（第三轮审查 P2-1 绑定加固）。
 *
 * 只测纯策略：真正的 `InetAddress` 解析、`ServerSocket` 绑定与「绑定失败回退」
 * 必须在真机上看 `http-server started … bind=… reason=…` 行，用替身测只会是假绿灯。
 * 核心不变量：**任何判不准的形态都回退 `0.0.0.0`，绝不返回可疑地址去 bind**。
 */
class CastServerBindPolicyTest {

    private fun mode(host: String?) = CastServerBindPolicy.decide(host).mode

    private fun reason(host: String?) = CastServerBindPolicy.decide(host).reason

    private fun assertBind(host: String, expected: String = host) {
        val d = CastServerBindPolicy.decide(host)
        assertEquals(CastServerBindPolicy.Mode.BIND_IPV4, d.mode)
        assertEquals(expected, d.bindHost)
        assertEquals("wifi-ipv4", d.reason)
    }

    private fun assertAny(host: String?, expectedReason: String) {
        val d = CastServerBindPolicy.decide(host)
        assertEquals(CastServerBindPolicy.Mode.BIND_ANY, d.mode)
        assertNull("回退时必须不携带绑定地址", d.bindHost)
        assertEquals(expectedReason, d.reason)
    }

    @Test
    fun `真机同款 Wi-Fi IPv4 绑定到该地址`() {
        assertBind("192.168.101.16")
    }

    @Test
    fun `其它单播网段同样可绑定`() {
        assertBind("10.0.0.7")
        assertBind("172.16.4.20")
        assertBind("169.254.8.1")
        assertBind("8.8.8.8")
    }

    @Test
    fun `前后空白被裁剪`() {
        assertBind("  192.168.1.9  ", "192.168.1.9")
    }

    @Test
    fun `边界八进制 0 与 255 合法`() {
        assertBind("1.0.0.255")
        assertBind("223.255.255.254")
    }

    @Test
    fun `host 缺失时回退`() {
        assertAny(null, "no-host")
        assertAny("", "no-host")
        assertAny("   ", "no-host")
    }

    @Test
    fun `IPv6 与带端口写法不解析`() {
        assertAny("fe80::1", "not-ipv4")
        assertAny("192.168.1.1:8080", "not-ipv4")
        assertAny("[fe80::1]", "not-ipv4")
    }

    @Test
    fun `域名与纯数字主机绝不交给 InetAddress`() {
        assertAny("MyTV.local", "not-ipv4")
        assertAny("2130706433", "not-ipv4")
        assertAny("localhost", "not-ipv4")
    }

    @Test
    fun `环回与未指定地址回退`() {
        assertAny("127.0.0.1", "non-unicast")
        assertAny("0.0.0.0", "non-unicast")
        assertAny("0.1.2.3", "non-unicast")
    }

    @Test
    fun `多播保留与广播地址回退`() {
        assertAny("224.0.0.1", "non-unicast")
        assertAny("239.255.255.250", "non-unicast")
        assertAny("255.255.255.255", "non-unicast")
    }

    @Test
    fun `前导零有解析歧义直接回退`() {
        assertAny("010.1.1.1", "leading-zero")
        assertAny("192.168.001.16", "leading-zero")
    }

    @Test
    fun `越界八进制回退`() {
        assertAny("256.1.1.1", "out-of-range")
        assertAny("192.168.1.999", "out-of-range")
    }

    @Test
    fun `段数不对或含空段回退`() {
        assertAny("192.168.1", "not-ipv4")
        assertAny("192.168.1.2.3", "not-ipv4")
        assertAny("192..1.2", "not-ipv4")
        assertAny("192.168.1.", "not-ipv4")
    }

    @Test
    fun `非数字字符回退`() {
        assertAny("192.168.1.-1", "not-ipv4")
        assertAny("192.168.1.x", "not-ipv4")
        assertAny("192.168.1.2%wlan0", "not-ipv4")
    }

    @Test
    fun `判定结果可重复且不改写入参`() {
        val host = "192.168.101.16"
        assertEquals(CastServerBindPolicy.decide(host), CastServerBindPolicy.decide(host))
        assertEquals(host, CastServerBindPolicy.decide(host).bindHost)
    }

    @Test
    fun `所有回退分支都携带机器可读理由`() {
        listOf(null, "", "fe80::1", "127.0.0.1", "224.0.0.1", "010.1.1.1", "256.1.1.1", "192.168.1")
            .forEach { host ->
                val d = CastServerBindPolicy.decide(host)
                assertEquals(CastServerBindPolicy.Mode.BIND_ANY, d.mode)
                assertNull(d.bindHost)
                assertTrue(d.reason.isNotBlank())
            }
    }
}
