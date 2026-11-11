package com.puretone.player.playback.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [ControlUrlHostPolicy] 单测（第三轮审查 P2-3 第二处）。
 *
 * 只测纯策略：真机上多品牌设备的 controlURL 到底是相对还是绝对、有没有域名写法，
 * 必须看 `control-url reject/unverified` 诊断行，用替身测出来只是假绿灯。
 * 核心不变量：**只拒「IP 字面量指向另一台设备」与畸形 URL，域名/混用一律放行留痕**。
 */
class ControlUrlHostPolicyTest {

    private val location = "http://192.168.101.20:8200/dmr/desc.xml"

    private fun verdict(absolute: String, from: String = location) =
        ControlUrlHostPolicy.verify(from, absolute).verdict

    private fun reason(absolute: String, from: String = location) =
        ControlUrlHostPolicy.verify(from, absolute).reason

    private fun assertAccept(absolute: String, from: String = location) {
        val d = ControlUrlHostPolicy.verify(from, absolute)
        assertEquals(ControlUrlHostPolicy.Verdict.ACCEPT, d.verdict)
        assertEquals("matched", d.reason)
    }

    private fun assertReject(absolute: String, expectedReason: String, from: String = location) {
        val d = ControlUrlHostPolicy.verify(from, absolute)
        assertEquals(ControlUrlHostPolicy.Verdict.REJECT, d.verdict)
        assertEquals(expectedReason, d.reason)
    }

    @Test
    fun `同 IP 不同端口的控制端点放行`() {
        assertAccept("http://192.168.101.20:9000/AVTransport/ctrl")
        assertAccept("http://192.168.101.20/desc2.xml")
    }

    @Test
    fun `描述地址与控制地址同 IP 时大小写与 scheme 无影响`() {
        assertAccept("HTTP://192.168.101.20:9000/ctrl")
        assertAccept("https://192.168.101.20:9000/ctrl")
    }

    @Test
    fun `IPv4 前导零规范化后仍判一致`() {
        assertAccept("http://192.168.101.020:9000/ctrl")
    }

    @Test
    fun `指向另一台局域网设备被拒绝`() {
        assertReject("http://192.168.1.1:8080/admin", "host-mismatch")
        assertReject("http://127.0.0.1:8080/admin", "host-mismatch")
        assertReject("http://10.0.0.5:8200/ctrl", "host-mismatch")
    }

    @Test
    fun `纯数字主机与带 userInfo 的写法被拒绝`() {
        assertReject("http://2130706433/ctrl", "host-mismatch")
        assertReject("http://192.168.101.20@evil.example/ctrl", "bad-target-host")
    }

    @Test
    fun `畸形绝对地址无 host 被拒绝`() {
        assertReject("http:///ctrl", "bad-target-host")
        assertReject("http://:9000/ctrl", "bad-target-host")
    }

    @Test
    fun `域名控制地址放行但留痕`() {
        val d = ControlUrlHostPolicy.verify(location, "http://MyTV.local:9000/ctrl")
        assertEquals(ControlUrlHostPolicy.Verdict.UNVERIFIED, d.verdict)
        assertEquals("mytv.local", d.host)
        assertEquals("host-not-ip", d.reason)
    }

    @Test
    fun `域名描述地址放行但留痕`() {
        val d = ControlUrlHostPolicy.verify(
            "http://MyTV.local:8200/desc.xml",
            "http://192.168.101.20:9000/ctrl"
        )
        assertEquals(ControlUrlHostPolicy.Verdict.UNVERIFIED, d.verdict)
        assertEquals("host-not-ip", d.reason)
    }

    @Test
    fun `IPv6 与 IPv4 混用放行但留痕`() {
        val d = ControlUrlHostPolicy.verify(location, "http://[fe80::1]:8200/ctrl")
        assertEquals(ControlUrlHostPolicy.Verdict.UNVERIFIED, d.verdict)
        assertEquals("ip-family-mismatch", d.reason)
    }

    @Test
    fun `IPv6 同地址放行`() {
        assertAccept(
            "http://[fe80::1]:9000/ctrl",
            "http://[fe80::1]:8200/desc.xml"
        )
    }

    @Test
    fun `IPv6 scope id 被剥离`() {
        assertAccept(
            "http://[fe80::1%wlan0]:9000/ctrl",
            "http://[fe80::1]:8200/desc.xml"
        )
    }

    @Test
    fun `描述 URL 解析不出 host 时不误杀`() {
        val d = ControlUrlHostPolicy.verify("not-a-url", "http://192.168.101.20:9000/ctrl")
        assertEquals(ControlUrlHostPolicy.Verdict.UNVERIFIED, d.verdict)
        assertNull(d.host)
        assertEquals("no-location-host", d.reason)
    }

    @Test
    fun `hostOf 去掉端口与 IPv6 方括号`() {
        assertEquals("192.168.101.20", ControlUrlHostPolicy.hostOf("http://192.168.101.20:8200/a/b"))
        assertEquals("fe80::1", ControlUrlHostPolicy.hostOf("http://[fe80::1]:8200/a"))
        assertEquals("mytv.local", ControlUrlHostPolicy.hostOf("HTTPS://MyTV.LOCAL:8200/x"))
    }

    @Test
    fun `hostOf 忽略 query 与 fragment`() {
        assertEquals("10.0.0.1", ControlUrlHostPolicy.hostOf("http://10.0.0.1:80/x?a=b#c"))
        assertEquals("10.0.0.1", ControlUrlHostPolicy.hostOf("http://10.0.0.1?a=b"))
    }

    @Test
    fun `hostOf 拒绝 userInfo 与缺 scheme`() {
        assertNull(ControlUrlHostPolicy.hostOf("http://user@10.0.0.1/ctrl"))
        assertNull(ControlUrlHostPolicy.hostOf("10.0.0.1:8200/ctrl"))
    }

    @Test
    fun `真机同款相对写法不进策略`() {
        // 相对 controlURL 由 ControlUrls.join 的另一分支处理，策略只收 http(s) 绝对地址；
        // 这里确认即便误传也不会被当成 host 误判。
        assertEquals(null, ControlUrlHostPolicy.hostOf("_urn:schemas-upnp-org:service:AVTransport_control"))
    }

    @Test
    fun `判定结果可重复`() {
        val abs = "http://192.168.1.1/admin"
        assertEquals(ControlUrlHostPolicy.verify(location, abs), ControlUrlHostPolicy.verify(location, abs))
    }
}
