package com.puretone.player.data.metadata

/**
 * 联网匹配到的歌曲元数据候选（用于补全标题 / 歌手 / 专辑 / 封面）。
 */
data class MetadataCandidate(
    val title: String,
    val artist: String,
    val album: String,
    /** 高清封面 URL（https），可空 */
    val artworkUrl: String?,
    /** 发行年份（从 releaseDate 解析），0 表示未知 */
    val year: Int
)

/**
 * 歌曲元数据提供者：根据关键词联网搜索候选信息。
 * 实现需自行切 IO 线程；网络失败时返回空列表而非抛异常（优雅降级）。
 */
interface SongMetadataProvider {
    /** 数据源名称（UI 展示用，如 "iTunes"） */
    val sourceName: String

    /**
     * 搜索候选。
     * @param term 关键词，通常是 "歌手 歌名"
     * @param limit 期望返回数量上限
     */
    suspend fun search(term: String, limit: Int = 20): List<MetadataCandidate>
}
