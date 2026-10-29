package com.puretone.player.domain.usecase

import android.net.Uri
import com.puretone.player.data.playlist.M3uPlaylistStore
import com.puretone.player.data.playlist.PlaylistImportResolver
import com.puretone.player.data.repository.MusicRepository
import javax.inject.Inject

/**
 * 导入 M3U 歌单（domain 用例）。
 *
 * 把 [com.puretone.player.ui.LibraryViewModel.importM3u] 里的编排逻辑
 * 收口到本用例：解析文件 → 与曲库匹配 → 命名 → 建歌单 → 加歌。
 * VM 不再直接持有 [M3uPlaylistStore]，只调本用例并消费返回的提示文案。
 *
 * [PlaylistImportResolver] 是纯函数 object 单例，直接调用无需注入。
 *
 * 返回 UI 提示文案（成功或失败），匹配不到任何歌曲也给出明确提示，不静默失败。
 */
class ImportM3uPlaylistUseCase @Inject constructor(
    private val m3uStore: M3uPlaylistStore,
    private val repo: MusicRepository
) {
    suspend operator fun invoke(uri: Uri): String {
        val entries = runCatching { m3uStore.import(uri) }.getOrNull()
        if (entries == null || entries.isEmpty()) return "无法读取或非空歌单文件"
        val library = runCatching { repo.getAllSongs() }.getOrDefault(emptyList())
        val matched = PlaylistImportResolver.resolve(entries, library)
        if (matched.isEmpty()) return "未匹配到任何歌曲（共 ${entries.size} 条）"
        val name = runCatching { m3uStore.nameOf(uri) }.getOrNull() ?: "导入歌单"
        val id = repo.createPlaylist(name)
        repo.addToPlaylist(id, matched.map { it.id })
        return "已导入歌单「$name」：匹配 ${matched.size}/${entries.size} 首"
    }
}
