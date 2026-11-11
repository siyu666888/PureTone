package com.puretone.player.data.metadata

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 基于 iTunes Search API 的元数据提供者（免 API Key）。
 *
 * - 端点：[NetworkConfig.ITUNES_SEARCH_BASE]?entity=song&limit=...&term=...
 * - 解析用系统 org.json，无额外依赖；
 * - 封面取 artworkUrl100 并升级到 600x600 高清；
 * - 全程 IO 线程，超时取自 [NetworkConfig]；任何异常都吞掉返回空列表（离线/限流时优雅降级）。
 */
@Singleton
class ItunesMetadataProvider @Inject constructor() : SongMetadataProvider {

    override val sourceName: String = "iTunes"

    override suspend fun search(term: String, limit: Int): List<MetadataCandidate> =
        withContext(Dispatchers.IO) {
            if (term.isBlank()) return@withContext emptyList()
            val url = buildString {
                append(NetworkConfig.ITUNES_SEARCH_BASE)
                append("?entity=song&limit=")
                append(limit.coerceIn(1, 50))
                append("&term=")
                append(URLEncoder.encode(term.trim(), "UTF-8"))
            }
            // HTTP 样板统一走 HttpUtil（P3-1）；iTunes 原本不设 UA，故不传 userAgent
            val body = HttpUtil.getText(url) ?: return@withContext emptyList()
            // 解析异常与抽取前一样被吞掉，返回空列表
            runCatching { parse(body) }.getOrDefault(emptyList())
        }

    private fun parse(body: String): List<MetadataCandidate> {
        val results = runCatching { JSONObject(body).optJSONArray("results") }.getOrNull()
            ?: return emptyList()
        val out = ArrayList<MetadataCandidate>()
        for (i in 0 until results.length()) {
            val obj = results.optJSONObject(i) ?: continue
            // 统一走 JsonOpt 的空值口径（P1-2），原先四处手写 takeIf 收敛成一处判据
            val title = obj.optNonNullString("trackName") ?: continue
            val artist = obj.optNonNullString("artistName") ?: "未知艺术家"
            val album = obj.optNonNullString("collectionName").orEmpty()
            val art = obj.optNonNullString("artworkUrl100")?.replace("100x100bb", "600x600bb")
            val year = obj.optNonNullString("releaseDate")?.takeIf { it.length >= 4 }
                ?.take(4)?.toIntOrNull() ?: 0
            out += MetadataCandidate(title = title, artist = artist, album = album, artworkUrl = art, year = year)
        }
        return out
    }
}
