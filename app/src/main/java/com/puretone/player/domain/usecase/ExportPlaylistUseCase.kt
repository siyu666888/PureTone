package com.puretone.player.domain.usecase

import com.puretone.player.data.playlist.M3uPlaylistStore
import com.puretone.player.data.repository.MusicRepository
import com.puretone.player.lyrics.M3uEntry
import javax.inject.Inject

/**
 * 导出歌单为 M3U 文件（domain 用例）。
 *
 * 收口 [com.puretone.player.ui.LibraryViewModel.exportPlaylist] 的编排：
 * 取歌单快照 → 组装 M3uEntry（标题/歌手拼接）→ 写外部目录。
 * VM 只消费返回的提示文案。
 */
class ExportPlaylistUseCase @Inject constructor(
    private val repo: MusicRepository,
    private val m3uStore: M3uPlaylistStore
) {
    suspend operator fun invoke(playlistId: Long): String {
        val pw = runCatching { repo.getPlaylistWithSongs(playlistId) }.getOrNull()
            ?: return "歌单不存在"
        if (pw.songs.isEmpty()) return "歌单为空，无可导出歌曲"
        val entries = pw.songs.map { s ->
            val title = buildString {
                if (s.title.isNotBlank()) append(s.title.trim())
                if (s.artist.isNotBlank()) {
                    if (isNotEmpty()) append(" - ")
                    append(s.artist.trim())
                }
            }.takeIf { it.isNotBlank() }
            M3uEntry(path = s.path, title = title, durationSec = s.duration / 1000.0)
        }
        val file = runCatching { m3uStore.export(pw.playlist.name, entries) }.getOrNull()
        return if (file != null) "已导出：${file.absolutePath}" else "导出失败"
    }
}
