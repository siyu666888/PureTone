package com.puretone.player.playback.cast

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [SoapTargetPolicy] 单测（P2-3 第三处）。
 *
 * 纯字符串判定：不起 HTTP 连接、不碰 DNS、不引用 Android API。
 * 重点锁定「正常设备 URL 一律 ACCEPT」与「SSRF 常见绕过写法一律 REJECT」，
 * 以及域名 / IPv6 的兼容窗口是 UNVERIFIED 而不是 REJECT。
 */
class SoapTargetPolicyTest {

    private fun decision(url: String) = SoapTargetPolicy.verify(url)

    private fun assertAccept(url: String, host: String) {
        val d = decision(url)
        assertEquals("$url verdict", SoapTargetPolicy.Verdict.ACCEPT, d.verdict)
        assertEquals("$url host", host, d.host)
        assertEquals("$url reason", "unicast-ipv4", d.reason)
    }

    private fun assertReject(url: String, reason: String) {
        val d = decision(url)
        assertEquals("$url verdict", SoapTargetPolicy.Verdict.REJECT, d.verdict)
        assertEquals("$url reason", reason, d.reason)
    }

    private fun assertUnverified(url: String, reason: String) {
        val d = decision(url)
        assertEquals("$url verdict", SoapTargetPolicy.Verdict.UNVERIFIED, d.verdict)
        assertEquals("$url reason", reason, d.reason)
    }

    @Test
    fun `局域网IPv4带端口放行`() {
        assertAccept("http://192.168.101.20:8200/AVTransport/ctrl", "192.168.101.20")
    }

    @Test
    fun `局域网IPv4无端口放行`() {
        assertAccept("http://10.0.0.7/desc.xml", "10.0.0.7")
    }

    @Test
    fun `https与大写scheme同样放行`() {
        assertAccept("HTTPS://192.168.1.5:443/desc.xml", "192.168.1.5")
    }

    @Test
    fun `路径里的at符号不影响判定`() {
        assertAccept("http://192.168.1.5:8200/a@b/c", "192.168.1.5")
    }

    @Test
    fun `边界端口放行`() {
        assertAccept("http://192.168.1.5:65535/desc.xml", "192.168.1.5")
        assertAccept("http://192.168.1.5:1/desc.xml", "192.168.1.5")
    }

    @Test
    fun `空URL拒绝`() {
        assertReject("", "empty-url")
        assertReject("   ", "empty-url")
    }

    @Test
    fun `非http协议拒绝`() {
        assertReject("file:///etc/passwd", "bad-scheme")
        assertReject("ftp://192.168.1.5/x", "bad-scheme")
        assertReject("javascript:alert(1)", "bad-scheme")
    }

    @Test
    fun `相对路径与缺scheme拒绝`() {
        assertReject("/AVTransport/control", "bad-scheme")
        assertReject("192.168.1.5:8200/desc.xml", "bad-scheme")
    }

    @Test
    fun `缺authority拒绝`() {
        assertReject("http:///path", "no-authority")
    }

    @Test
    fun `userInfo拒绝`() {
        assertReject("http://trusted@192.168.1.5:8200/desc.xml", "user-info")
    }

    @Test
    fun `环回与未指定地址拒绝`() {
        assertReject("http://127.0.0.1:8080/admin", "loopback")
        assertReject("http://0.0.0.0/", "loopback")
        assertReject("http://localhost:8200/desc.xml", "loopback")
        assertReject("http://[::1]:8200/desc.xml", "loopback")
        assertReject("http://0:0:0:0:0:0:0:1/desc.xml", "loopback")
    }

    @Test
    fun `纯数字与十六进制主机拒绝`() {
        assertReject("http://2130706433:8080/admin", "numeric-host")
        assertReject("http://0x7f000001/", "numeric-host")
    }

    @Test
    fun `前导零与空段的IPv4拒绝`() {
        assertReject("http://0177.0.0.1/", "bad-ipv4")
        assertReject("http://192.168..1/", "bad-ipv4")
    }

    @Test
    fun `多播与广播拒绝`() {
        assertReject("http://224.0.0.1:1900/", "non-unicast")
        assertReject("http://255.255.255.255/", "non-unicast")
    }

    @Test
    fun `非法端口拒绝`() {
        assertReject("http://192.168.1.5:99999/desc.xml", "bad-port")
        assertReject("http://192.168.1.5:0/desc.xml", "bad-port")
    }

    @Test
    fun `IPv6方括号畸形拒绝`() {
        assertReject("http://[fe80::1:8200/desc.xml", "bad-ipv6-bracket")
        assertReject("http://[fe80::1]x/desc.xml", "bad-ipv6-suffix")
    }

    @Test
    fun `控制字符与内部空白拒绝`() {
        assertReject("http://192.168.1.5/\nInjected: 1", "control-char")
        assertReject("http://192.168.1.5/a b", "control-char")
    }

    @Test
    fun `域名放行但留痕`() {
        assertUnverified("http://tv.local:8200/desc.xml", "domain-host")
        assertUnverified("https://renderer.example.com/desc.xml", "domain-host")
    }

    @Test
    fun `IPv6字面量放行但留痕`() {
        assertUnverified("http://[fe80::1%25eth0]:8200/desc.xml", "ipv6-host")
        assertUnverified("http://192.168.1.5:abc/desc.xml", "ipv6-host")
    }

    @Test
    fun `越界IPv4段按域名放行留痕`() {
        assertUnverified("http://999.1.1.1/desc.xml", "domain-host")
    }
}
