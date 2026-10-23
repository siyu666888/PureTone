package com.puretone.player.data.metadata

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 基于 iTunes Search API 的元数据提供者（免 API Key）。
 *
 * - 端点：https://itunes.apple.com/search?term=...&entity=song&limit=...
 * - 解析用系统 org.json，无额外依赖；
 * - 封面取 artworkUrl100 并升级到 600x600 高清；
 * - 全程 IO 线程，超时 8s；任何异常都吞掉返回空列表（离线/限流时优雅降级）。
 */
@Singleton
class ItunesMetadataProvider @Inject constructor() : SongMetadataProvider {

    override val sourceName: String = "iTunes"

    override suspend fun search(term: String, limit: Int): List<MetadataCandidate> =
        withContext(Dispatchers.IO) {
            if (term.isBlank()) return@withContext emptyList()
            val url = buildString {
                append("https://itunes.apple.com/search?entity=song&limit=")
                append(limit.coerceIn(1, 50))
                append("&term=")
                append(URLEncoder.encode(term.trim(), "UTF-8"))
            }
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
            }
            try {
                val code = connection.responseCode
                if (code !in 200..299) return@withContext emptyList()
                val body = BufferedReader(InputStreamReader(connection.inputStream, "UTF-8")).use { it.readText() }
                parse(body)
            } catch (_: Exception) {
                emptyList()
            } finally {
                connection.disconnect()
            }
        }

    private fun parse(body: String): List<MetadataCandidate> {
        val results = runCatching { JSONObject(body).optJSONArray("results") }.getOrNull()
            ?: return emptyList()
        val out = ArrayList<MetadataCandidate>()
        for (i in 0 until results.length()) {
            val obj = results.optJSONObject(i) ?: continue
            val title = obj.optString("trackName").takeIf { it.isNotBlank() && it != "null" } ?: continue
            val artist = obj.optString("artistName").takeIf { it.isNotBlank() && it != "null" } ?: "未知艺术家"
            val album = obj.optString("collectionName").takeIf { it.isNotBlank() && it != "null" }.orEmpty()
            val art = obj.optString("artworkUrl100").takeIf { it.isNotBlank() && it != "null" }
                ?.replace("100x100bb", "600x600bb")
            val year = obj.optString("releaseDate").takeIf { it.length >= 4 }?.take(4)?.toIntOrNull() ?: 0
            out += MetadataCandidate(title = title, artist = artist, album = album, artworkUrl = art, year = year)
        }
        return out
    }
}
