package com.puretone.player.domain.usecase

import com.puretone.player.data.metadata.MetadataCandidate
import com.puretone.player.data.repository.MusicRepository
import javax.inject.Inject

/**
 * 把元数据候选写入歌曲（domain 用例）。
 *
 * 收口 [com.puretone.player.ui.MetadataViewModel.apply] / undo 的持久化部分：
 * 候选字段映射到 updateSongInfo。返回 [Result]，成功为 success、失败携带异常
 * 信息（调用方据此拼出「更新失败：${message}」提示，避免丢失错误上下文）。
 */
class ApplyCandidateUseCase @Inject constructor(
    private val repo: MusicRepository
) {
    suspend operator fun invoke(songId: Long, candidate: MetadataCandidate): Result<Unit> = runCatching {
        repo.updateSongInfo(
            songId,
            candidate.title,
            candidate.artist,
            candidate.album,
            candidate.artworkUrl,
            candidate.year
        )
    }
}
