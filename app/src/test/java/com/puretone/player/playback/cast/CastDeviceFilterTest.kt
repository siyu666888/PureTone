package com.puretone.player.playback.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 非 MediaRenderer 过滤（2026-11-05 真机回归）。
 *
 * 诊断日志里同一轮 SSDP 搜到三条应答：电视 623 回 `MediaRenderer:1`，
 * 华为路由 TC7208 回 `upnp:rootdevice`。旧实现只把后者标成 `isTV=false` 仍然列出来，
 * 用户误点后 `cast-fail DeviceError`。这里把「该留 / 该删 / 拿不准就留」钉死。
 */
class CastDeviceFilterTest {

    private val rendererType = "urn:schemas-upnp-org:device:MediaRenderer:1"
    private val avt = "urn:schemas-upnp-org:service:AVTransport:1"
    private val rc = "urn:schemas-upnp-org:service:RenderingControl:1"
    private val cm = "urn:schemas-upnp-org:service:ConnectionManager:1"

    private fun verdict(
        fetched: Boolean = true,
        type: String? = rendererType,
        services: List<String> = listOf(avt, rc, cm)
    ) = CastDeviceFilter.evaluate(fetched, type, services)

    @Test
    fun `真机 623 电视保留并识别为渲染器`() {
        val v = verdict()
        assertTrue(v.keep)
        assertTrue(v.isRenderer)
        assertEquals("deviceType=MediaRenderer", v.reason)
    }

    @Test
    fun `华为路由 TC7208 这类 IGD 设备剔除`() {
        val v = verdict(
            type = "urn:schemas-upnp-org:device:InternetGatewayDevice:1",
            services = listOf("urn:schemas-upnp-org:service:WANIPConnection:1", cm)
        )
        assertFalse(v.keep)
        assertFalse(v.isRenderer)
    }

    @Test
    fun `MediaServer 没有 AVTransport 时剔除`() {
        val v = verdict(
            type = "urn:schemas-upnp-org:device:MediaServer:1",
            services = listOf("urn:schemas-upnp-org:service:ContentDirectory:1", cm)
        )
        assertFalse(v.keep)
    }

    @Test
    fun `只有 RenderingControl 没有 AVTransport 也剔除（调得了音量但投不了）`() {
        assertFalse(verdict(type = "urn:schemas-upnp-org:device:MediaServer:1", services = listOf(rc)).keep)
    }

    @Test
    fun `设备描述取不到时保守保留，避免误杀电视`() {
        val v = verdict(fetched = false, type = null, services = emptyList())
        assertTrue(v.keep)
        assertFalse(v.isRenderer)
    }

    @Test
    fun `描述只有占位类型且无服务时保守保留`() {
        val v = verdict(type = "?", services = emptyList())
        assertTrue(v.keep)
        assertFalse(v.isRenderer)
    }

    @Test
    fun `描述类型为空且无服务时保守保留`() {
        assertTrue(verdict(type = "", services = emptyList()).keep)
    }

    @Test
    fun `复合设备根类型是 MediaServer 但内嵌子设备有 AVTransport 时保留`() {
        val v = verdict(
            type = "urn:schemas-upnp-org:device:MediaServer:1",
            services = listOf("urn:schemas-upnp-org:service:ContentDirectory:1", avt, rc)
        )
        assertTrue(v.keep)
        assertTrue(v.isRenderer)
        assertEquals("服务含 AVTransport", v.reason)
    }

    @Test
    fun `deviceType 大小写与非标准版本号不敏感`() {
        assertTrue(verdict(type = "URN:SCHEMAS-UPNP-ORG:DEVICE:MEDIARENDERER:2", services = listOf("AVTransport:2")).keep)
    }

    @Test
    fun `只有 deviceType 命中而服务列表为空仍保留`() {
        val v = verdict(services = emptyList())
        assertTrue(v.keep)
        assertTrue(v.isRenderer)
    }

    @Test
    fun `AVTransport 版本号变体同样识别为渲染器`() {
        val v = verdict(
            type = "urn:schemas-upnp-org:device:UnknownDevice:1",
            services = listOf("urn:schemas-upnp-org:service:AVTransport:2")
        )
        assertTrue(v.keep)
        assertTrue(v.isRenderer)
    }
}
