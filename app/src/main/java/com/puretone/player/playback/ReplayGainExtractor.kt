package com.puretone.player.playback

import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.MediaInformation
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** 从 ffprobe 提取到的 ReplayGain 标签（dB，null = 无标签） */
data class ReplayGainTags(
    val trackGainDb: Float? = null,
    val albumGainDb: Float? = null
)

/**
 * ReplayGain 标签提取器（基于 ffmpeg-kit 的 FFprobeKit）。
 *
 * 标签位置因封装而异：MP3 / AAC 的 APEv2 / ID3 多落在「格式级」tags（format.tags），
 * FLAC / Ogg / Opus 的 Vorbis 注释落在「音频流级」tags（streams[].tags）。两者都查，
 * 先格式级后流级，命中即返回。
 *
 * 仅读取标准 REPLAYGAIN_TRACK_GAIN / REPLAYGAIN_ALBUM_GAIN。Apple 的 iTunNORM
 * 是另一套非标准归一化，暂不转换（留待后续）。
 *
 * [extract] 为阻塞式（ffprobe 同步调用），调用方须切 IO 线程；[parseGainDb] 为纯函数，可单测。
 */
@Singleton
class ReplayGainExtractor @Inject constructor() {

    /**
     * 提取单文件 ReplayGain 标签（阻塞，须 IO 线程）。
     * 返回 null 表示 ffprobe 执行失败或文件不可读；返回 [ReplayGainTags]（两字段均可空）
     * 表示执行成功（可能两字段都为空 —— 文件无标签）。
     */
    fun extract(path: String): ReplayGainTags? {
        val session = runCatching { FFprobeKit.getMediaInformation(path) }.getOrNull() ?: return null
        val info: MediaInformation = session.mediaInformation ?: return null
        val track = findGain(info, "REPLAYGAIN_TRACK_GAIN")
        val album = findGain(info, "REPLAYGAIN_ALBUM_GAIN")
        return ReplayGainTags(trackGainDb = track, albumGainDb = album)
    }

    private fun findGain(info: MediaInformation, key: String): Float? {
        // 1) 格式级 tags（MP3/AAC 的 APEv2/ID3 标签）
        readTag(info.tags, key)?.let { return it }
        // 2) 各音频流级 tags（FLAC/Ogg/Opus 的 Vorbis 注释）
        info.streams.orEmpty().forEach { stream ->
            readTag(stream.tags, key)?.let { return it }
        }
        return null
    }

    private fun readTag(tags: JSONObject?, key: String): Float? {
        if (tags == null || !tags.has(key)) return null
        return parseGainDb(tags.optString(key))
    }

    companion object {
        /**
         * 解析 ReplayGain 增益值（纯函数，可单测）。
         * 输入形如 "-6.42 dB" / "+3.0" / "0" / "  -2,1 dB"（部分工具用逗号小数点）；
         * 仅保留可选负号 + 数字 + 首个小数点，剔除单位、空格、逗号（逗号按小数点处理）。
         * 无法解析时返回 null。
         */
        fun parseGainDb(raw: String?): Float? {
            if (raw.isNullOrBlank()) return null
            val sb = StringBuilder()
            var seenDigit = false
            var seenDot = false
            for (c in raw) {
                when {
                    c == '-' && sb.isEmpty() -> sb.append(c)
                    c.isDigit() -> {
                        sb.append(c)
                        seenDigit = true
                    }
                    (c == '.' || c == ',') && seenDigit && !seenDot -> {
                        sb.append('.')
                        seenDot = true
                    }
                }
            }
            if (!seenDigit) return null
            return runCatching { sb.toString().toFloat() }.getOrNull()
        }
    }
}
