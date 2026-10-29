package com.puretone.player.domain.usecase

import com.puretone.player.data.repository.MusicRepository
import javax.inject.Inject

/**
 * 新建歌单并立即把一批歌曲加入末尾（domain 用例）。
 *
 * 收口 [com.puretone.player.ui.LibraryViewModel.createPlaylistAndAdd] 与
 * 简单 [com.puretone.player.ui.LibraryViewModel.createPlaylist]。
 * 幂等（空列表直接建空歌单），返回新建歌单 id。
 */
class CreatePlaylistWithSongsUseCase @Inject constructor(
    private val repo: MusicRepository
) {
    suspend operator fun invoke(name: String, songIds: List<Long> = emptyList()): Long {
        val id = repo.createPlaylist(name.trim().ifBlank { "新建歌单" })
        if (songIds.isNotEmpty()) repo.addToPlaylist(id, songIds)
        return id
    }
}
