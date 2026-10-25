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
                        val synced = obj.optLyrics("syncedLyrics")
                        if (pass == 0 && synced != null) return@withContext synced
                        val plain = obj.optLyrics("plainLyrics")
                        if (pass == 1 && plain != null) return@withContext plainToLrc(plain, durationMs)
                    }
                }
            }
            null
        }

    private fun pickFrom(obj: JSONObject?, durationMs: Long): String? {
        if (obj == null) return null
        obj.optLyrics("syncedLyrics")?.let { return it }
        obj.optLyrics("plainLyrics")?.let { return plainToLrc(it, durationMs) }
        return null
    }

    /**
     * 取非空字符串字段。
     *
     * P1-1（第二轮复审）：Android 框架 `org.json`（API 26–34 行为一致，已用
     * android-all 字节码实证）的 `JSONObject.optString` 对**显式 JSON null** 返回
     * 字面量字符串 `"null"` 而非空串：`JSONObject.NULL.toString()` = "null" →
     * `JSON.toString` = `String.valueOf(...)` → `optString` 的 `result != null` 判定
     * 使其通过。LRCLIB 对缺失字段正是返回 `"plainLyrics":null`（键显式为 null），
     * 因此裸 `takeIf { it.isNotBlank() }` 会把四个字母 "null" 当成歌词返回，
     * 导致歌词被写进 .lrc 文件、界面提示「已保存」却永远不显示。
     * 同仓 [ItunesMetadataProvider] 早已用 `!= "null"` 防御，这里保持一致。
     */
    private fun JSONObject.optLyrics(name: String): String? =
        optString(name).takeIf { it.isNotBlank() && it != "null" }

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
