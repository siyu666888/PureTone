package com.puretone.player.playback.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SsdpDiscovery] 单测。
 *
 * 只测纯逻辑（报文构造 / 应答解析 / 地址推导）——UDP 收发与网卡选择必须在真机上验证，
 * 用替身测出来的「通过」只会变成假绿灯。真机取证看诊断里的 `ownssdp` 行：
 * `bind`、`iface`、`join`、`tx`、`rx`、`locations`。
 */
class SsdpDiscoveryTest {

    @Test
    fun `M-SEARCH 带 MX=1 与目标 ST`() {
        val request = SsdpDiscovery.searchRequest(
            "urn:schemas-upnp-org:device:MediaRenderer:1",
            "239.255.255.250:1900"
        )
        assertTrue(request.startsWith("M-SEARCH * HTTP/1.1\r\n"))
        assertTrue(request.contains("HOST: 239.255.255.250:1900\r\n"))
        assertTrue(request.contains("MAN: \"ssdp:discover\"\r\n"))
        assertTrue(request.contains("MX: 1\r\n"))
        assertTrue(request.contains("ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n"))
        assertTrue(request.endsWith("\r\n\r\n"))
    }

    @Test
    fun `解析 200 OK 时头名大小写不敏感`() {
        val text = "HTTP/1.1 200 OK\r\n" +
            "CACHE-CONTROL: max-age=1800\r\n" +
            "Location: http://192.168.101.20:39520/description.xml\r\n" +
            "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
            "USN: uuid:529b-a1a1-a081-e170::urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
            "\r\n"
        val located = SsdpDiscovery.parseResponse(text)!!
        assertEquals("http://192.168.101.20:39520/description.xml", located.location)
        assertEquals("urn:schemas-upnp-org:device:MediaRenderer:1", located.st)
        assertTrue(located.usn.startsWith("uuid:529b"))
    }

    @Test
    fun `非 200 应答一律忽略`() {
        // NOTIFY 不参与发现：不带请求上下文，且 byebye 会把在线设备误删
        assertNull(
            SsdpDiscovery.parseResponse(
                "NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nNTS: ssdp:alive\r\n\r\n"
            )
        )
        assertNull(SsdpDiscovery.parseResponse("HTTP/1.1 404 Not Found\r\nLOCATION: http://a/b.xml\r\n"))
        assertNull(SsdpDiscovery.parseResponse(""))
    }

    @Test
    fun `200 OK 但缺 LOCATION 时返回 null`() {
        assertNull(SsdpDiscovery.parseResponse("HTTP/1.1 200 OK\r\nST: ssdp:all\r\n\r\n"))
    }

    @Test
    fun `从 LOCATION 取主机地址`() {
        assertEquals(
            "192.168.101.20",
            SsdpDiscovery.hostOf("http://192.168.101.20:39520/description.xml")
        )
        assertEquals("192.168.101.20", SsdpDiscovery.hostOf("http://192.168.101.20/description.xml"))
        // 没有 scheme 的脏数据也要能兜住
        assertEquals("192.168.101.20", SsdpDiscovery.hostOf("192.168.101.20:39520/desc.xml"))
    }

    @Test
    fun `子网广播地址按 24 位推导`() {
        assertEquals("192.168.101.255", SsdpDiscovery.broadcastOf("192.168.101.16"))
        assertEquals("10.0.0.255", SsdpDiscovery.broadcastOf("10.0.0.7"))
        assertNull(SsdpDiscovery.broadcastOf(null))
        assertNull(SsdpDiscovery.broadcastOf("192.168.101"))
        assertNull(SsdpDiscovery.broadcastOf("192.168.101.x"))
    }
}
