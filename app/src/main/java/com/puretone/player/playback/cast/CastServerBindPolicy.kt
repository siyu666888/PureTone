package com.puretone.player.playback.cast

/**
 * 自有取流服务的**监听绑定策略**（第三轮审查 P2-1）。
 *
 * 背景：[CastHttpServer] 旧实现用 `ServerSocket(0)`，内核默认绑 `0.0.0.0`——
 * 尽管公布给电视的 URL 里写的是 Wi-Fi IPv4，socket 其实接受来自**所有**网卡的连接：
 * 手机开热点时连到热点的设备、装了 VPN 时 VPN 对端，都能摸到这个随机端口。
 * token 鉴权保证它们读不到文件，但更稳妥的是从网络层就不接这些连接。
 *
 * 这里只做**纯字符串**判定，不引用 Android API、不触发 DNS、不碰 socket，
 * 便于 JVM 单测锁定边界。真正的 `InetAddress` 解析与 `ServerSocket` 绑定留在
 * [CastHttpServer.start]，且任何一步失败都必须回退到旧的 `0.0.0.0` 行为。
 *
 * ## 为什么还要保留 BIND_ANY
 *
 * 用户只有一台渲染器、真机回归受限，而绑定具体 IP 会改变连接行为：
 * 若某台设备拿到的「Wi-Fi IPv4」并非本机可绑定地址（多网卡、VPN、厂商定制
 * `LinkProperties`），一律绑定会让投屏直接起不来。故凡判不准的形态
 * （null / IPv6 / 域名 / 环回 / 多播 / 前导零 / 越界）一律 [Mode.BIND_ANY]，
 * 并把原因落进 `http-server started … reason=…` 供真机判读。
 */
internal object CastServerBindPolicy {

    enum class Mode {
        /** 绑定到 Wi-Fi 网卡的单播 IPv4 */
        BIND_IPV4,

        /** 回退到 `0.0.0.0`（改造前行为） */
        BIND_ANY
    }

    data class Decision(
        val mode: Mode,
        /** 规范化后的 IPv4 字面量；[Mode.BIND_ANY] 时为 null */
        val bindHost: String?,
        /** 机器可读的判定理由，供诊断落盘 */
        val reason: String
    )

    /**
     * @param rawHost 调用方从 Wi-Fi 网络取到的 IPv4（[CastNetworkBridge.wifiIpv4]），可能为 null
     */
    fun decide(rawHost: String?): Decision {
        val value = rawHost?.trim()?.takeIf { it.isNotBlank() }
            ?: return any("no-host")
        // 含冒号（IPv6 / 带端口）或任何非数字非点字符（域名、scope 后缀）都不解析
        if (value.contains(':') || value.any { !it.isDigit() && it != '.' }) {
            return any("not-ipv4")
        }
        val parts = value.split('.')
        if (parts.size != 4 || parts.any { it.isEmpty() }) return any("not-ipv4")
        // 前导零有歧义（"010.1.1.1" 在不同解析器里可能被当八进制），直接回退不猜
        if (parts.any { it.length > 1 && it[0] == '0' }) return any("leading-zero")
        val octets = parts.map { it.toIntOrNull() ?: return any("not-ipv4") }
        if (octets.any { it !in 0..255 }) return any("out-of-range")
        // 0.x（未指定/本网）、127.x（环回）、224.x 及以上（多播/保留/广播）不是可绑定单播地址
        if (octets[0] == 0 || octets[0] == 127 || octets[0] >= 224) {
            return any("non-unicast")
        }
        return Decision(Mode.BIND_IPV4, octets.joinToString("."), "wifi-ipv4")
    }

    private fun any(reason: String) = Decision(Mode.BIND_ANY, null, reason)
}
