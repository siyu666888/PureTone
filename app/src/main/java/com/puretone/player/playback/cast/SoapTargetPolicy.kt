package com.puretone.player.playback.cast

/**
 * [SoapClient] 的**目标 URL** 校验（第三轮审查 P2-3 第三处）。
 *
 * 背景：[SoapClient.get] / [SoapClient.post] 旧实现把传入的 URL 直接交给
 * `URL(url).openConnection()`，没有任何目标约束。前三处的 SSRF 面里，
 * LOCATION 与应答源由 [SsdpSourcePolicy] 收口、绝对 controlURL 由
 * [ControlUrlHostPolicy] 收口，但库通道给出的地址、猜路径、以及将来新增的
 * 调用点仍可能把 `file:///…`、`http://127.0.0.1:8080/…` 一类目标直接发出去。
 * 这里在**唯一的网络出口**补最后一道纯字符串校验。
 *
 * ## 三态，与前两处同一套保守口径
 *
 * - [Verdict.ACCEPT]：合法单播 IPv4 字面量（端口合法）→ 原样放行，零行为变化；
 * - [Verdict.REJECT]：非 http(s) scheme、缺 host、带 userInfo、纯数字 /
 *   前导零 / 十六进制主机、环回、多播广播、非法端口、控制字符 → 拒绝；
 *   正常设备的描述与控制端点**不可能是**这些写法；
 * - [Verdict.UNVERIFIED]：IPv6 字面量、域名 → **放行但留痕**。少数设备用域名
 *   写 LOCATION / controlURL，一律拒绝会误杀；用户只有一台渲染器，多品牌
 *   真机回归受限，故留可判读的兼容窗口。
 *
 * 全程纯字符串：不引用 Android API、不做 DNS、不打开连接、不用 `java.net.URI`
 * （`_urn:` 之类非法 scheme 首字符会让 URI 抛异常，正是 [ControlUrls] 存在的理由）。
 * 判定结果由 [SoapClient] 转成 `Result(-1, "blocked-target…")`，
 * 诊断由**生产调用侧**注入的可选回调落盘，纯函数与 JVM 单测零 Android 依赖。
 *
 * 判定拆成若干私有小函数：每个守卫独立一处，既保持 [verify] 的圈复杂度在
 * 门禁阈值内，也让「哪一类 URL 为什么被拒」能逐条单测。
 */
internal object SoapTargetPolicy {

    enum class Verdict { ACCEPT, REJECT, UNVERIFIED }

    data class Decision(
        val verdict: Verdict,
        /** 规范化后的目标 host；解析不出来时为 null，供诊断落盘 */
        val host: String?,
        /** 机器可读的判定理由，供诊断 grep */
        val reason: String
    )

    private sealed interface Split {
        data class Ok(val host: String, val port: String?) : Split
        data class Bad(val reason: String, val host: String? = null) : Split
    }

    fun verify(url: String): Decision {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return reject(null, "empty-url")
        if (hasControlChar(trimmed)) return reject(null, "control-char")
        val rest = afterScheme(trimmed) ?: return reject(null, "bad-scheme")
        val authority = authorityOf(rest) ?: return reject(null, "no-authority")
        // userInfo 是典型的 SSRF 写法（http://trusted@evil/），直接判为不可解析
        if (authority.contains('@')) return reject(null, "user-info")
        return when (val split = splitAuthority(authority)) {
            is Split.Bad -> reject(split.host, split.reason)
            is Split.Ok -> classify(split)
        }
    }

    private fun hasControlChar(text: String): Boolean =
        text.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7f }

    private fun afterScheme(url: String): String? {
        val end = url.indexOf("://")
        if (end <= 0) return null
        val scheme = url.substring(0, end).lowercase()
        if (scheme != "http" && scheme != "https") return null
        return url.substring(end + 3)
    }

    /** 只取 authority：scheme 之后、第一个 `/` / `?` / `#` 之前 */
    private fun authorityOf(rest: String): String? = rest
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
        .takeIf { it.isNotEmpty() }

    private fun splitAuthority(authority: String): Split {
        if (authority.startsWith("[")) return splitBracketed(authority)
        // 只有「恰好一个冒号 + 其后全数字」才是 host:port；多个冒号说明是
        // 未加方括号的 IPv6 字面量，不能把最后一段误当成端口（否则 ::1 会漏判）。
        val colon = authority.lastIndexOf(':')
        val tail = if (colon >= 0) authority.substring(colon + 1) else ""
        if (colon >= 0 && colon == authority.indexOf(':') && tail.all(Char::isDigit)) {
            return Split.Ok(authority.substring(0, colon), tail)
        }
        return Split.Ok(authority, null)
    }

    private fun splitBracketed(authority: String): Split {
        val close = authority.indexOf(']')
        if (close < 0) return Split.Bad("bad-ipv6-bracket")
        val rest = authority.substring(close + 1)
        if (rest.isNotEmpty() && !rest.startsWith(":")) {
            return Split.Bad("bad-ipv6-suffix", authority.substring(1, close))
        }
        return Split.Ok(authority.substring(1, close), rest.takeIf { it.startsWith(":") }?.substring(1))
    }

    private fun classify(split: Split.Ok): Decision {
        if (!validPort(split.port)) return reject(split.host, "bad-port")
        return classifyHost(split.host)
    }

    private fun validPort(port: String?): Boolean {
        if (port == null) return true
        val number = port.toLongOrNull() ?: return false
        return number in 1..65535
    }

    private fun classifyHost(rawHost: String): Decision {
        val host = rawHost.substringBefore('%').lowercase()
        if (host.isEmpty()) return reject(null, "no-host")
        if (isLoopback(host)) return reject(host, "loopback")
        // 2130706433 / 0x7f000001 会被 URL 解析器当成环回地址，属绕过写法
        if (host.all(Char::isDigit) || host.startsWith("0x")) return reject(host, "numeric-host")
        if (host.contains(':')) return unverified(host, "ipv6-host")
        return classifyIpv4OrDomain(host)
    }

    private fun isLoopback(host: String): Boolean = when {
        host == "localhost" -> true
        host == "::1" -> true
        host == "0:0:0:0:0:0:0:1" -> true
        host.startsWith("127.") -> true
        host.startsWith("0.") -> true
        else -> false
    }

    private fun classifyIpv4OrDomain(host: String): Decision {
        if (!host.contains('.')) return unverified(host, "domain-host")
        val parts = host.split('.')
        if (parts.any { it.isEmpty() || (it.length > 1 && it.startsWith('0')) }) {
            return reject(host, "bad-ipv4")
        }
        val nums = ipv4Numbers(parts) ?: return unverified(host, "domain-host")
        if (nums[0] in 224..239 || nums.all { it == 255 }) return reject(host, "non-unicast")
        return Decision(Verdict.ACCEPT, host, "unicast-ipv4")
    }

    private fun ipv4Numbers(parts: List<String>): List<Int>? {
        if (parts.size != 4) return null
        if (parts.any { !it.all(Char::isDigit) }) return null
        val nums = parts.mapNotNull { it.toIntOrNull() }
        if (nums.size != 4 || nums.any { it > 255 }) return null
        return nums
    }

    private fun unverified(host: String?, reason: String) = Decision(Verdict.UNVERIFIED, host, reason)

    private fun reject(host: String?, reason: String) = Decision(Verdict.REJECT, host, reason)
}
