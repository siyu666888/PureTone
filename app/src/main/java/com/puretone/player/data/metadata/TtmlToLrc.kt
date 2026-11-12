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
            // 该 `<p>` 的结束位置取「闭合标签」与「下一个 <p 开标签」中更靠前的那个。
            // 容错：社区投稿可能缺 </p>；旧实现只在找不到任何 </p> 时才看下一个 <p，
            // 于是缺闭合的行会一路吞到后面某一行的 </p>，把多行文本合并成一行，
            // 而被吞并的行仍会被 P_OPEN 各自遍历一次、重复输出，时间轴随之错乱。
            val closeEnd = ttml.indexOf("</p>", bodyStart).takeIf { it >= 0 } ?: Int.MAX_VALUE
            val nextOpen = ttml.indexOf("<p", bodyStart).takeIf { it >= 0 } ?: Int.MAX_VALUE
            val bodyEnd = minOf(closeEnd, nextOpen).let { if (it == Int.MAX_VALUE) ttml.length else it }
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

    /** 命名实体 + 数字字符引用（十进制 `&#39;`、十六进制 `&#x27;`） */
    private val ENTITY = Regex("""&(?:amp|lt|gt|quot|apos|#[xX][0-9a-fA-F]+|#[0-9]+);""")

    private val NAMED_ENTITIES = mapOf(
        "amp" to "&",
        "lt" to "<",
        "gt" to ">",
        "quot" to "\"",
        "apos" to "'",
    )

    /**
     * P3-5：一次性反转义 XML 实体，含数字字符引用。
     *
     * 用单个正则扫描、**只解一层**，避免链式 `replace` 造成的双重解码：
     * `&amp;#39;` 应得到字面量 `&#39;`，而不是 `'`。
     * 非法码点（越界 / UTF-16 代理区 / 整数溢出）保留原文，
     * 不吞字符也不抛异常——歌词来自社区投稿，容错优先。
     */
    internal fun decodeEntities(text: String): String = ENTITY.replace(text) { match ->
        val body = match.value.removePrefix("&").removeSuffix(";")
        when {
            body.startsWith("#x", ignoreCase = true) -> decodeCodePoint(body.substring(2), 16)
            body.startsWith("#") -> decodeCodePoint(body.substring(1), 10)
            else -> NAMED_ENTITIES[body]
        } ?: match.value
    }

    /** 码点 → 字符串；非法码点返回 null 让调用方保留原实体 */
    private fun decodeCodePoint(digits: String, radix: Int): String? {
        val code = try {
            digits.toLong(radix)
        } catch (_: NumberFormatException) {
            return null
        }
        if (code < 0 || code > Character.MAX_CODE_POINT.toLong()) return null
        if (code in 0xD800L..0xDFFFL) return null
        return try {
            String(Character.toChars(code.toInt()))
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** 剥除标签并反转义，取纯文本 */
    private fun plainText(raw: String): String = decodeEntities(ANY_TAG.replace(raw, ""))

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
