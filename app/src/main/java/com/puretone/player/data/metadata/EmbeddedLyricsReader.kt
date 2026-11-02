package com.puretone.player.data.metadata

import java.io.File
import java.nio.charset.Charset
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 音频内嵌歌词读取器（ID3v2 `USLT` / `SYLT` 帧）。
 *
 * ## 为什么自己解析 ID3，而不用 MediaMetadataRetriever
 *
 * `MediaMetadataRetriever` 没有「歌词」这个公开键。民间常见的
 * `extractMetadata(METADATA_KEY_WRITER)` 取 USLT 属于**未定义行为**——
 * 各厂商 ROM、各 Android 版本返回的内容不一致（可能返回作词人、也可能为空），
 * 不能作为可靠歌词来源。
 *
 * ID3v2.3/2.4 的 `USLT`（Unsynchronised lyrics）与 `SYLT`（Synchronised lyrics）
 * 帧格式是稳定的标准，直接解析字节最可靠：
 * ```
 * USLT: 编码(1) + 语言(3) + 内容描述(以 0 结尾) + 歌词文本
 * SYLT: 编码(1) + 语言(3) + 时间戳格式(1) + 内容类型(1) + 内容描述(0 结尾)
 *       + 重复的「文本(0 结尾) + 时间戳(4字节毫秒)」
 * ```
 *
 * 只解析 MP3（`ID3` 魔数开头）；其它容器（m4a/mp4 的 `©lyr`、FLAC 的 Vorbis
 * Comment）交给 [LocalLyricsProvider] 的兜底路径。
 */
@Singleton
class EmbeddedLyricsReader @Inject constructor() {

    /**
     * 从音频文件中读取内嵌歌词。
     *
     * @param audioPath 音频绝对路径
     * @return LRC 文本（含时间戳时原样返回；纯文本返回纯文本，由调用方决定是否均排）
     *         没有内嵌歌词时返回 null
     */
    fun read(audioPath: String): String? {
        if (audioPath.isBlank()) return null
        val file = File(audioPath)
        if (!file.isFile) return null
        // 只读文件头 + 若干 KB：USLT 帧通常在文件前部，不必整文件读入内存
        return runCatching {
            file.inputStream().use { input ->
                val head = ByteArray(minOf(file.length().toInt(), MAX_HEADER_BYTES))
                val read = input.read(head)
                if (read < 10) return null
                if (!isId3(head)) return null
                parseId3(head, read, input)
            }
        }.getOrNull()
    }

    /** 是否为 ID3v2 容器（`ID3` 魔数） */
    private fun isId3(head: ByteArray): Boolean =
        head.size >= 3 && head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() &&
            head[2] == '3'.code.toByte()

    /**
     * 解析 ID3v2 帧区。
     *
     * @param head 已读入的文件头
     * @param read 实际读入字节数
     * @param input 输入流（当帧区超出 head 时继续从这里读）
     */
    private fun parseId3(head: ByteArray, read: Int, input: java.io.InputStream): String? {
        val version = head[3].toInt() and 0xFF
        // 帧大小在 v2.4 用 syncsafe（每字节 7 位），v2.3 是普通大端
        val sizeBytes = head[6].toInt() and 0xFF
        val frameSize = if (version >= 4) {
            syncSafeToInt(head, 6)
        } else {
            ((head[6].toInt() and 0xFF) shl 24) or ((head[7].toInt() and 0xFF) shl 16) or
                ((head[8].toInt() and 0xFF) shl 8) or (head[9].toInt() and 0xFF)
        }
        if (frameSize <= 0 || sizeBytes !in 0..0xFF) return null

        val frameRegion = if (10 + frameSize <= read) {
            head.copyOfRange(10, 10 + frameSize)
        } else {
            val rest = ByteArray(frameSize - (read - 10).coerceAtLeast(0))
            val n = input.read(rest)
            if (n <= 0) return null
            head.copyOfRange(10, read) + rest.copyOf(n)
        }

        var offset = 0
        // 帧头长度：v2.2 = 3 字节 ID + 3 字节大小（无 flags）；
        // v2.3/v2.4 = 4 字节 ID + 4 字节大小 + **2 字节 flags** = 10 字节。
        // 漏掉 flags 会让数据区整体偏移 2 字节，歌词被截断成乱码。
        val idLen = if (version == 2) 3 else 4
        val headerLen = if (version == 2) 3 else 6
        while (offset + idLen + headerLen <= frameRegion.size) {
            val id = String(frameRegion, offset, idLen, Charsets.ISO_8859_1)
            // 遇到填充区（0x00）即结束
            if (id.firstOrNull()?.code == 0) break
            val size = if (version == 2) {
                ((frameRegion[offset + 3].toInt() and 0xFF) shl 16) or
                    ((frameRegion[offset + 4].toInt() and 0xFF) shl 8) or
                    (frameRegion[offset + 5].toInt() and 0xFF)
            } else if (version >= 4) {
                syncSafeToInt(frameRegion, offset + idLen)
            } else {
                ((frameRegion[offset + 4].toInt() and 0xFF) shl 24) or
                    ((frameRegion[offset + 5].toInt() and 0xFF) shl 16) or
                    ((frameRegion[offset + 6].toInt() and 0xFF) shl 8) or
                    (frameRegion[offset + 7].toInt() and 0xFF)
            }
            val dataStart = offset + idLen + headerLen
            if (size <= 0 || dataStart + size > frameRegion.size) break
            when (id.uppercase()) {
                "USLT" -> parseUslt(frameRegion, dataStart, size)?.let { return it }
                "SYLT" -> parseSult(frameRegion, dataStart, size)?.let { return it }
            }
            offset = dataStart + size
        }
        return null
    }

