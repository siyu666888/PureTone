package com.puretone.player.domain.usecase

import com.puretone.player.data.metadata.MetadataCandidate
import com.puretone.player.data.metadata.SongMetadataProvider
import com.puretone.player.domain.metadata.NetworkMetadataGate
import com.puretone.player.domain.metadata.simplify
import javax.inject.Inject

/**
 * 联网搜索元数据候选（domain 用例）。
 *
 * 收口 [com.puretone.player.ui.MetadataViewModel.search] / [com.puretone.player.ui.MetadataViewModel.autoFill]
 * 里的 provider.search + simplify。网络失败返回空列表（优雅降级），由 VM 决定提示文案。
 *
 * P2-4：用户关闭「联网补全元数据与歌词」时，[NetworkMetadataGate] 拦下请求，
 * 直接返回空列表——不发任何出站请求。
 */
class SearchMetadataUseCase @Inject constructor(
    private val provider: SongMetadataProvider,
    private val gate: NetworkMetadataGate
) {
    suspend operator fun invoke(term: String, limit: Int = 20): List<MetadataCandidate> {
        if (!gate.isEnabled()) return emptyList()
        return runCatching { provider.search(term, limit) }.getOrDefault(emptyList()).map { simplify(it) }
    }
}
