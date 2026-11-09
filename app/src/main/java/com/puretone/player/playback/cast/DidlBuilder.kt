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

    /** 非法 MIME 的兜底值：宁可让渲染器按二进制流处理，也不能破坏 DIDL 属性结构 */
    internal const val FALLBACK_MIME = "application/octet-stream"

    /** protocolInfo 第 3 段只能是单段 `type/subtype`，不允许 `;` / `"` / 空格 */
    private val SAFE_MIME = Regex("""[\w.+-]+/[\w.+-]+""")

    /**
     * P3-2：校验 MIME 能否安全拼进 XML 属性，非法值回退到 [FALLBACK_MIME]。
     *
     * `CastSourcePolicy.mimeTypeOf()` 给的是内部常量表，正常路径一定合法；
     * 但本类是 internal API，未来若接入外部传入的 MIME，含 `"` / `;` / 空格的
     * 值会直接破坏 `protocolInfo="…"` 的属性结构，甚至让整条 SOAP 变成非法 XML。
     * 这里**回退而非抛异常**：投屏链路不该因为一个脏 MIME 就崩掉，
     * 回退后诊断里的 `own-seturi … pi=` 前缀仍能定位到异常来源。
     */
    internal fun safeMimeType(mimeType: String): String {
        val trimmed = mimeType.trim()
        return if (SAFE_MIME.matches(trimmed)) trimmed else FALLBACK_MIME
    }

    /**
     * 按「保守 → 完整」顺序给出 protocolInfo 候选。
     *
     * 1. 最小形态 `http-get:*:<mime>:*`——大部分接收端只认这个（也是库的写法）；
     * 2. 带 DLNA 参数——严格的接收端要求 `DLNA.ORG_OP` 等能力位。
     */
    fun protocolInfoVariants(mimeType: String): List<String> {
        val safe = safeMimeType(mimeType)
        return listOf(
            "http-get:*:$safe:*",
            "http-get:*:$safe:DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=$DLNA_FLAGS"
        )
    }

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
        // protocolInfo 是属性而非文本节点：引号/尖括号必须单独转义，
        // 否则即便 protocolInfoVariants 已 sanitize，直接调用本方法仍可能注入非法 XML。
        val safeProtocolInfo = SoapClient.escape(protocolInfo)
        return """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" """ +
            """xmlns:dc="http://purl.org/dc/elements/1.1/" """ +
            """xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">""" +
            """<item id="1" parentID="0" restricted="1">""" +
            "<dc:title>$safeTitle</dc:title>" +
            "<upnp:class>$upnpClass</upnp:class>" +
            """<res protocolInfo="$safeProtocolInfo">$safeUrl</res>""" +
            "</item></DIDL-Lite>"
    }
}
