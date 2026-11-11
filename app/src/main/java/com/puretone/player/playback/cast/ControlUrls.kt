package com.puretone.player.playback.cast

/**
 * 把设备描述里的 `controlURL` 解析成可用的绝对地址。
 *
 * ## 为什么不能用 `java.net.URI.resolve`
 *
 * 真机上确实存在这种写法（本次诊断里「多屏互动」接收端就是这样）：
 *
 *     <controlURL>_urn:schemas-upnp-org:service:AVTransport_control</controlURL>
 *
 * 它相对的是描述 URL 的目录，RFC 3986 语义下车应该解析成
 * `http://ip:port/_urn:schemas-upnp-org:service:AVTransport_control`。
 * 但 `_urn:` 里的 `_` 不是合法 scheme 首字符，JDK 会直接抛
 * `IllegalArgumentException: Illegal character in scheme name`
 * （已用 JDK 17 实测），于是整条 URL 变成 null —— 探针连"问一句状态"都做不到。
 *
 * 另外 `URI.resolve` 对 `../` 的处理依赖 base 路径，而库的 `buildServiceUrl()`
 * 干脆是字符串拼接：`"$baseUrl/$controlUrl"`，既不解析 `..` 也不保留描述目录前缀，
 * 遇到上述两种写法都会拼出 404 地址。
 *
 * 所以这里自己实现：只做「取授权部分 + 按目录相对 + 归约 `.`/`..`」三件事，
 * 不碰 scheme 解析，任何写法都不会抛异常。
 */
internal object ControlUrls {

    /** 取 `scheme://host[:port]`；无法解析时返回 null */
    fun authorityOf(url: String): String? {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd <= 0) return null
        val rest = url.substring(schemeEnd + 3)
        val pathStart = rest.indexOf('/').let { if (it < 0) rest.length else it }
        val authority = rest.substring(0, pathStart)
        if (authority.isBlank()) return null
        return url.substring(0, schemeEnd + 3) + authority
    }

    /** 取描述 URL 的目录部分（含结尾 `/`）；无路径时返回 `/` */
    fun directoryOf(url: String): String {
        val schemeEnd = url.indexOf("://")
        val start = if (schemeEnd < 0) 0 else schemeEnd + 3
        val pathStart = url.indexOf('/', start)
        if (pathStart < 0) return "/"
        val path = url.substring(pathStart)
        val lastSlash = path.lastIndexOf('/')
        return if (lastSlash < 0) "/" else path.substring(0, lastSlash + 1)
    }

    /**
     * 归约路径中的 `.` 与 `..`。
     *
     * 保留开头的 `/`（绝对路径）；`..` 已在根目录时忽略（与浏览器一致）。
     */
    fun normalize(path: String): String {
        val absolute = path.startsWith("/")
        val out = ArrayList<String>()
        for (segment in path.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
                else -> out.add(segment)
            }
        }
        val body = out.joinToString("/")
        return if (absolute) "/$body" else body
    }

    /**
     * 把 `raw` 解析成绝对地址。
     *
     * 绝对写法（`http(s)://…`）先经 [ControlUrlHostPolicy] 校验目标 host 与
     * [locationUrl] 是否指向同一台设备；指向另一 IP（或畸形 URL）时返回 null。
     * **相对写法分支一字未改**——真机「多屏互动」的 `_urn:` / `../` 全靠它。
     *
     * @param diagnostic 可选的诊断输出，仅生产调用侧传 `CastNetworkLog::log`，
     *   只记 `REJECT` / `UNVERIFIED`。纯函数与 JVM 单测不传：[CastNetworkLog]
     *   依赖 `android.util.Log`，在单测里触发会因 stub 抛异常。
     * @return 绝对地址；[raw] 为空、[locationUrl] 不含授权部分、或目标被拒时返回 null
     */
    fun join(
        locationUrl: String,
        raw: String,
        diagnostic: ((String) -> Unit)? = null
    ): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) {
            val decision = ControlUrlHostPolicy.verify(locationUrl, trimmed)
            if (decision.verdict != ControlUrlHostPolicy.Verdict.ACCEPT) {
                diagnostic?.invoke(
                    "control-url ${decision.verdict.name.lowercase()} " +
                        "host=${decision.host ?: "?"} reason=${decision.reason} location=$locationUrl"
                )
            }
            return if (decision.verdict == ControlUrlHostPolicy.Verdict.REJECT) null else trimmed
        }
        val authority = authorityOf(locationUrl) ?: return null
        val path = if (trimmed.startsWith("/")) {
            normalize(trimmed)
        } else {
            normalize(directoryOf(locationUrl) + trimmed)
        }
        return authority + path
    }
}
