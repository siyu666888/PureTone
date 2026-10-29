package com.puretone.player.domain.usecase

import com.puretone.player.lyrics.LrcParser
import com.puretone.player.lyrics.LyricsStore
import com.puretone.player.lyrics.ParsedLyrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * 加载歌词（domain 用例）。
 *
 * 收口 [com.puretone.player.ui.PlayerViewModel.loadLyrics] 的纯逻辑：
 * 优先同目录同名 .lrc（大小写各一），其次 App 外部目录下载的歌词（[LyricsStore]）。
 * 找不到或解析失败返回 null（UI 据此显示「暂无歌词」）。
 *
 * 内部切 [Dispatchers.IO]，调用方无需关心线程。
 */
class LoadLyricsUseCase @Inject constructor(
    private val lyricsStore: LyricsStore
) {
    suspend operator fun invoke(audioPath: String?): ParsedLyrics? {
        if (audioPath.isNullOrBlank()) return null
        return withContext(Dispatchers.IO) {
            val file = File(audioPath)
            val dir = file.parentFile
            val base = file.nameWithoutExtension
            val candidates = buildList {
                if (dir != null) {
                    add(File(dir, "$base.lrc"))
                    add(File(dir, "$base.LRC"))
                }
                lyricsStore.existing(audioPath)?.let { add(it) }
            }
            val lrc = candidates.firstOrNull { it.exists() && it.isFile }
            if (lrc != null) {
                runCatching { LrcParser.parse(LrcParser.decode(lrc.readBytes())) }
                    .getOrDefault(ParsedLyrics.Empty)
            } else {
                null
            }
        }
    }
}
