package com.puretone.player.domain.metadata

import com.puretone.player.data.metadata.MetadataCandidate
import com.puretone.player.util.ChineseText

/**
 * 元数据匹配纯函数（domain 层）。
 *
 * 旧实现把 simplify / buildSearchTerm / isLikelyMatch 写在
 * [com.puretone.player.ui.MetadataViewModel] 内部（private），纯匹配算法被绑
 * 在 UI 层、无法纯 JVM 单测。抽到 domain 后：
 * - 候选简体化、搜索词构造、低置信保护均可纯 JVM 单测（见 MetadataMatchingTest）；
 * - VM 只负责联网编排 + UI 状态/取消，匹配判定逻辑收口到 domain。
 *
 * 口径（与历史行为一致，勿随意改动）：
 * - simplify：标题/歌手/专辑统一转简体，保证展示与落库均为简体；
 * - buildSearchTerm：跳过「未知艺术家 / 空」，避免污染搜索；
 * - isLikelyMatch：去空白与标点后存在包含关系才算高置信，避免 autoFill 无脑套用。
 */

/** 候选信息统一转简体（标题 / 歌手 / 专辑），保证展示与落库均为简体。 */
fun simplify(candidate: MetadataCandidate): MetadataCandidate = candidate.copy(
    title = ChineseText.toSimplified(candidate.title) ?: candidate.title,
    artist = ChineseText.toSimplified(candidate.artist) ?: candidate.artist,
    album = ChineseText.toSimplified(candidate.album) ?: candidate.album
)

/** 构造搜索词：跳过「未知艺术家 / 空」，避免污染搜索 */
fun buildSearchTerm(artist: String, title: String): String =
    listOf(
        artist.takeIf { it.isNotBlank() && it != "<unknown>" && it != "未知艺术家" },
        title
    ).filter { !it.isNullOrBlank() }.joinToString(" ").trim()

/**
 * 低置信匹配保护：去掉空白与标点后，候选标题与查询标题存在包含关系才算高置信。
 * 避免 autoFill 无脑套用第一条结果导致错误匹配被永久写入。
 */
fun isLikelyMatch(queryTitle: String, candidateTitle: String): Boolean {
    val norm = { s: String ->
        s.lowercase().replace(Regex("[\\s\\p{Punct}]"), "")
    }
    val a = norm(queryTitle)
    val b = norm(candidateTitle)
    if (a.isBlank() || b.isBlank()) return false
    return a == b || a.contains(b) || b.contains(a)
}
