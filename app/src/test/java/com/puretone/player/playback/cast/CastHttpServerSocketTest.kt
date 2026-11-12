package com.puretone.player.playback.cast

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.Socket

/**
 * [CastHttpServer] 的真实 socket 端到端协议测试。
 *
 * 前两轮把 200/206 响应头、HEAD 取流判定、缓存文件失效 404 都抽成纯函数锁定判据，
 * 但「响应头声明的 Content-Length 是否真等于写出的字节」「HEAD 是否真的零正文」
 * 只有跑一条真实 TCP 连接才能证明。这里起本地回环 ServerSocket + JDK Socket，
 * 不 mock 网络、不改生产代码；Robolectric 只为让 CastNetworkLog.log() 首行的
 * android.util.Log.i 不抛 "Method i ... not mocked"（纯 JVM 下 log() 首行即踩 stub）。
 *
 * 每个用例独立 start/stop，端口由系统分配，不依赖固定端口、DNS 或外网。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CastHttpServerSocketTest {

    private lateinit var server: CastHttpServer
    private lateinit var file: File

    @Before
    fun setUp() {
        file = File.createTempFile("cast-e2e", ".bin")
        file.writeBytes(ByteArray(FILE_SIZE) { it.toByte() })
        server = CastHttpServer()
        assertTrue("本地回环服务应能启动", server.start(null))
        assertTrue(server.port > 0)
    }

    @After
    fun tearDown() {
        if (::server.isInitialized) server.stop()
        if (::file.isInitialized) file.delete()
    }

    @Test
    fun `GET 正常文件返回 200 且实际字节数与 Content-Length 一致`() {
        val token = register()
        val response = request("GET", "/file/$token")

        assertEquals(HTTP_OK, response.status)
        assertEquals(FILE_SIZE.toString(), response.header("Content-Length"))
        assertArrayEquals(file.readBytes(), response.body)
        assertTrue("GET 应记成功取流", server.lastSuccessfulFetchMs() > 0L)
    }

    @Test
    fun `HEAD 只回响应头且不把投屏误判为已取流`() {
        val token = register()
        val head = request("HEAD", "/file/$token")

        assertEquals(HTTP_OK, head.status)
        assertEquals(FILE_SIZE.toString(), head.header("Content-Length"))
        assertNull("HEAD 不应带 Content-Range", head.header("Content-Range"))
        assertEquals("HEAD 不得写正文", 0, head.body.size)
        assertEquals("HEAD 不得置位 lastFetchMs", 0L, server.lastSuccessfulFetchMs())

        request("GET", "/file/$token")
        assertTrue("随后真正的 GET 应置位", server.lastSuccessfulFetchMs() > 0L)
    }

    @Test
    fun `Range 返回 206 且 Content-Range 的 total 与响应长度同源`() {
        val token = register()
        val response = request("GET", "/file/$token", "bytes=$RANGE_START-$RANGE_END")

        assertEquals(HTTP_PARTIAL, response.status)
        assertEquals("bytes $RANGE_START-$RANGE_END/$FILE_SIZE", response.header("Content-Range"))
        assertEquals((RANGE_END - RANGE_START + 1).toString(), response.header("Content-Length"))
        assertArrayEquals(file.readBytes().copyOfRange(RANGE_START, RANGE_END + 1), response.body)
    }

    @Test
    fun `缓存文件被清空时返回 404 而不是 200 零字节`() {
        val token = register()
        file.writeBytes(ByteArray(0))

        val response = request("GET", "/file/$token")

        assertEquals(HTTP_NOT_FOUND, response.status)
        assertEquals("缓存失效不得置位 lastFetchMs", 0L, server.lastSuccessfulFetchMs())
    }

    @Test
    fun `未知 token 返回 404`() {
        register()
        assertEquals(HTTP_NOT_FOUND, request("GET", "/file/not-a-token").status)
    }

    @Test
    fun `无法解析的 Range 按整文件 200 而非伪 206`() {
        val token = register()
        val response = request("GET", "/file/$token", "garbage")

        assertEquals(HTTP_OK, response.status)
        assertNull("Full 响应不应带 Content-Range", response.header("Content-Range"))
        assertArrayEquals(file.readBytes(), response.body)
    }

    @Test
    fun `越界 Range 返回 416`() {
        val token = register()
        assertEquals(
            HTTP_RANGE_NOT_SATISFIABLE,
            request("GET", "/file/$token", "bytes=$FILE_SIZE-").status
        )
    }

    @Test
    fun `非 GET HEAD 方法返回 405`() {
        val token = register()
        assertEquals(HTTP_METHOD_NOT_ALLOWED, request("POST", "/file/$token").status)
    }

    /** 登记当前文件并返回渲染器可见的 token（每次登记都会重置本轮取流状态） */
    private fun register(): String =
        server.urlFor(file, MIME, HOST)!!.substringAfterLast('/')

    private fun request(method: String, path: String, range: String? = null): HttpResponse =
        Socket(HOST, server.port).use { socket ->
            socket.soTimeout = SOCKET_TIMEOUT_MS
            val raw = buildString {
                append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
                append("Host: ").append(HOST).append("\r\n")
                if (range != null) append("Range: ").append(range).append("\r\n")
                append("Connection: close\r\n\r\n")
            }
            socket.getOutputStream().apply {
                write(raw.toByteArray(Charsets.ISO_8859_1))
                flush()
            }
            readResponse(socket.getInputStream())
        }

    /**
     * 逐字节读响应头（不能用 BufferedReader：它会预读缓冲，吞掉随后的二进制正文），
     * 再按 Content-Length 精确读正文。服务端 Connection: close，读到 EOF 即结束。
     */
    private fun readResponse(input: InputStream): HttpResponse {
        val stream = BufferedInputStream(input)
        val head = ByteArrayOutputStream()
        val terminator = "\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
        var matched = 0
        while (matched < terminator.size) {
            val byte = stream.read()
            if (byte < 0) break
            head.write(byte)
            matched = if (byte.toByte() == terminator[matched]) {
                matched + 1
            } else if (byte.toByte() == terminator[0]) {
                1
            } else {
                0
            }
        }
        val lines = head.toString("ISO-8859-1").split("\r\n").filter { it.isNotEmpty() }
        val status = lines.firstOrNull()?.split(' ')?.getOrNull(1)?.toIntOrNull() ?: -1
        val headers = lines.drop(1).mapNotNull { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) null else line.substring(0, colon).trim() to line.substring(colon + 1).trim()
        }.toMap()
        val length = headers.entries.firstOrNull { it.key.equals("Content-Length", ignoreCase = true) }
            ?.value?.toLongOrNull() ?: 0L
        val body = ByteArrayOutputStream()
        var remaining = length
        val buffer = ByteArray(READ_BUFFER_SIZE)
        while (remaining > 0L) {
            val read = stream.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read <= 0) break
            body.write(buffer, 0, read)
            remaining -= read
        }
        return HttpResponse(status, headers, body.toByteArray())
    }

    private data class HttpResponse(
        val status: Int,
        val headers: Map<String, String>,
        val body: ByteArray
    ) {
        fun header(name: String): String? =
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    }

    private companion object {
        const val HOST = "127.0.0.1"
        const val MIME = "audio/flac"
        const val FILE_SIZE = 16
        const val RANGE_START = 4
        const val RANGE_END = 9
        const val SOCKET_TIMEOUT_MS = 5_000
        const val READ_BUFFER_SIZE = 4_096
        const val HTTP_OK = 200
        const val HTTP_PARTIAL = 206
        const val HTTP_NOT_FOUND = 404
        const val HTTP_METHOD_NOT_ALLOWED = 405
        const val HTTP_RANGE_NOT_SATISFIABLE = 416
    }
}
