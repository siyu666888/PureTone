package com.puretone.player.playback.cast

import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 自有投送用的本地文件服务（HTTP/1.1，支持 Range）。
 *
 * ## 为什么不用库自带的 `LocalFileServer`
 *
 * 库的 `getLocalIpAddress()` 取「**按 `NetworkInterface` 枚举顺序的第一个非环回 IPv4**」。
 * 手机同时开 Wi-Fi 与移动数据时，枚举顺序里蜂窝网卡（`rmnet_data0`）常常排在
 * `wlan0` 前面，于是给电视的播放地址会变成 `http://10.x.x.x:8081/file/<token>`——
 * 一个电视永远够不着的运营商内网地址。这条链路上我们拿不到任何日志，
 * 只能看到最终一句「Device rejected the cast request」。
 *
 * 本实现把地址**显式指定**为 Wi-Fi 网卡的 IPv4（由调用方传入），
 * 并把每一次真实取流请求落盘（`http-fetch from=… range=… status=…`）——
 * 电视到底有没有来拉流，从此是可见事实，而不是猜测。
 *
 * ## 安全边界
 *
 * 只按 **token** 发文件，绝不按路径取：请求 `/file/<token>` 命中注册表才发，
 * 其余一律 404。端口由系统分配（`ServerSocket(0)`），局域网内没有可枚举的目录结构。
 */
internal class CastHttpServer {

    /** 已登记的可投送文件 */
    private data class Entry(val file: File, val mimeType: String)

    /** 一次 GET 的实际供流结果，只用于诊断，不参与响应构造 */
    private data class CopyResult(val written: Long, val costMs: Long, val error: String?)

    /** Range 解析结果（纯函数产物，单测覆盖） */
    sealed interface RangeSpec {
        /** 无 Range 头，整文件下发 */
        data object Full : RangeSpec

        /** 206 分段 */
        data class Partial(val start: Long, val end: Long) : RangeSpec

        /** 起点越界，应回 416 */
        data object Unsatisfiable : RangeSpec
    }

    private val entries = ConcurrentHashMap<String, Entry>()
    private var server: ServerSocket? = null
    private var acceptor: Thread? = null

    @Volatile
    private var running = false

    /**
     * **本轮投送**最多落盘几条取流日志：足够证明「电视来过」，又不至于刷爆诊断。
     *
     * 换曲时由 [urlFor] 清零（P2-4）：旧实现只在 [stop] 清零，连续投屏时前 8 条
     * 被头一两首占满，之后每首歌「电视到底来没来拉流」在诊断里完全不可见——
     * 191939 报告 883 行只有 18 条 `http-fetch` 就是这个原因。
     * 3 个 worker 并发调用 [logRequest]，故用原子计数，避免 check-then-increment 竞态。
     */
    private val loggedRequests = AtomicInteger(0)

    /**
     * 当前这一轮投送的 URL token。换曲后旧 URL 仍在 [entries] 里（电视可能还在拉旧流），
     * 只有命中当前 token 的请求才算「新曲被取流」，否则会把旧曲的重连误当成新曲已建立。
     */
    @Volatile
    private var activeToken: String? = null

    /**
     * 当前 token 最近一次成功取流的墙钟。
     *
     * 与 [loggedRequests] 不同，它**不受日志条数上限影响**：诊断文件里看不到
     * `http-fetch` 时，可能只是本轮前 8 条已记满，不能据此断言电视没来取流。
     * [DlnaCastManager] 用它判定「投屏是否真建立」，见 [CastState.isEstablished]。
     */
    private val lastFetchMs = java.util.concurrent.atomic.AtomicLong(0L)

    /**
     * 请求级取流取证（R7）。与 [lastFetchMs] 各司其职：后者只服务「投屏是否建立」，
     * 前者记录**所有**请求（含旧 token 重连）的到达时刻与写出量，不受 8 条日志上限影响。
     */
    private val fetchTrace = CastFetchTrace()

    /** 当前 token 最近一次成功取流的墙钟（0=本轮从未取流） */
    fun lastSuccessfulFetchMs(): Long = lastFetchMs.get()

    /** PAUSED 跳变时取一份请求级快照，供 [CastFetchIdleLog] 落进同一行 */
    fun fetchTraceSnapshot(nowMs: Long): String = fetchTrace.snapshot(nowMs)

