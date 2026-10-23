package com.puretone.player.data.metadata

import com.puretone.player.lyrics.LrcParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 基于 LRCLIB（lrclib.net）的歌词提供者（免 API Key）。
 *
 * - 端点：/api/get（精确匹配）→ /api/search（搜索兜底）；
 * - 优先 syncedLyrics（逐行同步）；仅 plainLyrics 时按歌曲时长均排为伪 LRC；
 * - 解析用系统 org.json，无额外依赖；
 * - 全程 IO 线程，超时 8s；任何异常都吞掉返回 null（离线 / 限流时优雅降级）。
 */
@Singleton
class LrcLibLyricsProvider @Inject constructor() : LyricsProvider {

    override val sourceName: String = "LRCLIB"

    override suspend fun fetch(artist: String, title: String, durationMs: Long): String? =
        withContext(Dispatchers.IO) {
            val a = artist.trim().takeIf { it.isNotBlank() && it != "<unknown>" && it != "未知艺术家" }.orEmpty()
            val t = title.trim()
            if (t.isBlank()) return@withContext null

            // 1) 精确匹配
            val exact = runCatching { getJson(buildGetUrl(t, a)) }.getOrNull()
            pickFrom(exact, durationMs)?.let { return@withContext it }

            // 2) 搜索兜底：先找同步，再找纯文本
            val arr = runCatching { getJsonArray(buildSearchUrl(t, a)) }.getOrNull()
            if (arr != null) {
                for (pass in 0..1) {
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val synced = obj.optString("syncedLyrics").takeIf { !it.isNullOrBlank() }
                        if (pass == 0 && synced != null) return@withContext synced
                        val plain = obj.optString("plainLyrics").takeIf { !it.isNullOrBlank() }
                        if (pass == 1 && plain != null) return@withContext plainToLrc(plain, durationMs)
                    }
                }
            }
            null
        }

    private fun pickFrom(obj: JSONObject?, durationMs: Long): String? {
        if (obj == null) return null
        obj.optString("syncedLyrics").takeIf { !it.isNullOrBlank() }?.let { return it }
        obj.optString("plainLyrics").takeIf { !it.isNullOrBlank() }?.let { return plainToLrc(it, durationMs) }
        return null
    }

    private fun buildGetUrl(title: String, artist: String): String = buildString {
        append("https://lrclib.net/api/get?track_name=")
        append(URLEncoder.encode(title, "UTF-8"))
        if (artist.isNotBlank()) {
            append("&artist_name=")
            append(URLEncoder.encode(artist, "UTF-8"))
        }
    }

    private fun buildSearchUrl(title: String, artist: String): String = buildString {
        append("https://lrclib.net/api/search?track_name=")
        append(URLEncoder.encode(title, "UTF-8"))
        if (artist.isNotBlank()) {
            append("&artist_name=")
            append(URLEncoder.encode(artist, "UTF-8"))
        }
    }

    private fun getJson(url: String): JSONObject? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 8000
            // LRCLIB 建议携带 UA
            setRequestProperty("User-Agent", "PureTone (https://github.com/puretone)")
        }
        return try {
            val code = connection.responseCode
            if (code !in 200..299) return null
            val body = BufferedReader(InputStreamReader(connection.inputStream, "UTF-8")).use { it.readText() }
            runCatching { JSONObject(body) }.getOrNull()
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun getJsonArray(url: String): JSONArray? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("User-Agent", "PureTone (https://github.com/puretone)")
        }
        return try {
            val code = connection.responseCode
            if (code !in 200..299) return null
            val body = BufferedReader(InputStreamReader(connection.inputStream, "UTF-8")).use { it.readText() }
            runCatching { JSONArray(body) }.getOrNull()
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    /** 纯文本歌词按歌曲时长均排为伪 LRC（无同步时间轴时的兜底，保证高亮可用） */
    private fun plainToLrc(plain: String, durationMs: Long): String {
        val lines = plain.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return ""
        val perLine = if (durationMs > 0) durationMs / lines.size else 3500L
        val sb = StringBuilder()
        lines.forEachIndexed { i, line ->
            sb.append('[').append(LrcParser.formatTime(i * perLine)).append(']').append(line).append('\n')
        }
        return sb.toString()
    }
}