    /** USLT：编码(1) + 语言(3) + 内容描述(0 结尾) + 歌词文本 */
    private fun parseUslt(buf: ByteArray, start: Int, size: Int): String? {
        val end = start + size
        if (start >= end) return null
        val charset = charsetFor(buf[start].toInt() and 0xFF)
        var p = start + 4 // 跳过编码(1) + 语言(3)
        // 内容描述：以 0 结尾（按编码宽度可能是 1 或 2 字节）
        val descEnd = findTerminator(buf, p, end, charset) ?: return null
        p = descEnd + terminatorWidth(charset)
        if (p >= end) return null
        val text = String(buf, p, end - p, charset).trim { it == '\u0000' }
        return text.takeIf { it.isNotBlank() }
    }

    /**
     * SYLT：编码(1) + 语言(3) + 时间戳格式(1) + 内容类型(1) + 内容描述(0 结尾)
     *      + 重复的「文本(0 结尾) + 时间戳(4 字节大端毫秒)」
     */
    private fun parseSult(buf: ByteArray, start: Int, size: Int): String? {
        val end = start + size
        if (start + 6 > end) return null
        val charset = charsetFor(buf[start].toInt() and 0xFF)
        var p = start + 6
        val descEnd = findTerminator(buf, p, end, charset) ?: return null
        p = descEnd + terminatorWidth(charset)
        val sb = StringBuilder()
        while (p < end) {
            val textEnd = findTerminator(buf, p, end, charset) ?: break
            val text = String(buf, p, textEnd - p, charset)
            p = textEnd + terminatorWidth(charset)
            if (p + 4 > end) break
            val ms = ((buf[p].toInt() and 0xFF) shl 24) or ((buf[p + 1].toInt() and 0xFF) shl 16) or
                ((buf[p + 2].toInt() and 0xFF) shl 8) or (buf[p + 3].toInt() and 0xFF)
            p += 4
            if (text.isNotBlank()) sb.append('[').append(formatTimestamp(ms)).append(']').append(text).append('\n')
        }
        return sb.toString().takeIf { it.isNotBlank() }
    }

    /** ID3 文本编码字节 → 字符集（0=ISO-8859-1, 1=UTF-16带BOM, 2=UTF-16BE, 3=UTF-8） */
    private fun charsetFor(byte: Int): Charset = when (byte) {
        1 -> Charsets.UTF_16
        2 -> Charsets.UTF_16BE
        3 -> Charsets.UTF_8
        else -> Charsets.ISO_8859_1
    }

    /** UTF-16 的终止符占 2 字节，其余占 1 字节 */
    private fun terminatorWidth(charset: Charset): Int =
        if (charset == Charsets.UTF_16 || charset == Charsets.UTF_16BE) 2 else 1

    /** 找到字符串终止符位置；找不到返回 null */
    private fun findTerminator(buf: ByteArray, from: Int, end: Int, charset: Charset): Int? {
        val width = terminatorWidth(charset)
        var p = from
        while (p + width <= end) {
            if (width == 1) {
                if (buf[p].toInt() == 0) return p
            } else {
                if (buf[p].toInt() == 0 && buf[p + 1].toInt() == 0) return p
            }
            p += width
        }
        return null
    }

    /** syncsafe 整数（v2.4 帧大小：每字节只用低 7 位） */
    private fun syncSafeToInt(buf: ByteArray, offset: Int): Int {
        if (offset + 4 > buf.size) return 0
        return ((buf[offset].toInt() and 0x7F) shl 21) or
            ((buf[offset + 1].toInt() and 0x7F) shl 14) or
            ((buf[offset + 2].toInt() and 0x7F) shl 7) or
            (buf[offset + 3].toInt() and 0x7F)
    }

    /** 毫秒 → `[mm:ss.xx]` */
    private fun formatTimestamp(ms: Int): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        val cs = (ms % 1000) / 10
        return "%02d:%02d.%02d".format(min, sec, cs)
    }

    companion object {
        /** 读取上限：ID3 头 + 帧区前若干字节，足以覆盖绝大多数 USLT 帧 */
        private const val MAX_HEADER_BYTES = 512 * 1024
    }
}
