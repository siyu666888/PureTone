package com.puretone.player.playback.cast

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 极简 SOAP / HTTP 客户端（投屏取证与自有投送通道共用）。
 *
 * ## 为什么不用库的 `UpnpHttp`
 *
 * UPnPCast v1.3.0 的 `UpnpHttp.postSoap()` **只在 HTTP 200 时返回正文**，其余
 * （404 控制地址错、500 SOAP fault、超时、连接被拒）全部吞成 `null`，再经
 * `playMediaDirect()` 变成 `false`，最后抛出不带 cause 的
 * `DeviceError("Device rejected the cast request")`——**现场信息全部丢失**。
 *
 * 本类的唯一职责是**把状态码与正文原样交出来**，让上层（探针 / 自有投送通道）
 * 能落盘真实原因。失败不抛异常，统一返回 [Result]（status = -1 表示连不上）。
 *
 * 与库行为对齐的两点细节必须保留，否则会被电视判为非法请求：
 * - `Content-Type: text/xml; charset=utf-8`；
 * - `SOAPAction` 的值为**带双引号**的 `"命名空间#动作名"`。
 */
internal object SoapClient {

    private const val CONNECT_TIMEOUT_MS = 3000
    private const val READ_TIMEOUT_MS = 6000

    /** 正文落盘上限：SOAP fault 的关键信息都在开头，截断避免刷爆诊断文件 */
    private const val BODY_LIMIT = 300

    /**
     * 一次 HTTP 往返。
     *
     * @param status HTTP 状态码；**-1 = 没连上 / 超时**（此时 [body] 是异常摘要）
     */
    data class Result(val status: Int, val body: String) {
        val ok: Boolean get() = status in 200..299
    }

    /** GET 一份设备描述或其他静态资源 */
    fun get(url: String): Result = request(url, soapAction = null, payload = null)

    /**
     * 发一条 SOAP 动作。
     *
     * @param body 动作元素本身（不含 Envelope），由 [envelope] 包装
     */
    fun post(url: String, soapAction: String, body: String): Result =
        request(url, soapAction, envelope(body))

    private fun request(url: String, soapAction: String?, payload: String?): Result {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("User-Agent", "PureTone/1.0")
            if (payload == null) {
                connection.requestMethod = "GET"
            } else {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "text/xml; charset=utf-8")
                connection.setRequestProperty("SOAPAction", "\"$soapAction\"")
                connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            Result(status, readBody(connection, status))
        } catch (e: Exception) {
            Result(-1, "${e.javaClass.simpleName}:${e.message}")
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    /** 2xx 读 inputStream、其余读 errorStream——SOAP fault 的正文只在后者里 */
    private fun readBody(connection: HttpURLConnection, status: Int): String {
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            ?: return ""
        return runCatching {
            BufferedReader(InputStreamReader(stream, "UTF-8")).use { it.readText() }
        }.getOrDefault("")
    }

    /**
     * 取 XML 标签文本。
     *
     * 允许标签带属性（`<tag attr="x">`），否则带命名空间的响应会整体解析失败；
     * 非贪婪匹配避免把后面的无关标签一起吞进来。
     */
    fun xmlValue(xml: String, tag: String): String? {
        val pattern = Regex(
            "<$tag(?:\\s[^>]*)?>(.*?)</$tag>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        return pattern.find(xml)?.groupValues?.get(1)?.trim()?.ifBlank { null }
    }

    /** 压成一行并截断，用于诊断落盘 */
    fun snippet(text: String, limit: Int = BODY_LIMIT): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= limit) flat else flat.take(limit) + "…"
    }

    /** XML 文本转义（标题、URL 都可能含 `&` `<` `>`） */
    fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    fun envelope(body: String): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
            <s:Body>$body</s:Body>
        </s:Envelope>
    """.trimIndent()
}
