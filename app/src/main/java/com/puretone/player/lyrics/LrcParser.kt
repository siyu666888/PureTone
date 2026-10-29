package com.puretone.player.lyrics

import com.puretone.player.util.ChineseText

/** 单行歌词 */
data class LyricLine(
    /** 起始时间（毫秒） */
    val timeMs: Long,
    val text: String
)

/**
 * 解析后的歌词。
 *
 * @param offsetMs [offset] 标签（毫秒），正值使歌词提前出现
 */
data class ParsedLyrics(
    val lines: List<LyricLine>,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val offsetMs: Long = 0L
) {
    /** 当前位置所在行索引；无匹配时返回 -1 */
    fun lineIndexAt(positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        var low = 0
        var high = lines.lastIndex
        var result = -1
        while (low <= high) {
            val mid = (low + high) / 2
            if (lines[mid].timeMs <= positionMs) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result
    }

    companion object {
        val Empty = ParsedLyrics(lines = emptyList())
    }
}

/**
 * LRC 歌词解析器。
 *
 * 支持：
 * - 单行多时间戳 `[00:12.34][01:23.45]歌词`；
 * - 元信息标签 ti / ar / al / by / offset；
 * - 毫秒三位或厘秒两位小数；
 * - BOM：UTF-8 / UTF-16LE / UTF-16BE（见 [decode]）；
 * - 编码回退链：BOM → 严格 UTF-8 → GBK → GB18030（见 [decode]）；
 * - 增强型 LRC 逐字标签 `<0,120>` 解析时剥除（不显示给用户）。
 *
 * 增强型 LRC 的逐字高亮为后续能力，当前至少保证不把时间标签当正文显示。
 */
object LrcParser {

    private val TIME_TAG = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val META_TAG = Regex("""^\s*\[(ti|ar|al|by|offset|re|ve|length)\s*:(.*)]\s*$""", RegexOption.IGNORE_CASE)
    private val LEADING_TAGS = Regex("""^((?:\[\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?])+)(.*)$""")

    /** 增强型 LRC 逐字时间标签 `<偏移,时长>`（可带第三个标志位），展示前剥除 */
    private val ENHANCED_TAG = Regex("""<\d+,\d+(?:,\d+)?>""")

    /** UTF-8 BOM（部分编辑器保存 .lrc 时会带上） */
    private const val BOM = "\uFEFF"

    fun parse(content: String): ParsedLyrics {
        if (content.isBlank()) return ParsedLyrics.Empty

        // 直接传字符串的调用方（测试/未来入口）也可能带 BOM：
        // 不剥掉的话第一行（常是 [ti:] 或第一句歌词）会被正则判为不匹配而丢弃
        val body = content.removePrefix(BOM)

        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var offsetMs = 0L
        // 两遍解析（P2-9）：先收集 (原始时间, 文本)，遍历结束后再统一减 offset。
        // 否则 offset 标签出现在歌词行之后时，之前的行会以 offset=0 计算，导致时间轴整体错位。
        val rawLines = mutableListOf<Pair<Long, String>>()

        body.lineSequence().forEach { rawLine ->
            val line = rawLine.trimEnd('\r', ' ').removePrefix(BOM)

            META_TAG.find(line)?.let { match ->
                val key = match.groupValues[1].lowercase()
                val value = match.groupValues[2].trim()
                when (key) {
                    "ti" -> title = ChineseText.toSimplified(value)
                    "ar" -> artist = ChineseText.toSimplified(value)
                    "al" -> album = ChineseText.toSimplified(value)
                    "offset" -> offsetMs = value.trim().toLongOrNull() ?: 0L
                }
                return@forEach
            }

            val leadMatch = LEADING_TAGS.find(line) ?: return@forEach
            // 先剥增强型逐字标签再转简体：<0,120>你<120,90>好 → 你好
            val stripped = ENHANCED_TAG.replace(leadMatch.groupValues[2], "")
            // 歌词正文统一转为简体（离线字符级映射）
            val text = ChineseText.toSimplified(stripped.trim())
            if (text.isNullOrEmpty()) return@forEach

            val times = TIME_TAG.findAll(leadMatch.groupValues[1]).map { match ->
                val minutes = match.groupValues[1].toLong()
                val seconds = match.groupValues[2].toLong()
                val fraction = match.groupValues[3]
                val fractionMs = when (fraction.length) {
                    0 -> 0L
                    1 -> fraction.toLong() * 100      // .5 -> 500ms
                    2 -> fraction.toLong() * 10       // .34 -> 340ms
                    else -> fraction.toLong()         // .345 -> 345ms
                }
                minutes * 60_000 + seconds * 1_000 + fractionMs
            }.toList()

            if (times.isEmpty()) return@forEach
            times.forEach { timeMs -> rawLines += timeMs to text }
        }

        val lines = rawLines.map { (timeMs, text) ->
            LyricLine(timeMs = (timeMs - offsetMs).coerceAtLeast(0L), text = text)
        }.sortedBy { it.timeMs }

        return ParsedLyrics(
            lines = lines,
            title = title,
            artist = artist,
            album = album,
            offsetMs = offsetMs
        )
    }

    /**
     * 歌词字节解码（审查报告 P2-9）：
     * 1. 有 BOM 按 BOM 判定（UTF-8 / UTF-16LE / UTF-16BE，Windows 编辑器常见）；
     * 2. 无 BOM 依次尝试**严格**解码：UTF-8 → GBK。
     *    严格模式（REPORT）保证解码失败就换下一个，不会用 REPLACE 产出乱码 '?'；
     * 3. GB18030 覆盖全部字节序列，作为最终兜底（不会抛异常）。
     *
     * 已知取舍：GBK 与 Big5 的字节域大面积重叠（Big5 字节用 GBK 也能"解码成功"
     * 但内容是错字），无统计模型无法可靠区分。本App用户面以简体为主，
     * 故 GBK 优先；Big5 歌词会经 GBK/GB18030 兜底（可能乱码）——BOM 与
     * UTF-8 路径覆盖绝大多数现代歌词文件。
     */
    fun decode(bytes: ByteArray): String {
        // 1) BOM 判定
        when {
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
                return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        // 2) 无 BOM：严格解码候选链
        for (name in listOf("UTF-8", "GBK")) {
            runCatching { strictDecode(bytes, name) }.getOrNull()?.let { return it }
        }
        // 3) GB18030 兜底（映射全部字节，不会失败）
        return String(bytes, java.nio.charset.Charset.forName("GB18030"))
    }

    private fun strictDecode(bytes: ByteArray, charsetName: String): String {
        val decoder = java.nio.charset.Charset.forName(charsetName).newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        return decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    }

    /** 毫秒转 LRC 时间戳（导出/调试用） */
    fun formatTime(ms: Long): String {
        val total = ms.coerceAtLeast(0)
        val minutes = total / 60_000
        val seconds = (total % 60_000) / 1_000
        val centis = (total % 1_000) / 10
        return "%02d:%02d.%02d".format(minutes, seconds, centis)
    }
}
