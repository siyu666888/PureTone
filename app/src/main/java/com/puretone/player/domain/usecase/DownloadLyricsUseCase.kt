package com.puretone.player.domain.usecase

import com.puretone.player.data.metadata.LyricsProvider
import com.puretone.player.lyrics.LyricsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 下载并保存歌词（domain 用例）。
 *
 * 收口 [com.puretone.player.ui.MetadataViewModel.downloadLyrics] 的核心：
 * 按「歌手 歌名」抓取 LRC，经 [LyricsStore] 存入应用外部目录。
 * 以枚举区分「无歌词 / 保存失败」，使 VM 能给出精确提示文案。
 */
class DownloadLyricsUseCase @Inject constructor(
    private val lyricsProvider: LyricsProvider,
    private val lyricsStore: LyricsStore
) {
    /** 下载结果：已保存 / 未找到歌词 / 保存失败 */
    enum class Outcome { SAVED, NO_LYRICS, SAVE_FAILED }

    suspend operator fun invoke(
        audioPath: String,
        artist: String,
        title: String,
        durationMs: Long
    ): Outcome {
        if (audioPath.isBlank()) return Outcome.SAVE_FAILED
        val lrc = runCatching { lyricsProvider.fetch(artist, title, durationMs) }.getOrNull()
        if (lrc.isNullOrBlank()) return Outcome.NO_LYRICS
        val ok = runCatching { withContext(Dispatchers.IO) { lyricsStore.save(audioPath, lrc) } }
            .getOrDefault(false)
        return if (ok) Outcome.SAVED else Outcome.SAVE_FAILED
    }
}
