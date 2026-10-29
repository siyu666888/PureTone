package com.puretone.player.domain.usecase

import com.puretone.player.data.repository.MusicRepository
import javax.inject.Inject

/**
 * 保存歌曲信息编辑（domain 用例）。
 *
 * 收口 [com.puretone.player.ui.PlayerViewModel.saveSongInfo] 的持久化部分：
 * 标题/歌手/专辑/封面/年份写入 Room。返回 true = 成功；false = 失败
 * （调用方应保留弹窗并提示，避免丢失编辑）。
 *
 * 「编辑后刷新弹窗只读摘要」属于 UI 状态同步，仍留在 VM。
 */
class SaveSongInfoUseCase @Inject constructor(
    private val repo: MusicRepository
) {
    suspend operator fun invoke(
        songId: Long,
        title: String,
        artist: String,
        album: String,
        coverUri: String?,
        year: Int
    ): Boolean = runCatching {
        repo.updateSongInfo(songId, title, artist, album, coverUri, year)
    }.isSuccess
}
