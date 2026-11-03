package com.puretone.player.playback.cast

/**
 * DIDL-Lite 元数据构造（`SetAVTransportURI` 的 `CurrentURIMetaData`）。
 *
 * ## 为什么还要自己造一份
 *
 * 库里的 `MetadataBuilder` 输出的是**最小合法报文**：
 *
 *     <res protocolInfo="http-get:*:audio/flac:*">…</res>
 *
 * 部分渲染器（本次的极光TV 即疑似）会要求 `protocolInfo` 带 DLNA 组织参数
 * （`DLNA.ORG_OP` 支持 Seek、`DLNA.ORG_CI` 是否转码、`DLNA.ORG_FLAGS` 能力位），
 * 缺失时直接以 SOAP fault 拒收 `SetAVTransportURI`，而库把这个 fault 吞掉了。
 *
 * 于是这里提供**多档 protocolInfo**，由调用方按顺序试、逐步落盘每一次的真实状态码：
 * 前档成功即止，全失败时诊断里留下完整的「哪一档被拒」证据。
 */
internal object DidlBuilder {

    /** DLNA flags：流式可 seek、支持时间跳转（各家实现通用的安全值） */
    private const val DLNA_FLAGS = "01700000000000000000000000000000"

    /**
     * 按「保守 → 完整」顺序给出 protocolInfo 候选。
     *
     * 1. 最小形态 `http-get:*:<mime>:*`——大部分接收端只认这个（也是库的写法）；
     * 2. 带 DLNA 参数——严格的接收端要求 `DLNA.ORG_OP` 等能力位。
     */
    fun protocolInfoVariants(mimeType: String): List<String> = listOf(
        "http-get:*:$mimeType:*",
        "http-get:*:$mimeType:DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=$DLNA_FLAGS"
    )

    /**
     * 构造完整 DIDL-Lite。
     *
     * @param title 显示名（会做 XML 转义）
     * @param mediaUrl 渲染器要拉的地址
     * @param protocolInfo 见 [protocolInfoVariants]
     * @param upnpClass 音频必须是 `object.item.audioItem.musicTrack`
     */
    fun build(title: String, mediaUrl: String, protocolInfo: String, upnpClass: String): String {
        val safeTitle = SoapClient.escape(title.ifBlank { "PureTone" })
        val safeUrl = SoapClient.escape(mediaUrl)
        return """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" """ +
            """xmlns:dc="http://purl.org/dc/elements/1.1/" """ +
            """xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">""" +
            """<item id="1" parentID="0" restricted="1">""" +
            "<dc:title>$safeTitle</dc:title>" +
            "<upnp:class>$upnpClass</upnp:class>" +
            """<res protocolInfo="$protocolInfo">$safeUrl</res>""" +
            "</item></DIDL-Lite>"
    }
}
