package com.puretone.player.lyrics

/** M3U 条目：文件路径 + 可选标题/时长 */
data class M3uEntry(
    val path: String,
    val title: String? = null,
    val durationSec: Int = 0
)

/**
 * M3U / M3U8 歌单编解码器。
 *
 * - 解析：识别 #EXTM3U 头、#EXTINF:<秒>,<标题>、忽略其余 # 注释；
 * - 序列化：输出标准 M3U（UTF-8 无 BOM）；
 * - 相对路径由调用方结合歌单文件所在目录解析，本类不做 IO。
 */
object M3uCodec {

    fun parse(content: String): List<M3uEntry> {
        val entries = mutableListOf<M3uEntry>()
        var pendingTitle: String? = null
        var pendingDuration = 0

        content.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach

            if (line.startsWith("#EXTINF:", ignoreCase = true)) {
                val body = line.substringAfter(':')
                val commaIdx = body.indexOf(',')
                if (commaIdx >= 0) {
                    pendingDuration = body.substring(0, commaIdx).trim().toIntOrNull() ?: 0
                    val label = body.substring(commaIdx + 1).trim().ifBlank { null }
                    // 时长为 0 表示导出方未记录时长，该 EXTINF 标签不作为可信标题
                    // （与 serialize 的 `#EXTINF:0` 无标签输出保持一致，保证往返稳定）
                    pendingTitle = label.takeIf { pendingDuration > 0 }
                } else {
                    pendingDuration = body.trim().toIntOrNull() ?: 0
                    pendingTitle = null
                }
                return@forEach
            }

            if (line.startsWith("#")) return@forEach // #EXTM3U / #EXTGRP 等

            entries += M3uEntry(
                path = line,
                title = pendingTitle,
                durationSec = pendingDuration
            )
            pendingTitle = null
            pendingDuration = 0
        }
        return entries
    }

    fun serialize(entries: List<M3uEntry>): String = buildString {
        append("#EXTM3U\n")
        entries.forEach { entry ->
            append("#EXTINF:")
            append(entry.durationSec.coerceAtLeast(0))
            if (!entry.title.isNullOrBlank()) {
                append(',')
                append(entry.title)
            }
            append('\n')
            append(entry.path)
            append('\n')
        }
    }
}
