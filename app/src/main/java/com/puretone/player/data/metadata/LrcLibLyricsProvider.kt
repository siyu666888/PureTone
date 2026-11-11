package com.puretone.player.data.metadata

import com.puretone.player.lyrics.LrcParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 基于 LRCLIB（lrclib.net）的歌词提供者（免 API Key）。
 *
 * - 端点：[NetworkConfig.LRCLIB_GET_URL]（精确匹配）→ [NetworkConfig.LRCLIB_SEARCH_URL]（搜索兜底）；
 * - 优先 syncedLyrics（逐行同步）；仅 plainLyrics 时按歌曲时长均排为伪 LRC；
 * - 解析用系统 org.json，无额外依赖；
 * - 全程 IO 线程，超时取自 [NetworkConfig]；任何异常都吞掉返回 null（离线 / 限流时优雅降级）。
 *
 * ## 定位
 *
 * 本类现在是 [MultiChannelLyricsProvider] 内部的**一个渠道**，不再直接对外暴露。
 * LRCLIB 的优势是欧美流行乐的逐行同步歌词质量高、接口稳定；不足是华语/日语覆盖不足
 * （那部分由 AMLL 渠道补）。故在聚合器里排在 AMLL 之后。
 */
@Singleton
class LrcLibLyricsProvider @Inject constructor() : LyricsProvider {

    override val sourceName: String = "LRCLIB"

    /** 命中方式（供聚合器判定渠道） */
    enum class Match { EXACT, SEARCH }

    /** 最近一次命中的方式；未命中时为 null */
    @Volatile
    var lastMatch: Match? = null
        private set

    override suspend fun fetch(artist: String, title: String, durationMs: Long): String? =
        withContext(Dispatchers.IO) {
            lastMatch = null
            val a = artist.trim().takeIf { it.isNotBlank() && it != "<unknown>" && it != "未知艺术家" }.orEmpty()
            val t = title.trim()
            if (t.isBlank()) return@withContext null

            // 1) 精确匹配
            val exact = runCatching { getJson(buildGetUrl(t, a)) }.getOrNull()
            pickFrom(exact, durationMs)?.let {
                lastMatch = Match.EXACT
                return@withContext it
            }

            // 2) 搜索兜底：先找同步，再找纯文本
            val arr = runCatching { getJsonArray(buildSearchUrl(t, a)) }.getOrNull()
            if (arr != null) {
                val synced = pickSynced(arr, t)
                if (synced != null) {
                    lastMatch = Match.SEARCH
                    return@withContext synced
                }
                val plain = pickPlain(arr, t)
                if (plain != null) {
                    lastMatch = Match.SEARCH
                    return@withContext plainToLrc(plain, durationMs)
                }
            }
            null
        }

    /**
     * 从搜索结果里挑出第一条**曲名对得上**的同步歌词。
     *
     * 曲名一致性校验是必须的：LRCLIB 的搜索是模糊的，曲名/歌手任一对不上
     * 都会返回一串别的歌，直接取第一条就会给用户错歌词（真机实测踩过：
     * 给《晴天》捞到了同歌手的《告白气球》）。
     */
    private fun pickSynced(arr: JSONArray, title: String): String? {
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (!titleMatches(obj, title)) continue
            obj.optNonNullString("syncedLyrics")?.let { return it }
        }
        return null
    }

    /** 从搜索结果里挑出第一条曲名对得上的纯文本歌词 */
    private fun pickPlain(arr: JSONArray, title: String): String? {
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (!titleMatches(obj, title)) continue
            obj.optNonNullString("plainLyrics")?.let { return it }
        }
        return null
    }

    /** 搜索结果的曲名是否与请求曲名一致（一致或为同曲变体） */
    private fun titleMatches(obj: JSONObject, title: String): Boolean =
        LyricsTitleMatch.isAcceptable(obj.optNonNullString("trackName").orEmpty(), title)

    private fun pickFrom(obj: JSONObject?, durationMs: Long): String? {
        if (obj == null) return null
        obj.optNonNullString("syncedLyrics")?.let { return it }
        obj.optNonNullString("plainLyrics")?.let { return plainToLrc(it, durationMs) }
        return null
    }

    // 字符串字段的空值口径统一到 JsonOpt.optNonNullString（P1-2），
    // 原先本类私有的 optLyrics 扩展已删除，避免三个渠道各写一份防护。

    private fun buildGetUrl(title: String, artist: String): String = buildString {
        append(NetworkConfig.LRCLIB_GET_URL)
        append("?track_name=")
        append(URLEncoder.encode(title, "UTF-8"))
        if (artist.isNotBlank()) {
            append("&artist_name=")
            append(URLEncoder.encode(artist, "UTF-8"))
        }
    }

    private fun buildSearchUrl(title: String, artist: String): String = buildString {
        append(NetworkConfig.LRCLIB_SEARCH_URL)
        append("?track_name=")
        append(URLEncoder.encode(title, "UTF-8"))
        if (artist.isNotBlank()) {
            append("&artist_name=")
            append(URLEncoder.encode(artist, "UTF-8"))
        }
    }

    // LRCLIB 建议携带 UA；HTTP 样板统一走 HttpUtil（P3-1）
    private fun getJson(url: String): JSONObject? =
        HttpUtil.getText(url, NetworkConfig.USER_AGENT)?.let { runCatching { JSONObject(it) }.getOrNull() }

    private fun getJsonArray(url: String): JSONArray? =
        HttpUtil.getText(url, NetworkConfig.USER_AGENT)?.let { runCatching { JSONArray(it) }.getOrNull() }

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
