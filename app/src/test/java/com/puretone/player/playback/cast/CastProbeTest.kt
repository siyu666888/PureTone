package com.puretone.player.playback.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CastProbe] 纯函数单测。
 *
 * 重点锁死 [CastProbe.resolveControlUrl]：库在 `buildServiceUrl()` 里用字符串拼接
 * 处理相对路径，遇到 `../` 或带目录的描述 URL 会拼出 404 的地址。这里用 RFC 3986
 * 语义（[java.net.URI.resolve]）保证三种写法都能落到正确端点。
 */
class CastProbeTest {

    @Test
    fun `绝对路径的 controlURL 只换主机端口`() {
        assertEquals(
            "http://192.168.101.20:8080/upnp/control/AVTransport1",
            CastProbe.resolveControlUrl(
                "http://192.168.101.20:8080/description.xml",
                "/upnp/control/AVTransport1"
            )
        )
    }

    @Test
    fun `相对路径的 controlURL 保留描述的目录前缀`() {
        // 库会拼成 http://ip:8080/control/avt（丢掉 dmr/ 前缀）→ 404
        assertEquals(
            "http://192.168.101.20:8080/dmr/control/avt",
            CastProbe.resolveControlUrl(
                "http://192.168.101.20:8080/dmr/description.xml",
                "control/avt"
            )
        )
    }

    @Test
    fun `向上跳目录的 controlURL 能正确归约`() {
        assertEquals(
            "http://192.168.101.20:8080/control/avt",
            CastProbe.resolveControlUrl(
                "http://192.168.101.20:8080/dmr/description.xml",
                "../control/avt"
            )
        )
    }

    @Test
    fun `已是绝对地址的 controlURL 原样返回`() {
        val abs = "http://192.168.101.20:9000/AVTransport/ctrl"
        assertEquals(abs, CastProbe.resolveControlUrl("http://10.0.0.1/desc.xml", abs))
    }

    @Test
    fun `空 controlURL 无法解析`() {
        assertNull(CastProbe.resolveControlUrl("http://10.0.0.1/desc.xml", ""))
    }

    @Test
    fun `解析服务列表保留全部类型与原始 controlURL`() {
        val xml = """
            <root>
              <serviceList>
                <service>
                  <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                  <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
                  <controlURL>/upnp/control/AVTransport1</controlURL>
                  <SCPDURL>/upnp/AVTransport1.xml</SCPDURL>
                </service>
                <service>
                  <serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
                  <serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId>
                  <controlURL>../cn/control</controlURL>
                </service>
              </serviceList>
            </root>
        """.trimIndent()

        val services = CastProbe.parseServices(xml)

        assertEquals(2, services.size)
        assertEquals("/upnp/control/AVTransport1", services[0].controlUrl)
        assertEquals("../cn/control", services[1].controlUrl)
        assertEquals("AVTransport", CastProbe.shortType(services[0].serviceType))
        assertEquals("ConnectionManager", CastProbe.shortType(services[1].serviceType))
        // SCPDURL 是查音量量程的唯一入口；设备没声明时必须落空串（不能编一个猜路径）
        assertEquals("/upnp/AVTransport1.xml", services[0].scpdUrl)
        assertEquals("", services[1].scpdUrl)
    }

    @Test
    fun `取 SinkProtocolInfo 并判定格式支持`() {
        val soap = """
            <s:Envelope><s:Body><u:GetProtocolInfoResponse>
              <SinkProtocolInfo>http-get:*:audio/mpeg:*,http-get:*:audio/flac:*,http-get:*:video/mp4:*</SinkProtocolInfo>
            </u:GetProtocolInfoResponse></s:Body></s:Envelope>
        """.trimIndent()

        val sink = CastProbe.sinkProtocols(soap)

        assertTrue(sink.contains("audio/flac"))
        val support = CastProbe.formatSupport(sink)
        assertTrue("mp3 应支持：$support", support.contains("mp3=yes"))
        assertTrue("flac 应支持：$support", support.contains("flac=yes"))
        assertTrue("wav 应不支持：$support", support.contains("wav=no"))
    }

    @Test
    fun `无 SinkProtocolInfo 时清单为空且判定全否`() {
        val sink = CastProbe.sinkProtocols("<s:Fault><errorCode>401</errorCode></s:Fault>")
        assertEquals("", sink)
        assertTrue(CastProbe.formatSupport(sink).contains("flac=no"))
    }
}
