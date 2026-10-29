package com.puretone.player.domain.usecase

import com.puretone.player.data.local.entity.SongEntity
import com.puretone.player.data.metadata.MetadataCandidate
import com.puretone.player.data.metadata.SongMetadataProvider
import com.puretone.player.data.repository.MusicRepository
import com.puretone.player.domain.metadata.buildSearchTerm
import com.puretone.player.domain.metadata.isLikelyMatch
import com.puretone.player.domain.metadata.simplify
import javax.inject.Inject

/**
 * 自动填充：按「歌手 歌名」搜索并自动套用首个高置信候选（封面 + 信息）。
 *
 * 收口 [com.puretone.player.ui.MetadataViewModel.autoFill] 的核心编排：
 * - 搜索首个候选（已转简体）；
 * - 低置信不匹配则不写入（返回 [AutoFillResult.NoMatch]），避免错误匹配被永久保留；
 * - 高置信则写入，并带回「写入前的原歌曲」供 VM 撤销。
 *
 * 返回 [AutoFillResult]，VM 据此更新 message / 撤销快照 / 表单同步回调。
 */
class AutoFillMetadataUseCase @Inject constructor(
    private val repo: MusicRepository,
    private val provider: SongMetadataProvider
) {
    sealed interface AutoFillResult {
        /** 已套用：携带写入前的原歌曲（供撤销）与套用的候选 */
        data class Applied(val original: SongEntity, val candidate: MetadataCandidate) : AutoFillResult
        /** 无匹配 / 低置信，未写入；携带候选列表供 UI 展示、用户手动选择 */
        data class NoMatch(val candidates: List<MetadataCandidate>) : AutoFillResult
        /** 失败（含缺原歌曲 / 写入异常），message 直接给 UI；同样回传候选供手动选择 */
        data class Failed(val message: String, val candidates: List<MetadataCandidate>) : AutoFillResult
    }

    suspend operator fun invoke(songId: Long, artist: String, title: String): AutoFillResult {
        val term = buildSearchTerm(artist, title)
        if (term.isBlank()) return AutoFillResult.Failed("请先填写歌名或歌手", emptyList())
        val list = runCatching { provider.search(term, 5) }.getOrDefault(emptyList()).map { simplify(it) }
        val best = list.firstOrNull() ?: return AutoFillResult.NoMatch(list)
        // P2-2：低置信匹配不自动写入，改为由用户手动选择，避免错误匹配被永久保留
        if (!isLikelyMatch(title, best.title)) return AutoFillResult.NoMatch(list)
        val original = runCatching { repo.getSong(songId) }.getOrNull()
            ?: return AutoFillResult.Failed("找不到原歌曲", list)
        val ok = runCatching {
            repo.updateSongInfo(
                songId,
                best.title,
                best.artist,
                best.album,
                best.artworkUrl,
                best.year
            )
        }.isSuccess
        return if (ok) AutoFillResult.Applied(original, best) else AutoFillResult.Failed("更新失败", list)
    }
}
