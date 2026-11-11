package com.puretone.player.playback.cast

/**
 * 设备描述里**绝对** `controlURL` / `SCPDURL` 的目标校验（第三轮审查 P2-3 第二处）。
 *
 * 背景：[ControlUrls.join] 旧实现遇到 `http(s)://…` 开头的 URL 直接原样返回。
 * UPnP 设备的控制端点应当在**同一台设备**上（描述 URL 的 host），但局域网里
 * 被攻陷 / 伪造的设备可以把 controlURL 写成 `http://192.168.1.1/admin` 或
 * `http://127.0.0.1:8080/…`，诱导手机把 SOAP 动作发给非预期目标（SSRF）。
 * 第一处（SSDP LOCATION 与应答源）已由 [SsdpSourcePolicy] 收口，这里补第二处。
 *
 * ## 三态，而不是「不一致就拒」
 *
 * 与 [SsdpSourcePolicy] 同一套保守口径：
 * - [Verdict.ACCEPT]：host 规范化后相等 → 原样放行，端口不参与比较（厂商自定）；
 * - [Verdict.REJECT]：两边都是 IP 字面量却不相等，或绝对 URL 的 host 解析不出来
 *   （畸形 / 带 userInfo）→ 丢弃，这是 SSRF 的核心面；
 * - [Verdict.UNVERIFIED]：任一边是域名、IPv6 与 IPv4 混用、描述 URL host 解析不出来
 *   → **放行但落诊断**。少数设备用域名写描述地址，一律拒绝会误杀真实设备；
 *   用户只有一台渲染器，多品牌真机回归受限，故留可判读的兼容窗口。
 *
 * 全程纯字符串：不引用 Android API、不做 DNS、不用 `java.net.URI`
 * （`_urn:` 之类非法 scheme 首字符会让 URI 抛异常，正是 [ControlUrls] 存在的理由）。
 */
internal object ControlUrlHostPolicy {

    enum class Verdict { ACCEPT, REJECT, UNVERIFIED }

    data class Decision(
        val verdict: Verdict,
        /** 规范化后的目标 host；解析不出来时为 null，供诊断落盘 */
        val host: String?,
        /** 机器可读的判定理由，供诊断 grep */
        val reason: String
    )

    /**
     * @param locationUrl 设备描述 URL（SSDP LOCATION / 库通道给出的地址）
     * @param absoluteUrl 描述里以 `http(s)://` 开头的 controlURL / SCPDURL 原文
     */
    fun verify(locationUrl: String, absoluteUrl: String): Decision {
        val from = hostOf(locationUrl)
            ?: return Decision(Verdict.UNVERIFIED, null, "no-location-host")
        val target = hostOf(absoluteUrl)
            ?: return Decision(Verdict.REJECT, null, "bad-target-host")
        if (target == from) return Decision(Verdict.ACCEPT, target, "matched")
        if (!isIpLiteral(from) || !isIpLiteral(target)) {
            return Decision(Verdict.UNVERIFIED, target, "host-not-ip")
        }
        if (from.contains(':') != target.contains(':')) {
            return Decision(Verdict.UNVERIFIED, target, "ip-family-mismatch")
        }
        return Decision(Verdict.REJECT, target, "host-mismatch")
    }

    /**
     * 纯字符串取 host：去 scheme / 端口 / IPv6 方括号 / scope id /  userInfo，
     * 转小写，IPv4 去前导零。
     *
     * `2130706433` 这类纯数字主机**不**转换：它会被 [isIpLiteral] 判成 IP 字面量，
     * 与描述 URL 的 host 必然不等，从而被拒（避免 URL 解析器当成 127.0.0.1 绕过）。
     */
    internal fun hostOf(url: String): String? {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd <= 0) return null
        val authority = url.substring(schemeEnd + 3)
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .takeIf { it.isNotBlank() }
            ?: return null
        // userInfo 是典型的 SSRF 写法（http://trusted@evil/），直接判为不可解析
        if (authority.contains('@')) return null
        val rawHost = if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close < 0) return null
            authority.substring(1, close)
        } else {
            val colon = authority.lastIndexOf(':')
            if (colon >= 0) authority.substring(0, colon) else authority
        }
        val host = rawHost.substringBefore('%').lowercase().takeIf { it.isNotBlank() } ?: return null
        val parts = host.split('.')
        if (parts.size == 4 && parts.all { it.isNotEmpty() && it.all(Char::isDigit) }) {
            val nums = parts.map { it.toIntOrNull() ?: return host }
            if (nums.all { it in 0..255 }) return nums.joinToString(".")
        }
        return host
    }

    /** 是否 IP 字面量：IPv6（含冒号）或「只有数字与点」的 IPv4 / 纯数字主机 */
    private fun isIpLiteral(host: String): Boolean =
        host.contains(':') || host.all { it.isDigit() || it == '.' }
}
