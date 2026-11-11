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

    // --- P2-3 第三处：目标校验必须发生在 openConnection() 之前 ---

    @Test
    fun `非http目标直接拒绝并落诊断且不发包`() {
        val lines = mutableListOf<String>()
        val r = SoapClient.get("file:///etc/passwd", diagnostic = { lines += it })
        assertEquals(-1, r.status)
        assertTrue(r.body.startsWith("blocked-target reason="))
        assertEquals(1, lines.size)
        assertTrue(lines.single().startsWith("soap-target reject "))
        assertTrue(lines.single().contains("reason=bad-scheme"))
    }

    @Test
    fun `环回目标在纯JVM单测里不注入诊断也能安全拒绝`() {
        // 不传 diagnostic：验证拒绝分支不触碰 CastNetworkLog（首行是 Log.i）
        val r = SoapClient.get("http://127.0.0.1:8080/admin")
        assertEquals(-1, r.status)
        assertTrue(r.body.contains("reason=loopback"))
    }

    @Test
    fun `纯数字主机绕过写法被拒绝`() {
        val r = SoapClient.get("http://2130706433:8080/admin")
        assertEquals(-1, r.status)
        assertTrue(r.body.contains("reason=numeric-host"))
    }

    @Test
    fun `post同样走目标校验`() {
        val r = SoapClient.post("http://[fe80::1:8200/ctrl", "act", "<x/>")
        assertEquals(-1, r.status)
        assertTrue(r.body.contains("reason=bad-ipv6-bracket"))
    }

    @Test
    fun `UNVERIFIED目标只留痕不拒绝且不改Result语义`() {
        // 域名会被放行到 openConnection()；这里只验证判定本身，不发网络请求。
        val d = SoapTargetPolicy.verify("http://tv.local:8200/desc.xml")
        assertEquals(SoapTargetPolicy.Verdict.UNVERIFIED, d.verdict)
        assertEquals("domain-host", d.reason)
    }
}
