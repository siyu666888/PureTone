package com.puretone.player.domain.usecase

import com.puretone.player.data.metadata.SongMetadataProvider
import com.puretone.player.data.repository.MusicRepository
import kotlinx.coroutines.delay
import javax.inject.Inject

/**
 * 批量补全缺失封面（domain 用例）。
 *
 * 收口 [com.puretone.player.ui.MetadataViewModel.backfillMissingCovers] 的核心编排：
 * 对每首无封面歌曲按「歌手 歌名」搜索首个候选并套用其封面；
 * 串行处理、每次请求间隔限流（700ms，P2-2），仅当确实写入封面才计入成功（P2-8）；
 * 完成后重新查询实际剩余缺失数用于校验。
 *
 * @param onProgress 每处理一首回调（已处理, 总数），供 VM 更新进度；默认无操作。
 * @return 三元组 (成功补全数, 总数, 剩余未补全数)
 */
class BackfillCoversUseCase @Inject constructor(
    private val repo: MusicRepository,
    private val provider: SongMetadataProvider
) {
    suspend operator fun invoke(
        onProgress: (processed: Int, total: Int) -> Unit = { _, _ -> }
    ): Triple<Int, Int, Int> {
        val missing = runCatching { repo.getSongsMissingCover() }.getOrDefault(emptyList())
        if (missing.isEmpty()) return Triple(0, 0, 0)
        var done = 0
        missing.forEachIndexed { i, song ->
            onProgress(i, missing.size)
            val term = "${song.artist} ${song.title}".trim()
            val candidate = runCatching { provider.search(term, 1).firstOrNull() }.getOrNull()
            if (candidate?.artworkUrl != null) {
                // P2-8：仅当确实写入了封面才计入成功，避免写库失败也被算成成功
                if (runCatching { repo.updateCover(song.id, candidate.artworkUrl) }.isSuccess) {
                    done++
                }
            }
            delay(700) // P2-2：限流，避免触发数据源配额导致大量失败
        }
        onProgress(missing.size, missing.size)
        // P2-2：计数校验——重新查询实际剩余缺失封面数
        val remaining = runCatching { repo.getSongsMissingCover().size }.getOrDefault(0)
        return Triple(done, missing.size, remaining)
    }
}
