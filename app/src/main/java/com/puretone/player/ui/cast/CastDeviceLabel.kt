package com.puretone.player.ui.cast

/**
 * 投屏设备名的展示层清洗。
 *
 * SSDP / UPnP 上报的 friendlyName 经常把 IP 直接拼进名字，例如
 * `623(192.168.101.20)`。对普通用户而言括号里的地址没有意义，却会把状态胶囊撑得
 * 又长又歪。胶囊只显示可识别的短名（如「623」），完整名与 IP 放到设备详情二级页。
 *
 * 刻意只做「展示层」清洗：不改 [com.puretone.player.playback.cast.CastState]、不改
 * 投屏历史、不做用户重命名，避免为了一行文案去动持久化与设备选择链路。
 */
object CastDeviceLabel {

    /** 仅剥离「括号里含 IPv4」的片段，保留其它括号内容（如「音箱（客厅）」）。 */
    private val ParenthesizedIp = Regex("""\s*[（(][^）)]*\d{1,3}(?:\.\d{1,3}){3}[^）)]*[）)]""")

    /** 名字里没有括号、直接裸奔的 IPv4。 */
    private val BareIpv4 = Regex("""\b\d{1,3}(?:\.\d{1,3}){3}\b""")

    private val ExtraWhitespace = Regex("""\s+""")

    /** 清洗后首尾残留的连接符 / 顿号 / 竖线等装饰符。 */
    private val EdgeSeparators = Regex("""^[\s·・\-—_、,，/|]+|[\s·・\-—_、,，/|]+$""")

    /**
     * 生成状态胶囊用的短设备名。
     *
     * @param raw SSDP 上报的原始 friendlyName（可能为 null / 纯 IP / 全角括号）
     * @param fallback 清洗后为空时的兜底文案（调用方传本地化的「投屏」）
     */
    fun short(raw: String?, fallback: String): String {
        if (raw.isNullOrBlank()) return fallback
        val withoutParentheses = ParenthesizedIp.replace(raw, "")
        val withoutIp = BareIpv4.replace(withoutParentheses, " ")
        val normalized = ExtraWhitespace.replace(withoutIp, " ").trim()
        val cleaned = EdgeSeparators.replace(normalized, "")
        return cleaned.ifBlank { fallback }
    }
}
