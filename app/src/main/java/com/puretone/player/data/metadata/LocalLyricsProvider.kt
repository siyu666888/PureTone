package com.puretone.player.data.metadata

import com.puretone.player.lyrics.LrcParser
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 本地歌词渠道：音频文件内嵌歌词 + 同目录同名 .lrc。
 *
 * ## 为什么放在网络渠道之前
 *
 * 联网下载歌词总会失败——服务端限流、境外接口抖动、曲名对不上、元数据缺失……
 * 但**文件里往往已经有歌词**了：用户早就下好了 .lrc 放在歌旁边，或从某个播放器
 * 转换过来的音频自带内嵌歌词。这两条渠道完全不依赖网络，命中率高、零延迟、
 * 零隐私风险，应当优先于任何联网请求。
 *
 * 两条渠道的先后：
 * 1. **同目录同名 .lrc**（`<base>.lrc` / `<base>.LRC`）：用户手动放的文件，
 *    通常是校准过的同步版，质量最高；
 * 2. **音频内嵌歌词**：ID3 的 `USLT`（未同步纯文本）或 Vorbis Comment 的
 *    `LYRICS`（可能带时间戳）。读取用系统 [MediaMetadataRetriever]，
 *    不引入任何新依赖。
 *
 * 内嵌歌词若带时间戳则原样返回；纯文本则按歌曲时长均排为伪 LRC，
 * 与 [LrcLibLyricsProvider] 的处理一致，保证播放页高亮可用。
 */
@Singleton
class LocalLyricsProvider @Inject constructor(
    private val embeddedReader: EmbeddedLyricsReader
) {

    /**
     * 取本地歌词（LRC 文本）。
     *
     * @param audioPath 音频绝对路径（用于找同名 .lrc 与读取内嵌标签）
     * @param durationMs 歌曲时长，用于把纯文本歌词均排为伪 LRC
     * @return LRC 文本；本地没有任何歌词时返回 null
     */
    fun fetch(audioPath: String, durationMs: Long): String? {
        if (audioPath.isBlank()) return null
        sidecarLrc(audioPath)?.let { return it }
        return embedded(audioPath, durationMs)
    }

    /** 同目录同名 .lrc（大小写各一）；找到返回其内容 */
    private fun sidecarLrc(audioPath: String): String? {
        val file = File(audioPath)
        val dir = file.parentFile ?: return null
        val base = file.nameWithoutExtension
        val candidates = listOf(File(dir, "$base.lrc"), File(dir, "$base.LRC"))
        return candidates.firstOrNull { it.isFile && it.length() > 0 }
            ?.let { runCatching { LrcParser.decode(it.readBytes()) }.getOrNull() }
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * 音频内嵌歌词。
     *
     * 走 [EmbeddedLyricsReader] 直接解析 ID3v2 的 `USLT` / `SYLT` 帧——
     * 比 `MediaMetadataRetriever` 的未定义键可靠得多（详见该类注释）。
     *
     * 内嵌歌词若带时间戳则原样返回；纯文本则按歌曲时长均排为伪 LRC，
     * 与 [LrcLibLyricsProvider] 的处理一致，保证播放页高亮可用。
     */
    private fun embedded(audioPath: String, durationMs: Long): String? {
        val raw = embeddedReader.read(audioPath) ?: return null
        return normalize(raw, durationMs).takeIf { it.isNotBlank() }
    }

    /**
     * 规范化歌词文本：带时间戳的原样返回；纯文本按时长均排为伪 LRC。
     *
     * 与 [LrcLibLyricsProvider.plainToLrc] 同策略，但这里不重复依赖那个类的
     * 私有方法（它是 private），故独立实现一份极简版。
     */
    private fun normalize(text: String, durationMs: Long): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        // 已含时间戳标记 → 原样返回
        if (trimmed.contains('[') && trimmed.lines().any { LRC_TIMESTAMP.matches(it.trim()) }) {
            return trimmed
        }
        val lines = trimmed.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return ""
        val perLine = if (durationMs > 0) durationMs / lines.size else 3500L
        return buildString {
            lines.forEachIndexed { i, line ->
                append('[').append(LrcParser.formatTime(i * perLine)).append(']').append(line).append('\n')
            }
        }
    }

    companion object {
        /** 形如 `[mm:ss.xx]` / `[mm:ss:xx]` / `[mm:ss]` 的时间戳 */
        private val LRC_TIMESTAMP = Regex("""^\[((\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?)\]""")
    }
}
