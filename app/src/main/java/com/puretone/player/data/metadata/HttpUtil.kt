package com.puretone.player.data.metadata

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 三个歌词 / 元数据 Provider 共用的文本 GET（第三轮审查 P3-1）。
 *
 * [AmllLyricsProvider]、[LrcLibLyricsProvider]（两段）、[ItunesMetadataProvider] 原先各有一份
 * 「设置 GET + 超时 + UA → 判非 2xx → 读 UTF-8 正文 → 吞异常 → finally disconnect」样板，
 * AMLL 是新增渠道时直接拷贝的产物。这里收敛成一处：
 *
 * - 超时仍取 [NetworkConfig.CONNECT_TIMEOUT_MS] / [NetworkConfig.READ_TIMEOUT_MS]；
 * - UA 是可选参数：AMLL / LRCLIB 传 [NetworkConfig.USER_AGENT]，iTunes 原本就不设 UA，
 *   故传 `null`，不额外注入请求头；
 * - `openConnection()` 刻意留在 `try` 外——抽取前三个 Provider 都是如此，URL 构造异常
 *   照旧向调用方传播，不被下面的 `catch` 吞掉；
 * - 非 2xx 与任何 IO / 解析异常都返回 `null`，`disconnect()` 在 `finally` 里必定执行。
 *
 * **2026-11-11 补齐 P3-1 原建议的 `limit`**：审查报告写的是
 * `object HttpUtil { fun get(url, limit): String? }`，首次抽取时为保「零行为改动」只做了样板归并。
 * 现按 `SoapClient.readLimited` 的既有口径补上 [NetworkConfig.MAX_RESPONSE_BYTES]（256 KiB）
 * 字节上限——这是本次唯一的**有意行为变化**：超限响应只取前 256 KiB，正常歌词 / iTunes JSON
 * 远小于该值，无用户可感知影响。按字节而非按字符截断，与 SOAP 通道一致（UTF-8 中文占 3 字节）。
 */
internal object HttpUtil {

    fun getText(
        url: String,
        userAgent: String? = null,
        limit: Int = NetworkConfig.MAX_RESPONSE_BYTES,
    ): String? = getText(url, userAgent, limit) { URL(it).openConnection() as HttpURLConnection }

    /** 可注入连接工厂：纯 JVM 单测用替身锁定契约，不起真实网络；使用默认上限 */
    internal fun getText(
        url: String,
        userAgent: String?,
        open: (String) -> HttpURLConnection,
    ): String? = getText(url, userAgent, NetworkConfig.MAX_RESPONSE_BYTES, open)

    /** 可同时注入上限与连接工厂：单测用它覆盖截断边界 */
    internal fun getText(
        url: String,
        userAgent: String?,
        limit: Int,
        open: (String) -> HttpURLConnection,
    ): String? {
        val connection = open(url).apply {
            requestMethod = "GET"
            connectTimeout = NetworkConfig.CONNECT_TIMEOUT_MS
            readTimeout = NetworkConfig.READ_TIMEOUT_MS
            if (userAgent != null) setRequestProperty("User-Agent", userAgent)
        }
        return try {
            val code = connection.responseCode
            if (code !in 200..299) return null
            connection.inputStream.use { readLimited(it, limit) }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 最多读 [limit] 字节后停止，不抛异常、不补零；流提前 EOF 就按实际字节数返回。
     * 与 `SoapClient.readLimited` 逐行等价，**刻意不直接调用**以免 metadata 反向依赖 cast 包。
     */
    internal fun readLimited(
        stream: InputStream,
        limit: Int = NetworkConfig.MAX_RESPONSE_BYTES,
    ): String {
        val buffer = ByteArray(limit)
        var read = 0
        while (read < buffer.size) {
            val count = stream.read(buffer, read, buffer.size - read)
            if (count < 0) break
            read += count
        }
        return String(buffer, 0, read, Charsets.UTF_8)
    }
}
