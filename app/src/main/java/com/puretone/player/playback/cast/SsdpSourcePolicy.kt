package com.puretone.player.playback.cast

import java.net.URI

/**
 * SSDP 应答源校验（第三轮审查 P2-3）。
 *
 * 背景：SSDP 的 `LOCATION` 头来自局域网内任何设备。旧实现 [SsdpDiscovery] 只做
 * 「200 OK + LOCATION 非空 + 按 URL 去重」，随后 `resolveDevices` 直接
 * `SoapClient.get(located.location)`。于是同一 Wi-Fi 里的设备可以回一条
 * `LOCATION: http://192.168.1.1/admin` 或 `http://127.0.0.1:8080/...`，
 * 诱导手机向非预期目标发 GET（SSRF）。
 *
 * UPnP 设备在自己的 IP 上提供描述，所以「LOCATION 的 host == 应答源 IP」是廉价且
 * 协议自洽的校验。这里刻意用**纯字符串比较**而不是报告建议的
 * `InetAddress.getByName(host)`：后者会触发 DNS，既可能在收包循环里阻塞，也可能让
 * 攻击者用域名解析结果绕过比较。端口不参与比较——设备描述端口由厂商自定。
 *
 * ## 为什么不把「不一致」一律丢弃
 *
 * 两类情况无法在不联网的前提下证明，直接拒绝会误杀真实设备：
 * - 域名形式的 LOCATION（少数设备写 `http://MyTV:8200/desc.xml`）；
 * - 应答源地址取不到（IPv6 / 系统未填 `packet.address`），旧诊断行对此本来就打 null。
 *
 * 这两类记为 [Verdict.UNVERIFIED]：**放行但落一条诊断**，把「已收敛」与「仍待真机确认」
 * 分开。真正被拒的是「LOCATION 是 IP 字面量、却指向另一个 IP」，以及畸形 / 带
 * userInfo 的 URL——这正是 SSRF 的核心面，且与已验证的 IPv4 真机链路完全兼容。
 */
internal object SsdpSourcePolicy {

    enum class Verdict { ACCEPT, REJECT, UNVERIFIED }

    data class Decision(
        val verdict: Verdict,
        /** 规范化后的 LOCATION host；解析不出来时为 null，供诊断落盘 */
        val host: String?,
        /** 机器可读的判定理由，供诊断 grep */
        val reason: String
    )

    /**
     * @param location SSDP 应答里的 LOCATION 原文
     * @param sourceHost 收到该数据报的源地址（`packet.address.hostAddress`），可能为 null
     */
    fun verify(location: String, sourceHost: String?): Decision {
        val uri = runCatching { URI(location.trim()) }.getOrNull()
        val scheme = uri?.scheme?.lowercase()
        if (uri == null || (scheme != "http" && scheme != "https") || uri.userInfo != null) {
            return Decision(Verdict.REJECT, null, "bad-location")
        }
        val host = normalizeHost(uri.host) ?: return Decision(Verdict.REJECT, null, "no-host")
        val source = normalizeHost(sourceHost)
        // 域名（含任何带字母的主机名）不做 DNS 解析，放行但留痕
        if (!isIpLiteral(host)) return Decision(Verdict.UNVERIFIED, host, "host-not-ip")
        if (source == null) return Decision(Verdict.UNVERIFIED, host, "source-unknown")
        // IPv6 LOCATION 配 IPv4 源（或相反）：多网卡 / 映射地址，宁可留痕也不误杀
        if (host.contains(':') != source.contains(':')) {
            return Decision(Verdict.UNVERIFIED, host, "ip-family-mismatch")
        }
        return if (host == source) {
            Decision(Verdict.ACCEPT, host, "matched")
        } else {
            Decision(Verdict.REJECT, host, "host-mismatch")
        }
    }

    /**
     * 规范化主机名：去 IPv6 方括号与 scope id、转小写、IPv4 去前导零。
     *
     * `2130706433` 这类纯数字主机**不**转换：URL 解析器会把它当 127.0.0.1，
     * 交给 [isIpLiteral] 判成 IP 字面量后必然与应答源不等，从而被拒。
     */
    private fun normalizeHost(raw: String?): String? {
        val value = raw?.trim()
            ?.removePrefix("[")
            ?.removeSuffix("]")
            ?.substringBefore('%')
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val parts = value.split('.')
        if (parts.size == 4 && parts.all { it.isNotEmpty() && it.all(Char::isDigit) }) {
            val nums = parts.map { it.toIntOrNull() ?: return value }
            if (nums.all { it in 0..255 }) return nums.joinToString(".")
        }
        return value
    }

    /** 是否 IP 字面量：IPv6（含冒号）或「只有数字与点」的 IPv4 / 纯数字主机 */
    private fun isIpLiteral(host: String): Boolean =
        host.contains(':') || host.all { it.isDigit() || it == '.' }
}
