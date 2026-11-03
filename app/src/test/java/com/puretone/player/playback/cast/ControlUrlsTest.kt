package com.puretone.player.playback.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ControlUrls] 单测。
 *
 * 重点锁死**不能用 `java.net.URI.resolve`** 的那类写法：真机上「多屏互动」接收端的
 * controlURL 是 `_urn:schemas-upnp-org:service:AVTransport_control`，JDK 会因
 * 「非法 scheme 首字符」抛异常，导致整条控制地址变成 null（探针连问一句状态都做不到）。
 */
class ControlUrlsTest {

    @Test
    fun `下划线开头的相对 controlURL 按目录解析且不抛异常`() {
        assertEquals(
            "http://192.168.101.20:39520/_urn:schemas-upnp-org:service:AVTransport_control",
            ControlUrls.join(
                "http://192.168.101.20:39520/description.xml",
                "_urn:schemas-upnp-org:service:AVTransport_control"
            )
        )
    }

    @Test
    fun `绝对路径只换主机与端口`() {
        assertEquals(
            "http://192.168.101.20:39520/control/AVTransport1",
            ControlUrls.join("http://192.168.101.20:39520/dmr/desc.xml", "/control/AVTransport1")
        )
    }

    @Test
    fun `相对路径保留描述目录前缀`() {
        assertEquals(
            "http://10.0.0.5:8080/dmr/control/avt",
            ControlUrls.join("http://10.0.0.5:8080/dmr/description.xml", "control/avt")
        )
    }

    @Test
    fun `向上跳目录会被归约`() {
        assertEquals(
            "http://10.0.0.5:8080/control/avt",
            ControlUrls.join("http://10.0.0.5:8080/dmr/description.xml", "../control/avt")
        )
    }

    @Test
    fun `已是绝对地址原样返回`() {
        val abs = "http://192.168.1.9:9000/AVTransport/ctrl"
        assertEquals(abs, ControlUrls.join("http://10.0.0.1/desc.xml", abs))
    }

    @Test
    fun `空 controlURL 返回 null`() {
        assertEquals(null, ControlUrls.join("http://10.0.0.1/desc.xml", "  "))
    }

    @Test
    fun `描述 URL 无路径时目录为根`() {
        assertEquals("/", ControlUrls.directoryOf("http://10.0.0.1"))
        assertEquals("/dmr/", ControlUrls.directoryOf("http://10.0.0.1/dmr/desc.xml"))
    }

    @Test
    fun `归约不会越过根目录`() {
        assertEquals("/a", ControlUrls.normalize("/../a"))
        assertTrue(ControlUrls.normalize("../../a") == "a")
    }
}
