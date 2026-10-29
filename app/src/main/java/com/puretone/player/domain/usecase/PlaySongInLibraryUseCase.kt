package com.puretone.player.domain.usecase

import com.puretone.player.data.repository.MusicRepository
import com.puretone.player.playback.PlaybackManager
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * 在完整曲库上下文中播放指定歌曲（domain 用例）。
 *
 * 收口 [com.puretone.player.ui.PlayerViewModel.playSongById]：取全库首帧 → 定位索引
 * → setQueueAndPlay，使上一首/下一首可用。
 *
 * @return true = 找到并播放；false = 歌曲已从曲库删除（无操作，避免静默播第一首）。
 */
class PlaySongInLibraryUseCase @Inject constructor(
    private val repo: MusicRepository,
    private val playback: PlaybackManager
) {
    suspend operator fun invoke(id: Long): Boolean {
        val library = runCatching { repo.observeLibrary().first() }.getOrNull() ?: return false
        val index = library.indexOfFirst { it.id == id }
        if (index < 0) return false
        playback.setQueueAndPlay(library, index)
        return true
    }
}
