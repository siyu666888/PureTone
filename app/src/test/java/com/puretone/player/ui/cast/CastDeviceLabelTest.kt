package com.puretone.player.ui.cast

import org.junit.Assert.assertEquals
import org.junit.Test

class CastDeviceLabelTest {

    private val fallback = "投屏"

    @Test
    fun `半角括号里的 IP 被剥离`() {
        assertEquals("623", CastDeviceLabel.short("623(192.168.101.20)", fallback))
    }

    @Test
    fun `全角括号里的 IP 被剥离`() {
        assertEquals("客厅的小米电视", CastDeviceLabel.short("客厅的小米电视（10.221.16.202）", fallback))
    }

    @Test
    fun `括号内含中文说明与 IP 一并剥离`() {
        assertEquals("623", CastDeviceLabel.short("623（DLNA 10.221.16.202:8200）", fallback))
    }

    @Test
    fun `不含 IP 的括号内容保留`() {
        assertEquals("音箱（客厅）", CastDeviceLabel.short("音箱（客厅）", fallback))
    }

    @Test
    fun `裸 IPv4 被剥离且不留粘连空格`() {
        assertEquals("音箱 623", CastDeviceLabel.short("音箱 192.168.1.7 623", fallback))
    }

    @Test
    fun `清洗后只残留装饰符时回落到兜底`() {
        assertEquals(fallback, CastDeviceLabel.short("— (10.0.0.1) ·", fallback))
    }

    @Test
    fun `纯 IP 与空值回落到兜底`() {
        assertEquals(fallback, CastDeviceLabel.short("192.168.101.20", fallback))
        assertEquals(fallback, CastDeviceLabel.short("", fallback))
        assertEquals(fallback, CastDeviceLabel.short("   ", fallback))
        assertEquals(fallback, CastDeviceLabel.short(null, fallback))
    }

    @Test
    fun `首尾装饰符被裁掉而中间保留`() {
        assertEquals("客厅音箱 · 623", CastDeviceLabel.short("· 客厅音箱 · 623 —", fallback))
    }
}
