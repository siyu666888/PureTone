package com.puretone.player.data.metadata

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AMLL TTML 歌词库渠道（开源、免鉴权、覆盖华语与日语曲库）。
 *
 * ## 为什么加这个渠道
 *
 * LRCLIB 的覆盖率以英文/欧美流行乐为主，华语与日语歌曲常查不到——
 * 用户反馈的「有时候会下载失败」多半不是网络问题，而是**词库里根本没有这首歌**。
 * AMLL TTML DB（https://github.com/amll-dev/amll-ttml-db）是社区共建的
 * 逐词歌词库，华语覆盖率显著高于 LRCLIB，正好补上这块缺口。
 *
 * ## 接口
 *
 * 官方 HTTP API（https://amll.dev/reference/http-api/overview）：
 * - `GET /v1/lyrics/search?q=<关键词>` —— 模糊搜索（曲名/歌手/专辑/正文）；
 * - `GET /v1/lyrics/get?id=<id>` —— 按 id 取完整 TTML 歌词。
 *
 * 两个端点都**不需要鉴权**，单 IP 限速 50 次/秒（对本 App 的用户点查绰绰有余）。
 *
 * ## 格式转换
 *
 * AMLL 返回的是 **TTML**（逐词歌词）：
 * ```xml
 * <p begin="00:29.231" end="00:32.723">
 *   <span begin="00:29.231" end="00:29.692">故</span><span ...>事</span>...
 * </p>
 * ```
 * 本应用播放页消费的是 LRC，故按 `<p>`（行）取 `begin` 作为行时间戳、
 * 拼接该行所有 `<span>` 的文本作为行内容，转成标准 LRC。
 * 逐词信息（`<span>` 的精细时间轴）在 LRC 里表达不了，故丢弃——
 * 换来的是**逐行高亮**的可用歌词，这是当前播放页的能力边界。
 */
@Singleton
class AmllLyricsProvider @Inject constructor() {

    /** 数据源名称（聚合器内部使用，不对外暴露） */
    val sourceName: String = "AMLL"

    /** 搜索 + 取全文 + 转 LRC；任何一步失败都返回 null（优雅降级） */
    suspend fun fetch(artist: String, title: String, durationMs: Long): String? =
        withContext(Dispatchers.IO) {
            val t = title.trim()
            if (t.isBlank()) return@withContext null
            val a = artist.trim().takeIf { it.isNotBlank() && it != "<unknown>" && it != "未知艺术家" }.orEmpty()

            // 1) 搜索候选：优先「曲名+歌手」精确组合（AND 交集），命中更准
            val candidates = LinkedHashMap<Long, String>() // id -> 曲名（用于匹配度排序）
            searchByNameAndArtist(t, a, candidates)
            // 2) 精确组合没命中时，退回关键词模糊搜索（覆盖面更广）
            if (candidates.isEmpty()) {
                searchByKeyword(t, a, candidates)
            }
            if (candidates.isEmpty()) return@withContext null

            // 3) 按匹配度排序后逐个尝试取全文。
            //    **必须先过滤掉曲名对不上的候选**：歌手名兜底检索会捞回同一歌手的
            //    其他歌曲（如给《晴天》捞到《告白气球》），给错歌词比找不到更糟。
            val ranked = candidates.entries
                .filter { LyricsTitleMatch.isAcceptable(it.value, t) }
                .sortedByDescending { matchScore(it.value, t, a) }
            if (ranked.isEmpty()) return@withContext null

            for ((id, _) in ranked.take(MAX_CANDIDATES)) {
                val ttml = fetchById(id) ?: continue
                val lrc = TtmlToLrc.convert(ttml) ?: continue
                if (lrc.isNotBlank()) return@withContext lrc
            }
            null
        }

