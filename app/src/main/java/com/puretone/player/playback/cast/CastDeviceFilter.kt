package com.puretone.player.playback.cast

/**
 * SSDP 发现结果里「这台设备是不是 MediaRenderer」的判定（纯函数，可单测）。
 *
 * ## 为什么要过滤
 *
 * [SsdpDiscovery.SEARCH_TARGETS] 除了精确的 `MediaRenderer:1`，还发了
 * `upnp:rootdevice` 与 `ssdp:all` 两个宽泛目标——真机上电视刚唤醒时常常只回
 * rootdevice，少发一个就会漏掉电视。代价是路由器 / NAS / 媒体服务器也会应答。
 * 2026-11-05 诊断里 `华为路由TC7208(192.168.101.1)` 就是这样进了设备列表，
 * 用户误点后 `cast-fail DeviceError`，还得重新搜一次才能选到真正的电视。
 *
 * ## 判据：以「能不能投」为准，而不是看设备名或 SSDP 的 ST
 *
 * DLNA 投屏的唯一硬性前提是设备暴露 `AVTransport` 服务；规范里的 MediaRenderer
 * 一定有它。所以：
 * - `deviceType` 含 `MediaRenderer`，或任一 `serviceType` 含 `AVTransport` → 渲染器；
 * - 描述明确取到了、上面两条都不满足 → 非渲染器（路由器 IGD / MediaServer / 打印机）；
 * - **描述没取到或没有可判定字段 → 一律保留**。这条是红线：SSDP 只给了 LOCATION，
 *   取描述失败在真机上很常见（电视端口刚重启、HTTP 超时），此时把设备当成
 *   「非渲染器」剔除，就会重演「设备列表突然空了」——比多显示一台路由器更糟。
 *
 * 只看 `deviceType` 也不够：不少盒子把根设备写成 `MediaServer:1`，真正的
 * MediaRenderer 藏在 `<deviceList>` 子设备里。`CastProbe.parseServices` 全量抓
 * `<service>` 块，子设备的 AVTransport 一样能被看到，故用「或」而不是「且」。
 *
 * 历史列表不需要过滤：[CastHistoryEntry] 只在 `onCastSucceeded` 时写入，
 * 路由器物理上不可能投成功。
 */
internal object CastDeviceFilter {

    /** 判定结论：[keep] 是否留在设备列表，[isRenderer] 是否按电视图标显示 */
    data class Verdict(val keep: Boolean, val isRenderer: Boolean, val reason: String)

    private const val MEDIA_RENDERER = "MediaRenderer"
    private const val AV_TRANSPORT = "AVTransport"

    /** `CastProbe.parseDescription` 在字段缺失时用的占位值，不能拿它当真实类型 */
    private const val UNKNOWN_TYPE = "?"

    /**
     * @param descriptionFetched 设备描述 XML 是否成功取到并解析
     * @param deviceType 描述里的根 `deviceType`（取不到为 null）
     * @param serviceTypes 描述里**所有** `<service>` 的 `serviceType`（含子设备）
     */
    fun evaluate(
        descriptionFetched: Boolean,
        deviceType: String?,
        serviceTypes: List<String> = emptyList()
    ): Verdict {
        if (!descriptionFetched) {
            return Verdict(keep = true, isRenderer = false, reason = "描述不可达，保守保留")
        }
        val type = deviceType?.trim().orEmpty()
        val typeUsable = type.isNotEmpty() && type != UNKNOWN_TYPE && type.contains(':')
        if (!typeUsable && serviceTypes.isEmpty()) {
            return Verdict(keep = true, isRenderer = false, reason = "描述无可判定字段，保守保留")
        }
        val rendererByType = type.contains(MEDIA_RENDERER, ignoreCase = true)
        val rendererByService = serviceTypes.any {
            it.contains(AV_TRANSPORT, ignoreCase = true)
        }
        return when {
            rendererByType -> Verdict(true, true, "deviceType=MediaRenderer")
            rendererByService -> Verdict(true, true, "服务含 AVTransport")
            else -> Verdict(false, false, "非 MediaRenderer 且无 AVTransport")
        }
    }
}