    private val workers = Executors.newFixedThreadPool(WORKER_COUNT) { runnable ->
        Thread(runnable, "PureTone-CastHttp").apply { isDaemon = true }
    }

    /** 监听端口；未启动返回 -1 */
    val port: Int get() = server?.localPort ?: -1

    /**
     * 启动监听（幂等）。
     *
     * 绑定 `0.0.0.0` 由系统选端口——**对外公布的地址**才是关键，
     * 见 [urlFor] 的 `host` 参数。
     */
    fun start(): Boolean {
        if (running) return true
        return runCatching {
            val socket = ServerSocket(0)
            server = socket
            running = true
            acceptor = Thread({ acceptLoop(socket) }, "PureTone-CastHttp-accept").apply {
                isDaemon = true
                start()
            }
            CastNetworkLog.log("http-server started port=${socket.localPort}")
            true
        }.getOrElse {
            Log.w(TAG, "start failed", it)
            CastNetworkLog.log("http-server start_fail ${it.javaClass.simpleName} ${it.message}")
            false
        }
    }

    /**
     * 登记一个文件并返回渲染器可访问的地址。
     *
     * @param host 手机在**局域网内**的 IPv4（由调用方从 Wi-Fi 网络取，不能用枚举顺序）
     */
    fun urlFor(file: File, mimeType: String, host: String): String? {
        if (!running && !start()) return null
        val token = UUID.randomUUID().toString().replace("-", "")
        entries[token] = Entry(file, mimeType)
        // 新一轮投送：旧 token 的取流不再算数，避免把「上一首的重连」当成本曲已建立
        activeToken = token
        lastFetchMs.set(0L)
        // 取流日志按「轮」重置，否则连播几首后诊断里只剩头一两首的 http-fetch
        loggedRequests.set(0)
        // 请求级取证同样按轮复位，避免上一首的请求串进新曲的 PAUSED 判读
        fetchTrace.reset()
        val url = "http://$host:$port/file/$token"
        CastNetworkLog.log("http-url host=$host port=$port mime=$mimeType")
        return url
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
        acceptor = null
        entries.clear()
        loggedRequests.set(0)
        fetchTrace.reset()
        activeToken = null
        lastFetchMs.set(0L)
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            val client = try {
                socket.accept()
            } catch (e: Exception) {
                if (running) Log.w(TAG, "accept failed", e)
                return
            }
            workers.execute {
                runCatching { handle(client) }.onFailure { Log.w(TAG, "handle failed", it) }
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.use { client ->
            client.soTimeout = SOCKET_TIMEOUT_MS
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.ISO_8859_1))
            val requestLine = reader.readLine() ?: return
            val range = readHeaders(reader)
            val parts = requestLine.split(' ')
            if (parts.size < 2) {
                respondText(client, 400, "Bad Request")
                return
            }
            serve(client, parts[0].uppercase(), parts[1], range)
        }
    }

    /** 读完请求头（GET/HEAD 无正文），返回 Range 头原始值 */
    private fun readHeaders(reader: BufferedReader): String? {
        var range: String? = null
        var index = 0
        while (index < MAX_HEADERS) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            if (line.substringBefore(':').trim().equals("Range", ignoreCase = true)) {
                range = line.substringAfter(':').trim()
            }
            index++
        }
        return range
    }

    private fun serve(socket: Socket, method: String, path: String, rangeHeader: String?) {
        // 记请求**到达**时刻，不是响应写完时刻：R7 判读「停顿那一帧电视有没有来拉流」
        // 全靠这个。旧 token 的重连也算一次请求，但不会误写进 lastFetchMs。
        fetchTrace.noteRequest(System.currentTimeMillis())
        if (method != "GET" && method != "HEAD") {
            respondText(socket, 405, "Method Not Allowed")
            return
        }
        val token = path.substringBefore('?').removePrefix("/file/")
        val entry = entries[token]
        if (entry == null) {
            logRequest(socket, path, rangeHeader, 404)
            respondText(socket, 404, "File Not Found")
            return
        }
        val size = entry.file.length()
        when (val spec = parseRange(rangeHeader, size)) {
            is RangeSpec.Unsatisfiable -> {
                logRequest(socket, path, rangeHeader, 416)
                respondText(socket, 416, "Range Not Satisfiable")
            }
            is RangeSpec.Partial -> {
                markFetchIfActive(token)
                sendFile(socket, method, entry, spec.start, spec.end, spec, rangeHeader)
            }
            RangeSpec.Full -> {
                markFetchIfActive(token)
                // 无法识别的 Range 一律按整文件 200 下发；但原始头仍传下去供诊断落盘，
                // 这样电视发了什么非法 Range 在报告里依旧可见（P1-1）。
                sendFile(socket, method, entry, 0L, (size - 1).coerceAtLeast(0L), spec, rangeHeader)
            }
        }
    }

    /** 只记「当前这一首」的成功取流时刻，供投屏建立确认读取 */
    private fun markFetchIfActive(token: String) {
        if (token == activeToken) lastFetchMs.set(System.currentTimeMillis())
    }

    /**
     * @param spec 响应状态**只由解析结果决定**（[responseStatus]），不看 [rangeHeader]
     *   是否为空——电视发一个无法解析的 `Range: garbage` 时旧实现会误回 206 +
     *   `Content-Range: 0-{size-1}` 却发整文件，协议自相矛盾（P1-1）。
     * @param rangeHeader 仅用于诊断日志，保留原始值不参与响应构造。
     */
    private fun sendFile(
        socket: Socket,
        method: String,
        entry: Entry,
        start: Long,
        end: Long,
        spec: RangeSpec,
        rangeHeader: String?
    ) {
        val size = entry.file.length()
        val length = end - start + 1
        val status = responseStatus(spec)
        val partial = status == 206
        val header = buildString {
            append("HTTP/1.1 ").append(status).append(if (partial) " Partial Content\r\n" else " OK\r\n")
            append("Content-Type: ").append(entry.mimeType).append("\r\n")
            append("Content-Length: ").append(length).append("\r\n")
            append("Accept-Ranges: bytes\r\n")
            if (partial) {
                append("Content-Range: bytes ").append(start).append('-').append(end)
                    .append('/').append(size).append("\r\n")
            }
            append("Connection: close\r\n\r\n")
        }
        val out = socket.getOutputStream()
        out.write(header.toByteArray(Charsets.ISO_8859_1))
        val result = if (method == "GET") {
            copy(out, entry.file, start, length)
        } else {
            CopyResult(0L, 0L, null)
        }
        out.flush()
        logRequest(
            socket, entry.file.name, rangeHeader, status,
            formatFetchDetail(start, length, result.written, result.costMs, result.error)
        )
    }

    /**
     * 供流并返回「实际写出字节 / 耗时 / 异常原因」。
     *
     * 行为与改造前完全一致（异常仍被吞掉、只记日志，绝不抛给 [handle] 影响播放），
     * 只是把原本只进 logcat 的 `copy failed` 同时写进诊断文件——R7 的三个取证缺口之一。
     * `http-copy-fail` **不受** `http-fetch` 8 条上限影响：供流中断必须留痕。
     */
    private fun copy(out: OutputStream, file: File, start: Long, length: Long): CopyResult {
        if (length <= 0) return CopyResult(0L, 0L, null)
        val beganMs = System.currentTimeMillis()
        var written = 0L
        var error: String? = null
        runCatching {
            FileInputStream(file).use { input ->
                var remaining = start
                // skip() 可能少跳：循环到真正到达起点，否则电视收到的是错位的流
                while (remaining > 0) {
                    val skipped = input.skip(remaining)
                    if (skipped <= 0) break
                    remaining -= skipped
                }
                if (remaining > 0) return@runCatching
                val buffer = ByteArray(BUFFER_SIZE)
                var left = length
                while (left > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                    written += read
                    left -= read
                }
            }
        }.onFailure {
            Log.w(TAG, "copy failed", it)
            error = "${it.javaClass.simpleName}:${it.message ?: "-"}"
        }
        fetchTrace.noteWritten(written)
        val reason = error
        if (reason != null) {
            fetchTrace.noteError(reason)
            CastNetworkLog.log("http-copy-fail sent=$written/$length err=$reason")
        }
        return CopyResult(written, System.currentTimeMillis() - beganMs, error)
    }

    private fun respondText(socket: Socket, code: Int, text: String) {
        runCatching {
            val body = text.toByteArray(Charsets.UTF_8)
            val out = socket.getOutputStream()
            out.write(("HTTP/1.1 $code $text\r\nContent-Type: text/plain; charset=utf-8\r\n" +
                "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
            out.write(body)
            out.flush()
        }
    }

    /**
     * 落盘一条取流记录（每轮限量，避免一次播放刷爆环形日志）。
     *
     * @param detail 发文件时的供流明细（[formatFetchDetail]）；404/416 走默认空串，
     *   这样既有的 `http-fetch … status=404` 判读格式一字不变。
     */
    private fun logRequest(socket: Socket, path: String, range: String?, status: Int, detail: String = "") {
        // getAndUpdate 保证 3 个 worker 并发时不会一起越过上限
        val previous = loggedRequests.getAndUpdate { if (it >= MAX_LOGGED_REQUESTS) it else it + 1 }
        if (previous >= MAX_LOGGED_REQUESTS) return
        val from = runCatching { socket.inetAddress.hostAddress }.getOrNull() ?: "?"
        CastNetworkLog.log(
            "http-fetch from=$from path=${path.take(48)} range=${range ?: "-"} status=$status" +
                if (detail.isEmpty()) "" else " $detail"
        )
    }

    companion object {
        private const val TAG = "CastHttpServer"

        private const val WORKER_COUNT = 3
        private const val SOCKET_TIMEOUT_MS = 15_000
        private const val MAX_HEADERS = 32
        private const val MAX_LOGGED_REQUESTS = 8
        private const val BUFFER_SIZE = 64 * 1024

        /**
         * 供流明细格式（抽成纯函数供单测锁定）。
         *
         * R7 要区分「电视一次拉完大块后几十秒没新请求」（FLAC 长连接的正常表现）
         * 与「请求来了但只写出很少字节就断了」（供流中断），故同时记
         * 实际写出 / 计划长度 / 起点 / 耗时 / 异常原因，附在 `http-fetch` 同一行。
         */
        fun formatFetchDetail(
            start: Long,
            length: Long,
            written: Long,
            costMs: Long,
            error: String?
        ): String = "sent=$written/$length start=$start cost=${costMs}ms err=${error ?: "-"}"

        /**
         * 由解析结果算 HTTP 状态：**只有真正分段才 206**。
         *
         * 旧实现用 `rangeHeader != null` 判分段，导致无法识别的 Range 头也回 206
         * 却发整文件（P1-1）。抽成纯函数让 [sendFile] 与单测共用同一判据。
         */
        fun responseStatus(spec: RangeSpec): Int = when (spec) {
            is RangeSpec.Partial -> 206
            RangeSpec.Full -> 200
            RangeSpec.Unsatisfiable -> 416
        }

        /**
         * 解析 `Range: bytes=…`。
         *
         * 只支持单区间（多区间没有渲染器会用），三种写法都覆盖：
         * `a-b`、`a-`、`-n`（末尾 n 字节）。
         */
        fun parseRange(header: String?, size: Long): RangeSpec {
            if (header == null || !header.startsWith("bytes=", ignoreCase = true) || size <= 0) {
                return RangeSpec.Full
            }
            val spec = header.substringAfter('=', "").substringBefore(',').trim()
            val dash = spec.indexOf('-')
            if (dash < 0) return RangeSpec.Full
            val headText = spec.substring(0, dash).trim()
            val tailText = spec.substring(dash + 1).trim()
            return runCatching {
                if (headText.isEmpty()) {
                    val suffix = tailText.toLong()
                    if (suffix <= 0) return@runCatching RangeSpec.Unsatisfiable
                    RangeSpec.Partial((size - suffix).coerceAtLeast(0L), size - 1)
                } else {
                    val start = headText.toLong()
                    val rawEnd = if (tailText.isEmpty()) size - 1 else tailText.toLong()
                    if (start >= size || rawEnd < start) return@runCatching RangeSpec.Unsatisfiable
                    RangeSpec.Partial(start, rawEnd.coerceAtMost(size - 1))
                }
            }.getOrDefault(RangeSpec.Full)
        }
    }
}
