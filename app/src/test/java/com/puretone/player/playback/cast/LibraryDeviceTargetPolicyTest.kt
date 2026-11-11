package com.puretone.player.playback.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 库通道兜底设备入口校验（P2-3 第五轮）的纯 JVM 判定表。
 *
 * 重点是三类边界：合法单播 IPv4 **零日志零变化**；明确畸形目标剔除并落行；
 * 域名 / IPv6 / 非 URL 形状**放行留痕**（库 id 格式不受控，误杀比放行更糟）。
 */
class LibraryDeviceTargetPolicyTest {

    @Test
    fun `合法单播 IPv4 描述 URL 零变化且不落诊断`() {
        val d = LibraryDeviceTargetPolicy.screen("http://192.168.101.20:49152/upnpdev.xml")
        assertTrue(d.keep)
        assertNull(d.diagnostic)
    }

    @Test
    fun `IPv4 不带端口也放行`() {
        val d = LibraryDeviceTargetPolicy.screen("http://10.0.0.1/desc.xml")
        assertTrue(d.keep)
        assertNull(d.diagnostic)
    }

    @Test
    fun `https 描述 URL 放行`() {
        val d = LibraryDeviceTargetPolicy.screen("https://192.168.1.8:443/desc.xml")
        assertTrue(d.keep)
        assertNull(d.diagnostic)
    }

    @Test
    fun `scheme 大小写不敏感`() {
        val d = LibraryDeviceTargetPolicy.screen("HTTP://192.168.1.5:8200/desc.xml")
        assertTrue(d.keep)
        assertNull(d.diagnostic)
    }

    @Test
    fun `空 id 直接剔除`() {
        for (id in listOf("", "   ")) {
            val d = LibraryDeviceTargetPolicy.screen(id)
            assertFalse(d.keep)
            assertTrue(d.diagnostic!!.contains("reason=empty-id"))
        }
    }

    @Test
    fun `UDN 与 uuid 形状的 id 放行留痕`() {
        for (id in listOf("uuid:abc-123", "urn:upnp-org:deviceId:1")) {
            val d = LibraryDeviceTargetPolicy.screen(id)
            assertTrue(d.keep)
            assertTrue(d.diagnostic!!.contains("library-device unverified"))
            assertTrue(d.diagnostic!!.contains("reason=non-url-id"))
        }
    }

    @Test
    fun `裸 IP 形状的 id 放行留痕不误杀`() {
        val d = LibraryDeviceTargetPolicy.screen("192.168.1.5")
        assertTrue(d.keep)
        assertTrue(d.diagnostic!!.contains("reason=non-url-id"))
    }

    @Test
    fun `非 http scheme 剔除`() {
        for (id in listOf("file:///etc/passwd", "ftp://192.168.1.5/x")) {
            val d = LibraryDeviceTargetPolicy.screen(id)
            assertFalse(d.keep)
            assertTrue(d.diagnostic!!.contains("library-device reject"))
            assertTrue(d.diagnostic!!.contains("reason=bad-scheme"))
        }
    }

    @Test
    fun `环回目标剔除`() {
        val d = LibraryDeviceTargetPolicy.screen("http://127.0.0.1:8080/admin")
        assertFalse(d.keep)
        assertTrue(d.diagnostic!!.contains("reason=loopback"))
        assertTrue(d.diagnostic!!.contains("host=127.0.0.1"))
    }

    @Test
    fun `带 userInfo 的目标剔除`() {
        val d = LibraryDeviceTargetPolicy.screen("http://trusted@evil.com/desc.xml")
        assertFalse(d.keep)
        assertTrue(d.diagnostic!!.contains("reason=user-info"))
    }

    @Test
    fun `纯数字与十六进制主机剔除`() {
        for (id in listOf("http://2130706433/desc.xml", "http://0x7f000001/desc.xml")) {
            val d = LibraryDeviceTargetPolicy.screen(id)
            assertFalse(d.keep)
            assertTrue(d.diagnostic!!.contains("reason=numeric-host"))
        }
    }

    @Test
    fun `前导零与多播地址剔除`() {
        for (id in listOf("http://192.168.001.5/desc.xml", "http://239.255.255.250/desc.xml")) {
            val d = LibraryDeviceTargetPolicy.screen(id)
            assertFalse(d.keep)
        }
    }

    @Test
    fun `非法端口与控制字符剔除`() {
        for (id in listOf("http://192.168.1.5:99999/d", "http://192.168.1.5/a\n b")) {
            val d = LibraryDeviceTargetPolicy.screen(id)
            assertFalse(d.keep)
        }
    }

    @Test
    fun `域名 LOCATION 放行留痕`() {
        val d = LibraryDeviceTargetPolicy.screen("http://example.tv/desc.xml")
        assertTrue(d.keep)
        assertTrue(d.diagnostic!!.contains("library-device unverified"))
        assertTrue(d.diagnostic!!.contains("reason=domain-host"))
        assertTrue(d.diagnostic!!.contains("host=example.tv"))
    }

    @Test
    fun `IPv6 LOCATION 放行留痕`() {
        val d = LibraryDeviceTargetPolicy.screen("http://[fe80::1]:8200/desc.xml")
        assertTrue(d.keep)
        assertTrue(d.diagnostic!!.contains("reason=ipv6-host"))
    }

    @Test
    fun `诊断行是单行且 id 有长度上限`() {
        val longId = "file://" + "a".repeat(300) + "\n第二行"
        val d = LibraryDeviceTargetPolicy.screen(longId)
        assertFalse(d.keep)
        val line = d.diagnostic!!
        assertFalse(line.contains("\n"))
        assertFalse(line.contains("\r"))
        assertTrue(line.length < 200)
        assertTrue(line.contains("reason=bad-scheme"))
    }

    @Test
    fun `screenAll 等长同序且 ACCEPT 不落行`() {
        val ids = listOf(
            "http://192.168.1.5/desc.xml",
            "http://127.0.0.1/x",
            "uuid:abc",
            "http://example.tv/x",
        )
        val lines = mutableListOf<String>()
        val keep = LibraryDeviceTargetPolicy.screenAll(ids) { lines += it }
        assertEquals(listOf(true, false, true, true), keep)
        assertEquals(3, lines.size)
        assertTrue(lines[0].contains("reason=loopback"))
        assertTrue(lines[1].contains("reason=non-url-id"))
        assertTrue(lines[2].contains("reason=domain-host"))
    }

    @Test
    fun `默认发射器是 noop 不影响判定`() {
        assertEquals(listOf(false, true), LibraryDeviceTargetPolicy.screenAll(listOf("file:///x", "http://192.168.1.5/d")))
        assertEquals(emptyList<Boolean>(), LibraryDeviceTargetPolicy.screenAll(emptyList()))
    }
}
