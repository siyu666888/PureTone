package com.puretone.player.playback.cast

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [SsdpSourcePolicy] 单测（第三轮审查 P2-3）。
 *
 * 与 [SsdpDiscoveryTest] 一样只测纯逻辑：UDP 收发、`packet.address` 的填充与诊断落盘
 * 必须在真机上看 `ownssdp` / `ownssdp-source` 行，用替身测出来只会变成假绿灯。
 */
class SsdpSourcePolicyTest {

    private val tv = "192.168.101.20"

    private fun verdict(location: String, source: String? = tv) =
        SsdpSourcePolicy.verify(location, source).verdict

    private fun reason(location: String, source: String? = tv) =
        SsdpSourcePolicy.verify(location, source).reason

    @Test
    fun `LOCATION 与应答源一致时放行`() {
        assertEquals(
            SsdpSourcePolicy.Verdict.ACCEPT,
            verdict("http://$tv:39520/description.xml")
        )
        assertEquals("matched", reason("http://$tv:39520/description.xml"))
    }

    @Test
    fun `设备描述端口不参与比较`() {
        assertEquals(SsdpSourcePolicy.Verdict.ACCEPT, verdict("http://$tv:8200/desc.xml"))
        assertEquals(SsdpSourcePolicy.Verdict.ACCEPT, verdict("http://$tv/desc.xml"))
    }

    @Test
    fun `scheme 大小写与前后空白不影响判定`() {
        assertEquals(SsdpSourcePolicy.Verdict.ACCEPT, verdict("HTTP://$tv:8200/desc.xml"))
        assertEquals(SsdpSourcePolicy.Verdict.ACCEPT, verdict("  http://$tv:8200/desc.xml  "))
        assertEquals(SsdpSourcePolicy.Verdict.ACCEPT, verdict("https://$tv:8200/desc.xml"))
    }

    @Test
    fun `IPv4 前导零规范化后仍判一致`() {
        // 部分设备把 IP 写成 001.005 这类定长格式，字符串直比会误杀
        assertEquals(
            SsdpSourcePolicy.Verdict.ACCEPT,
            SsdpSourcePolicy.verify("http://192.168.001.005:8200/desc.xml", "192.168.1.5").verdict
        )
        assertEquals(
            SsdpSourcePolicy.Verdict.ACCEPT,
            SsdpSourcePolicy.verify("http://192.168.101.20:8200/desc.xml", "192.168.101.020").verdict
        )
    }

    @Test
    fun `伪造 LOCATION 指向网关或回环一律拒绝`() {
        for (target in listOf("192.168.101.1", "127.0.0.1", "10.0.0.9", "8.8.8.8")) {
            val decision = SsdpSourcePolicy.verify("http://$target/admin", tv)
            assertEquals("target=$target", SsdpSourcePolicy.Verdict.REJECT, decision.verdict)
            assertEquals("host-mismatch", decision.reason)
        }
    }

    @Test
    fun `纯数字主机这类 SSRF 绕过写法被拒绝`() {
        // URL 解析器会把 2130706433 当 127.0.0.1；不能当域名放行
        assertEquals(SsdpSourcePolicy.Verdict.REJECT, verdict("http://2130706433:8080/"))
        assertEquals(SsdpSourcePolicy.Verdict.REJECT, verdict("http://0177.0.0.1/"))
    }

    @Test
    fun `非 http scheme 与缺 host 的畸形 LOCATION 被拒绝`() {
        assertEquals(SsdpSourcePolicy.Verdict.REJECT, verdict("file://$tv/desc.xml"))
        assertEquals(SsdpSourcePolicy.Verdict.REJECT, verdict("$tv:8200/desc.xml"))
        assertEquals(SsdpSourcePolicy.Verdict.REJECT, verdict(""))
        assertEquals("bad-location", reason("file://$tv/desc.xml"))
        assertEquals("no-host", reason("http:///desc.xml"))
    }

    @Test
    fun `带 userInfo 的 URL 被拒绝避免 host 混淆`() {
        assertEquals(
            SsdpSourcePolicy.Verdict.REJECT,
            verdict("http://$tv@192.168.101.1/admin")
        )
        assertEquals("bad-location", reason("http://$tv@192.168.101.1/admin"))
    }

    @Test
    fun `域名形式的 LOCATION 放行但标记未验证`() {
        val decision = SsdpSourcePolicy.verify("http://MyTV.local:8200/desc.xml", tv)
        assertEquals(SsdpSourcePolicy.Verdict.UNVERIFIED, decision.verdict)
        assertEquals("host-not-ip", decision.reason)
        assertEquals("mytv.local", decision.host)
    }

    @Test
    fun `应答源取不到时放行但标记未验证`() {
        val decision = SsdpSourcePolicy.verify("http://$tv:8200/desc.xml", null)
        assertEquals(SsdpSourcePolicy.Verdict.UNVERIFIED, decision.verdict)
        assertEquals("source-unknown", decision.reason)
    }

    @Test
    fun `IPv6 字面量带方括号与 scope id 时判一致`() {
        assertEquals(
            SsdpSourcePolicy.Verdict.ACCEPT,
            SsdpSourcePolicy.verify("http://[fe80::1%25wlan0]:8200/desc.xml", "fe80::1%wlan0").verdict
        )
        assertEquals(
            SsdpSourcePolicy.Verdict.ACCEPT,
            SsdpSourcePolicy.verify("http://[FE80::1]:8200/desc.xml", "fe80::1").verdict
        )
    }

    @Test
    fun `IPv6 LOCATION 指向另一台设备时被拒绝`() {
        val decision = SsdpSourcePolicy.verify("http://[fe80::2]:8200/desc.xml", "fe80::1")
        assertEquals(SsdpSourcePolicy.Verdict.REJECT, decision.verdict)
        assertEquals("host-mismatch", decision.reason)
    }

    @Test
    fun `IPv6 与 IPv4 地址族不一致时只标记不误杀`() {
        val decision = SsdpSourcePolicy.verify("http://[fe80::1]:8200/desc.xml", tv)
        assertEquals(SsdpSourcePolicy.Verdict.UNVERIFIED, decision.verdict)
        assertEquals("ip-family-mismatch", decision.reason)
    }

    @Test
    fun `判定结果带规范化 host 供诊断落盘`() {
        assertEquals(tv, SsdpSourcePolicy.verify("http://192.168.101.020:8200/d.xml", tv).host)
        assertEquals(null, SsdpSourcePolicy.verify("file://$tv/d.xml", tv).host)
    }
}
