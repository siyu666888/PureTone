package com.puretone.player.lyrics

/** M3U 条目：文件路径 + 可选标题/时长（秒，可为小数，如 273.5） */
data class M3uEntry(
    val path: String,
    val title: String? = null,
    val durationSec: Double = 0.0
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
        var pendingDuration = 0.0

        content.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach

            if (line.startsWith("#EXTINF:", ignoreCase = true)) {
                val body = line.substringAfter(':')
                val commaIdx = body.indexOf(',')
                if (commaIdx >= 0) {
                    // P2-7：保留小数时长（EXTINF 允许 273.5 这类秒数），负数视为 0；
                    // 标题无论时长是否为 0 都保留，保证 serialize/parse 往返自洽。
                    pendingDuration = body.substring(0, commaIdx).trim().toDoubleOrNull()
                        ?.coerceAtLeast(0.0) ?: 0.0
                    val label = body.substring(commaIdx + 1).trim().ifBlank { null }
                    pendingTitle = label
                } else {
                    pendingDuration = body.trim().toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
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
            pendingDuration = 0.0
        }
        return entries
    }

    fun serialize(entries: List<M3uEntry>): String = buildString {
        append("#EXTM3U\n")
        entries.forEach { entry ->
            append("#EXTINF:")
            // 整数秒原样输出，小数秒保留精度；保证 parse 后再 serialize 稳定（P2-7）
            val d = entry.durationSec.coerceAtLeast(0.0)
            append(if (d % 1.0 == 0.0) d.toLong().toString() else d.toString())
            if (!entry.title.isNullOrBlank()) {
                append(',')
                // P3-10：标题里的换行（\r/\n）会让本行被拆成多条记录、污染后续条目；
                // 统一替换为空格，保证 serialize → parse 往返不破坏格式与条目数量。
                append(escapeTitle(entry.title))
            }
            append('\n')
            append(entry.path)
            append('\n')
        }
    }

    /** 把标题中的换行替换为空格（P3-10），避免其被 M3U 行结构误判为新条目 */
    private fun escapeTitle(title: String): String = title.replace(Regex("[\r\n]"), " ")
}
