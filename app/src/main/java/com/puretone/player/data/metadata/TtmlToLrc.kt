package com.puretone.player.data.metadata

/**
 * TTML（逐词歌词）→ LRC（逐行歌词）转换器。
 *
 * ## 为什么需要转换
 *
 * AMLL 词库提供的是 TTML 格式的**逐词**歌词：
 * ```xml
 * <p begin="00:29.231" end="00:32.723">
 *   <span begin="00:29.231" end="00:29.692">故</span>
 *   <span begin="00:29.692" end="00:30.057">事</span>
 * </p>
 * ```
 * 而本应用的播放页消费标准 LRC（行级时间戳 + 纯文本），两者模型不同。
 *
 * ## 转换策略
 *
 * 以 `<p>`（歌词行）为单位：取 `begin` 作为该行的时间戳，把该行内所有
 * `<span>` 的文本按顺序拼接作为行内容，输出 `[mm:ss.xx]文本`。
 *
 * 逐词的精细时间轴（`<span>` 上的 begin/end）在 LRC 里无处安放，故丢弃。
 * 这是**有损**转换，但换来的是与现有播放页完全兼容的逐行高亮——
 * 当前播放页的能力边界本来就是逐行高亮，逐词高亮属后续能力。
 *
 * ## 容错
 *
 * TTML 由社区投稿，格式可能有出入，故解析必须宽容：
 * - 时间戳可能是 `00:29.231`、`00:29.23`、`29.231`（缺小时部分）等写法；
 * - `<p>` 可能没有 `begin`（此时退回用第一个 `<span>` 的 begin）；
 * - 行内可能有 `<span>` 之外的直接文本节点，一并保留。
 */
object TtmlToLrc {

    /** 匹配一个 `<p ...>` 开标签，捕获属性 */
    private val P_OPEN = Regex("""<p\s+([^>]*)>""", RegexOption.IGNORE_CASE)

    /** 匹配一个 `<span ...>` 开标签，捕获属性 */
    private val SPAN_OPEN = Regex("""<span\s+([^>]*)>""", RegexOption.IGNORE_CASE)

    /** 匹配任意标签（用于剥除） */
    private val ANY_TAG = Regex("""</?[A-Za-z:][^>]*>""")

    /** 属性 `begin="..."` */
    private val ATTR_BEGIN = Regex("""begin\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)

    /**
     * 把 TTML 转为 LRC。
     *
     * @param ttml TTML 全文
     * @return LRC 文本；解析不出任何歌词行时返回 null
     */
    fun convert(ttml: String): String? {
        if (ttml.isBlank()) return null

        val sb = StringBuilder()
        var count = 0

        for (match in P_OPEN.findAll(ttml)) {
            val attrs = match.groupValues[1]
            val bodyStart = match.range.last + 1
            // 该 `<p>` 的结束位置：下一个 `</p>` 或下一个 `<p`（容错：可能缺闭合标签）
            val bodyEnd = ttml.indexOf("</p>", bodyStart).takeIf { it >= 0 }
                ?: ttml.indexOf("<p", bodyStart).takeIf { it >= 0 }
                ?: ttml.length
            if (bodyEnd <= bodyStart) continue

            val body = ttml.substring(bodyStart, bodyEnd)
            val text = extractLineText(body)
            if (text.isBlank()) continue

            val timeMs = parseTime(ATTR_BEGIN.find(attrs)?.groupValues?.get(1))
                ?: parseTime(ATTR_BEGIN.find(body)?.groupValues?.get(1))
                ?: continue

            sb.append('[').append(formatTimestamp(timeMs)).append(']').append(text).append('\n')
            count++
        }

        return if (count == 0) null else sb.toString()
    }

    /**
     * 取出 `<p>` 内的歌词文本。
     *
     * 逐个 `<span>` 取其文本并拼接；`<span>` 之外的裸文本也保留
     * （少数投稿把整行文字直接放在 `<p>` 里没有拆 span）。
     */
    private fun extractLineText(body: String): String {
        val sb = StringBuilder()
        var cursor = 0
        for (match in SPAN_OPEN.findAll(body)) {
            // span 之前的裸文本
            if (match.range.first > cursor) {
                sb.append(plainText(body.substring(cursor, match.range.first)))
            }
            val contentStart = match.range.last + 1
            val contentEnd = body.indexOf("</span>", contentStart).takeIf { it >= 0 } ?: body.length
            sb.append(plainText(body.substring(contentStart, contentEnd)))
            cursor = contentEnd
        }
        if (cursor < body.length) {
            sb.append(plainText(body.substring(cursor)))
        }
        return sb.toString().trim()
    }

    /** 剥除标签并反转义，取纯文本 */
    private fun plainText(raw: String): String =
        ANY_TAG.replace(raw, "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")

    /**
     * 解析 TTML 时间戳 → 毫秒。
     *
     * 支持 `hh:mm:ss.mmm` / `mm:ss.mmm` / `ss.mmm`（缺位按 0 处理）。
     */
    fun parseTime(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.trim().split(':')
        if (parts.isEmpty() || parts.size > 3) return null
        return try {
            when (parts.size) {
                3 -> {
                    val h = parts[0].trim().toLong()
                    val m = parts[1].trim().toLong()
                    val s = parseSeconds(parts[2])
                    h * 3_600_000 + m * 60_000 + s
                }
                2 -> {
                    val m = parts[0].trim().toLong()
                    val s = parseSeconds(parts[1])
                    m * 60_000 + s
                }
                else -> parseSeconds(parts[0])
            }
        } catch (_: NumberFormatException) {
            null
        }
    }

    /** 秒数 → 毫秒（小数部分按位数折算：`.2`=200ms、`.23`=230ms、`.231`=231ms） */
    private fun parseSeconds(raw: String): Long {
        val text = raw.trim()
        val dot = text.indexOf('.')
        if (dot < 0) return text.toLong() * 1000
        val sec = text.substring(0, dot).toLong()
        val frac = text.substring(dot + 1)
        val fracMs = when (frac.length) {
            0 -> 0L
            1 -> frac.toLong() * 100
            2 -> frac.toLong() * 10
            else -> frac.take(3).toLong()
        }
        return sec * 1000 + fracMs
    }

    /** 毫秒 → `[mm:ss.xx]` */
    private fun formatTimestamp(ms: Long): String {
        val total = ms.coerceAtLeast(0)
        val minutes = total / 60_000
        val seconds = (total % 60_000) / 1000
        val centis = (total % 1000) / 10
        return "%02d:%02d.%02d".format(minutes, seconds, centis)
    }
}