    /** 曲名 + 歌手精确组合搜索（两个参数之间是 AND 交集） */
    private fun searchByNameAndArtist(
        title: String,
        artist: String,
        out: MutableMap<Long, String>
    ) {
        if (artist.isBlank()) return
        val url = buildString {
            append(NetworkConfig.AMLL_SEARCH_URL)
            append("?musicName=").append(URLEncoder.encode(title, "UTF-8"))
            append("&artistName=").append(URLEncoder.encode(artist, "UTF-8"))
            append("&pageSize=").append(SEARCH_PAGE_SIZE)
        }
        collectSearchResults(url, out)
    }

    /** 关键词模糊搜索（曲名优先，附带歌手做二次过滤） */
    private fun searchByKeyword(
        title: String,
        artist: String,
        out: MutableMap<Long, String>
    ) {
        val url = buildString {
            append(NetworkConfig.AMLL_SEARCH_URL)
            append("?q=").append(URLEncoder.encode(title, "UTF-8"))
            append("&pageSize=").append(SEARCH_PAGE_SIZE)
        }
        collectSearchResults(url, out)
        // 歌手也参与一次检索，双管齐下（词库里有时曲名对不上、歌手能匹配上）
        if (artist.isNotBlank() && out.isEmpty()) {
            val artistUrl = buildString {
                append(NetworkConfig.AMLL_SEARCH_URL)
                append("?q=").append(URLEncoder.encode(artist, "UTF-8"))
                append("&pageSize=").append(SEARCH_PAGE_SIZE)
            }
            collectSearchResults(artistUrl, out)
        }
    }

    /** 解析搜索响应，把候选写入 [out] */
    private fun collectSearchResults(url: String, out: MutableMap<Long, String>) {
        val body = getText(url) ?: return
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return
        if (root.optInt("status") != 200) return
        val items = root.optJSONObject("data")?.optJSONArray("items") ?: return
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val id = item.optLong("id", 0L)
            if (id <= 0L) continue
            val names = item.optJSONArray("musicNames")
            val musicName = names?.optString(0) ?: item.optString("musicName")
            if (!out.containsKey(id)) out[id] = musicName
        }
    }

    /** 按 id 取 TTML 全文 */
    private fun fetchById(id: Long): String? {
        val body = getText("${NetworkConfig.AMLL_GET_URL}?id=$id") ?: return null
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (root.optInt("status") != 200) return null
        return root.optJSONObject("data")?.optString("lyrics")?.takeIf { it.isNotBlank() }
    }

    /**
     * 匹配度打分：曲名完全相同最高，包含次之。
     *
     * 词库里同一首歌常有多个版本（Live / 伴奏 / 翻唱），文件名可能是
     * 「晴天 (Live)」这类带后缀的变体，故用「包含」而非「完全相等」判定。
     *
     * 注意：曲名**完全对不上**的候选一律判 0 分（[LyricsTitleMatch.SCORE_NONE]），
     * 会被上一层的过滤直接剔除——歌手名兜底检索必然带回同歌手的其他歌曲，
     * 放行就等于给错歌词。
     */
    private fun matchScore(musicName: String, title: String, artist: String): Int {
        val base = LyricsTitleMatch.score(musicName, title)
        if (base == LyricsTitleMatch.SCORE_NONE) return LyricsTitleMatch.SCORE_NONE
        var score = base
        if (artist.isNotBlank() && musicName.contains(artist, ignoreCase = true)) score += 5
        return score
    }

    /** 简单的文本 GET；失败返回 null */
    private fun getText(url: String): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = NetworkConfig.CONNECT_TIMEOUT_MS
            readTimeout = NetworkConfig.READ_TIMEOUT_MS
            setRequestProperty("User-Agent", NetworkConfig.USER_AGENT)
        }
        return try {
            val code = connection.responseCode
            if (code !in 200..299) return null
            BufferedReader(InputStreamReader(connection.inputStream, "UTF-8")).use { it.readText() }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        /** 搜索返回条数上限：够用且不至于拉太多 */
        private const val SEARCH_PAGE_SIZE = 10

        /** 最多尝试几个候选（逐个取全文，命中即止） */
        private const val MAX_CANDIDATES = 3
    }
}
