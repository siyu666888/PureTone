package com.puretone.player.playback.cast

import java.io.InputStream
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
 *
 * ## 目标校验（P2-3 第三处）
 *
 * [request] 在打开连接**之前**先过一遍 [SoapTargetPolicy]：明显畸形 / 环回 /
 * 非 http(s) 的目标直接返回 `Result(-1, "blocked-target…")`，绝不发包。
 * 合法单播 IPv4 与域名 / IPv6 一律放行，正常路径零行为变化。
 * 诊断不写在本类里——[CastNetworkLog.log] 首行是 `Log.i`，而本类被 JVM 单测
 * 直接调用；改由生产调用侧通过可选 `diagnostic` 回调注入。
 */
internal object SoapClient {

    private const val CONNECT_TIMEOUT_MS = 3000
    private const val READ_TIMEOUT_MS = 6000

    /** 正文落盘上限：SOAP fault 的关键信息都在开头，截断避免刷爆诊断文件 */
    private const val BODY_LIMIT = 300

    /**
     * 响应体**读取**上限（P1-3）：局域网内一台被攻陷或异常的设备可以在 GET/POST
     * 后无限返回数据，旧实现 `readText()` 会一次性读进内存直到 OOM。
     * 合法 SCPD / 设备描述 XML 通常只有几 KB，256KB 绰绰有余。
     * 与 [BODY_LIMIT] 不是一回事：那个只截断**日志展示**，这个限制**实际读取量**。
     */
    private const val MAX_BODY_BYTES = 256 * 1024

    /**
     * 一次 HTTP 往返。
     *
     * @param status HTTP 状态码；**-1 = 没连上 / 超时**（此时 [body] 是异常摘要）
     */
    data class Result(val status: Int, val body: String) {
        val ok: Boolean get() = status in 200..299
    }

    /** GET 一份设备描述或其他静态资源 */
    fun get(url: String, diagnostic: ((String) -> Unit)? = null): Result =
        request(url, soapAction = null, payload = null, diagnostic = diagnostic)

    /**
     * 发一条 SOAP 动作。
     *
     * @param body 动作元素本身（不含 Envelope），由 [envelope] 包装
     */
    fun post(
        url: String,
        soapAction: String,
        body: String,
        diagnostic: ((String) -> Unit)? = null
    ): Result = request(url, soapAction, envelope(body), diagnostic)

    private fun request(
        url: String,
        soapAction: String?,
        payload: String?,
        diagnostic: ((String) -> Unit)?
    ): Result {
        val decision = SoapTargetPolicy.verify(url)
        if (decision.verdict != SoapTargetPolicy.Verdict.ACCEPT) {
            diagnostic?.invoke(
                "soap-target ${decision.verdict.name.lowercase()} " +
                    "url=${snippet(url, 120)} host=${decision.host ?: "-"} reason=${decision.reason}"
            )
        }
        if (decision.verdict == SoapTargetPolicy.Verdict.REJECT) {
            return Result(-1, "blocked-target reason=${decision.reason}")
        }
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
        return runCatching { readLimited(stream) }.getOrDefault("")
    }

    /**
     * 最多读 [limit] 字节，绝不把整个响应用 `readText()` 吞进内存（P1-3）。
     *
     * 抽成 `internal` 是为了纯 JVM 单测能直接用 `ByteArrayInputStream` 锁定截断行为，
     * 不必起 HTTP 连接。按字节截断时末尾若正好是半个 UTF-8 字符，`String` 会替换成
     * U+FFFD 而不抛异常——SOAP fault 的关键信息都在开头，不影响诊断。
     */
    internal fun readLimited(stream: InputStream, limit: Int = MAX_BODY_BYTES): String {
        val buffer = ByteArray(limit)
        var read = 0
        while (read < buffer.size) {
            val count = stream.read(buffer, read, buffer.size - read)
            if (count < 0) break
            read += count
        }
        return String(buffer, 0, read, Charsets.UTF_8)
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
