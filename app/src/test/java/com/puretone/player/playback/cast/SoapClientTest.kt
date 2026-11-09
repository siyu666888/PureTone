package com.puretone.player.playback.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SoapClient.readLimited] 单测（P1-3）。
 *
 * 恶意 LAN 设备可以无限返回响应体，旧 `readText()` 会一直读到 OOM。
 * 这里只用 ByteArrayInputStream 锁定「正常读 / 上限截断 / 边界」行为，不起 HTTP 连接。
 */
class SoapClientTest {

    @Test
    fun `小响应完整读取且保留UTF8中文`() {
        val xml = "<s:Fault><detail>设备不支持</detail></s:Fault>"
        assertEquals(xml, SoapClient.readLimited(xml.toByteArray(Charsets.UTF_8).inputStream()))
    }

    @Test
    fun `超出上限只保留前N字节`() {
        val big = ByteArray(1000) { 'a'.code.toByte() }
        assertEquals("a".repeat(10), SoapClient.readLimited(big.inputStream(), limit = 10))
    }

    @Test
    fun `恰好等于上限时完整返回`() {
        val body = "12345"
        assertEquals(body, SoapClient.readLimited(body.toByteArray().inputStream(), limit = 5))
    }

    @Test
    fun `空流返回空串`() {
        assertEquals("", SoapClient.readLimited(ByteArray(0).inputStream()))
    }

    @Test
    fun `上限为零时直接返回空串`() {
        assertEquals("", SoapClient.readLimited(ByteArray(100).inputStream(), limit = 0))
    }

    @Test
    fun `截断在多字节字符中间不抛异常`() {
        val text = "晴天".repeat(20)
        val bytes = text.toByteArray(Charsets.UTF_8)
        val result = SoapClient.readLimited(bytes.inputStream(), limit = 7)
        // 7 字节 = 两个完整汉字(6) + 第三个汉字的第一个字节，末尾应被替换为 U+FFFD
        assertTrue(result.isNotEmpty())
        assertTrue(result.startsWith("晴天"))
    }
}
