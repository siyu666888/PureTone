package com.puretone.player.data.playlist

import com.puretone.player.data.local.entity.SongEntity
import com.puretone.player.lyrics.M3uEntry

/**
 * M3U 导入解析器：把歌单文件条目匹配为曲库歌曲。
 *
 * 匹配优先级：
 * 1. 绝对路径完全一致；
 * 2. 路径以相同片段结尾（处理导出方/导入方挂载点不同，如 /sdcard vs /storage/emulated/0）；
 * 3. 文件名（不含扩展名）唯一匹配。
 *
 * 纯 JVM 逻辑，可单测。
 */
object PlaylistImportResolver {

    fun resolve(entries: List<M3uEntry>, library: List<SongEntity>): List<SongEntity> {
        if (entries.isEmpty() || library.isEmpty()) return emptyList()

        val byPath = library.associateBy { it.path }
        val bySuffix = library.groupBy { it.path }
        val byName = library.groupBy { fileNameKey(it.path) }

        val result = mutableListOf<SongEntity>()
        entries.forEach { entry ->
            val normalized = entry.path.replace('\\', '/')
            // 后缀 tier 同样必须唯一：裸文件名（a.mp3）会同时命中多个目录下的同名曲库文件，
            // 此时必须判歧义跳过，而不是静默匹配第一首（导入错歌）
            // P2-12：必须以「路径段边界」结尾匹配。旧实现裸 endsWith 会让条目
            // "music/a.mp3" 命中曲库里的 ".../mymusic/a.mp3"（目录名恰好同后缀），
            // 且因为唯一而静默导入——导进来的是另一首歌。
            val suffixMatches = bySuffix.entries.filter { (libPath, _) ->
                libPath == normalized ||
                    libPath.endsWith("/$normalized") ||
                    normalized.endsWith("/$libPath")
            }
            val match = byPath[normalized]
                ?: suffixMatches.singleOrNull()?.value?.firstOrNull()
                ?: byName[fileNameKey(normalized)]?.takeIf { it.size == 1 }?.firstOrNull()
                ?: return@forEach
            if (result.none { it.id == match.id }) result += match
        }
        return result
    }

    private fun fileNameKey(path: String): String =
        path.substringAfterLast('/').substringBeforeLast('.').lowercase()
}
