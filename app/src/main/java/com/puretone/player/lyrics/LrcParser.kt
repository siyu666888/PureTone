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
 * - 编码回退 UTF-8 → GBK（见 [decode]）。
 *
 * 增强型 LRC（逐字时间戳 `<0,120>字`）目前按普通文本保留，逐字高亮为后续能力。
 */
object LrcParser {

    private val TIME_TAG = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val META_TAG = Regex("""^\s*\[(ti|ar|al|by|offset|re|ve|length)\s*:(.*)]\s*$""", RegexOption.IGNORE_CASE)
    private val LEADING_TAGS = Regex("""^((?:\[\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?])+)(.*)$""")

    fun parse(content: String): ParsedLyrics {
        if (content.isBlank()) return ParsedLyrics.Empty

        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var offsetMs = 0L
        val lines = mutableListOf<LyricLine>()

        content.lineSequence().forEach { rawLine ->
            val line = rawLine.trimEnd('\r', ' ')

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
            // 歌词正文统一转为简体（离线字符级映射）
            val text = ChineseText.toSimplified(leadMatch.groupValues[2].trim()) ?: return@forEach
            if (text.isEmpty()) return@forEach

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
            times.forEach { timeMs ->
                lines += LyricLine(timeMs = (timeMs - offsetMs).coerceAtLeast(0L), text = text)
            }
        }

        val sorted = lines.sortedBy { it.timeMs }
        return ParsedLyrics(
            lines = sorted,
            title = title,
            artist = artist,
            album = album,
            offsetMs = offsetMs
        )
    }

    /**
     * 歌词字节解码：优先 UTF-8（可严格校验），失败回退 GBK。
     * 国内 .lrc 文件历史编码混杂，此为兼容兜底。
     */
    fun decode(bytes: ByteArray): String = try {
        val decoder = java.nio.charset.Charset.forName("UTF-8").newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    } catch (e: java.nio.charset.CharacterCodingException) {
        String(bytes, java.nio.charset.Charset.forName("GBK"))
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
