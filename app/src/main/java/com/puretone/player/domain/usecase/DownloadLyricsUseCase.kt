package com.puretone.player.domain.usecase

import com.puretone.player.data.metadata.LyricsProvider
import com.puretone.player.domain.metadata.NetworkMetadataGate
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
 *
 * P2-4：用户关闭「联网补全元数据与歌词」时，[NetworkMetadataGate] 拦下请求，
 * 不发任何出站请求。新增 [Outcome.DISABLED] 区分「被开关关闭」与「确实没歌词」——
 * 否则 UI 会显示「未找到歌词」，用户以为是自己搜错了歌名。
 */
class DownloadLyricsUseCase @Inject constructor(
    private val lyricsProvider: LyricsProvider,
    private val lyricsStore: LyricsStore,
    private val gate: NetworkMetadataGate
) {
    /** 下载结果：已保存 / 未找到歌词 / 保存失败 / 联网功能已关闭 */
    enum class Outcome { SAVED, NO_LYRICS, SAVE_FAILED, DISABLED }

    suspend operator fun invoke(
        audioPath: String,
        artist: String,
        title: String,
        durationMs: Long
    ): Outcome {
        if (audioPath.isBlank()) return Outcome.SAVE_FAILED
        if (!gate.isEnabled()) return Outcome.DISABLED
        // 传 audioPath：多渠道聚合器会先查本地（同目录 .lrc / 内嵌歌词），
        // 本地命中就不必联网——既快又省流量。
        val lrc = runCatching {
            lyricsProvider.fetchWithPath(audioPath, artist, title, durationMs)
        }.getOrNull()
        if (lrc.isNullOrBlank()) return Outcome.NO_LYRICS
        val ok = runCatching { withContext(Dispatchers.IO) { lyricsStore.save(audioPath, lrc) } }
            .getOrDefault(false)
        return if (ok) Outcome.SAVED else Outcome.SAVE_FAILED
    }
}
