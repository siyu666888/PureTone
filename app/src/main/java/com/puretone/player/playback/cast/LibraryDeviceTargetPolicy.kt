package com.puretone.player.playback.cast

/**
 * 库通道兜底设备的 LOCATION 目标校验（审查 P2-3 第五轮）。
 *
 * ## 背景
 *
 * 自建 SSDP 的 LOCATION 在 [SsdpDiscovery.onDatagram] 由 [SsdpSourcePolicy]
 * 比对应答源，描述 GET 与所有 SOAP 目标由 [SoapTargetPolicy] 在
 * [SoapClient.request] **打开连接之前**校验。但 [CastDeviceDiscovery.searchOnce]
 * 的兜底路径调用的是第三方 `DLNACast.search()`：它自己发 SSDP、自己取设备描述
 * （既不经过 `onDatagram`，也不经过自有 `SoapClient`），返回的 `Device.id`
 * 直接写进 `lastDevices` → UI 设备列表 → [CastDeviceDiscovery.select] → 投送链路。
 * 这条链上此前没有任何目标约束。
 *
 * ## 边界（判读时务必如实说明）
 *
 * 本策略**拦不住库内部那次 HTTP 请求**——SSRF 在第三方代码里已经发生，调用方
 * 无法拦截。它做的是：在设备进入**自有投送链路之前**剔掉明显畸形的 LOCATION，
 * 并给域名 / IPv6 / 非 URL 形状留可 grep 的兼容窗口，让后续 SOAP 目标不再由
 * 一个未经检查的字符串决定。库通道的完整收口仍需多品牌真机描述取证。
 *
 * ## 三态口径，与前三处一致
 *
 * - 合法单播 IPv4 字面量 → [Screened.keep] = true、`diagnostic = null`
 *   （**零新增日志、零行为变化**，正常设备完全无感）；
 * - 明确畸形目标（非 http(s) scheme / 环回 / 带 userInfo / 纯数字或十六进制
 *   主机 / 多播广播 / 非法端口 / 控制字符）→ 剔除并落 `library-device reject …`；
 * - 域名、IPv6、以及**不以 http(s) 开头的 id** → **放行但留痕**
 *   `library-device unverified …`。最后一条尤其关键：库返回的 id 格式不受我们
 *   控制（部分实现给 UDN / uuid / 裸 IP），按 URL 规则一律拒绝会误杀真实设备，
 *   宁可放行留痕，由真机取证再决定是否收紧。
 *
 * 全程纯字符串：不引用 Android API、不做 DNS、不打开连接、不构造第三方类型。
 * 诊断由调用方以可选 lambda 注入（生产侧传 [CastTargetDiagnostics.emit]，
 * 其默认 sink 是 noop），因此纯 JVM 单测零 Android 依赖。
 */
internal object LibraryDeviceTargetPolicy {

    /** 单台设备的判定结果；[diagnostic] 为 null 表示 ACCEPT，无需落盘 */
    data class Screened(
        val keep: Boolean,
        val diagnostic: String?,
    )

    /**
     * 批量判定：返回与 [ids] **等长、同序**的保留标记。
     *
     * 只负责判定与转发诊断行，不做过滤——过滤由调用方按索引完成，
     * 避免把第三方 `DLNACast.Device` 类型带进纯策略。
     */
    fun screenAll(ids: List<String>, emit: (String) -> Unit = {}): List<Boolean> {
        val keep = ArrayList<Boolean>(ids.size)
        for (id in ids) {
            val screened = screen(id)
            screened.diagnostic?.let(emit)
            keep.add(screened.keep)
        }
        return keep
    }

    /** 判定一台库通道设备的描述 URL（[SoapTargetPolicy] 的同款三态口径） */
    fun screen(id: String): Screened {
        val trimmed = id.trim()
        if (trimmed.isEmpty()) return Screened(false, line("reject", null, "empty-id", trimmed))
        // 库返回的 id 未必是 LOCATION URL（UDN / uuid / 裸 IP 都见过）。
        // 这类形状不能按 URL 规则误杀：放行但留痕，交给后续投送链路。
        if (trimmed.indexOf("://") < 0) {
            return Screened(true, line("unverified", null, "non-url-id", trimmed))
        }
        // 已经写成 URL 却不是 http(s)（`file://` / `ftp://` …）没有兼容理由，
        // 直接剔除；不用再进 verify，诊断 reason 与 SoapTargetPolicy 保持一致。
        if (!hasHttpScheme(trimmed)) {
            return Screened(false, line("reject", null, "bad-scheme", trimmed))
        }
        val decision = SoapTargetPolicy.verify(trimmed)
        return when (decision.verdict) {
            SoapTargetPolicy.Verdict.ACCEPT -> Screened(true, null)
            SoapTargetPolicy.Verdict.REJECT ->
                Screened(false, line("reject", decision.host, decision.reason, trimmed))
            SoapTargetPolicy.Verdict.UNVERIFIED ->
                Screened(true, line("unverified", decision.host, decision.reason, trimmed))
        }
    }

    /**
     * 已经带 `://` 的地址只认 `http` / `https`（大小写不敏感，
     * 与 [SoapTargetPolicy] 一致）；`file:///…` / `ftp://…` 在此判为
     * `bad-scheme`。不带 `://` 的 UDN / uuid / 裸 IP 由 [screen] 先走兼容窗口。
     */
    private fun hasHttpScheme(text: String): Boolean {
        val end = text.indexOf("://")
        if (end <= 0) return false
        return when (text.substring(0, end).lowercase()) {
            "http", "https" -> true
            else -> false
        }
    }

    /** 单行诊断；控制字符与空白一律替换，避免畸形 id 把诊断文件写花 */
    private fun line(action: String, host: String?, reason: String, id: String): String {
        val safe = buildString {
            for (c in id.take(MAX_ID_CHARS)) {
                append(if (c.code < 0x20 || c.code == 0x7f || c.isWhitespace()) '_' else c)
            }
        }
        return "library-device $action host=${host ?: "-"} reason=$reason id=$safe"
    }

    private const val MAX_ID_CHARS = 120
}
