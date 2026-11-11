package com.puretone.player.data.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * [HttpUtil] 单测（第三轮审查 P3-1）。
 *
 * 用可注入的连接替身锁定「抽取前后行为逐字等价」：GET / 超时 / 可选 UA /
 * 非 2xx / 异常吞掉 / 必定 disconnect / openConnection 异常照旧外抛。
 * 不起真实网络，纯 JVM。
 */
class HttpUtilTest {

    private class StubConnection(
        private val code: Int,
        private val body: String = "",
        private val failOnCode: Boolean = false,
        private val failOnStream: Boolean = false,
    ) : HttpURLConnection(URL("http://localhost/")) {

        val headers = mutableMapOf<String, String>()
        var disconnected = false
            private set

        override fun setRequestProperty(key: String, value: String) {
            headers[key] = value
        }

        override fun getResponseCode(): Int {
            if (failOnCode) throw IOException("network gone")
            return code
        }

        override fun getInputStream(): InputStream {
            if (failOnStream) throw IOException("stream gone")
            return ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
        }

        override fun connect() = Unit
        override fun disconnect() {
            disconnected = true
        }

        override fun usingProxy(): Boolean = false
    }

    private fun call(text: String, userAgent: String? = null, stub: StubConnection): String? =
        HttpUtil.getText("http://unit.test/$text", userAgent) { stub }

    private fun callWithLimit(text: String, limit: Int, stub: StubConnection): String? =
        HttpUtil.getText("http://unit.test/$text", null, limit) { stub }

    @Test
    fun `200返回完整UTF8正文`() {
        val stub = StubConnection(200, "晴天 - 周杰伦")
        assertEquals("晴天 - 周杰伦", call("ok", stub = stub))
        assertTrue(stub.disconnected)
    }

    @Test
    fun `299仍视为成功`() {
        val stub = StubConnection(299, "partial")
        assertEquals("partial", call("ok", stub = stub))
        assertTrue(stub.disconnected)
    }

    @Test
    fun `非2xx返回null且仍disconnect`() {
        listOf(199, 404, 500).forEach { code ->
            val stub = StubConnection(code, "ignored")
            assertNull(call("bad", stub = stub))
            assertTrue("code=$code 未断开连接", stub.disconnected)
        }
    }

    @Test
    fun `携带UA时设置User-Agent请求头`() {
        val stub = StubConnection(200, "ok")
        call("ok", NetworkConfig.USER_AGENT, stub)
        assertEquals(NetworkConfig.USER_AGENT, stub.headers["User-Agent"])
    }

    @Test
    fun `不携带UA时保持iTunes原行为不设置该头`() {
        val stub = StubConnection(200, "ok")
        call("ok", null, stub)
        assertFalse(stub.headers.containsKey("User-Agent"))
    }

    @Test
    fun `GET与超时统一取自NetworkConfig`() {
        val stub = StubConnection(200, "ok")
        call("ok", stub = stub)
        assertEquals("GET", stub.requestMethod)
        assertEquals(NetworkConfig.CONNECT_TIMEOUT_MS, stub.connectTimeout)
        assertEquals(NetworkConfig.READ_TIMEOUT_MS, stub.readTimeout)
    }

    @Test
    fun `响应码抛异常时返回null且disconnect`() {
        val stub = StubConnection(200, "ok", failOnCode = true)
        assertNull(call("fail", stub = stub))
        assertTrue(stub.disconnected)
    }

    @Test
    fun `读流抛异常时返回null且disconnect`() {
        val stub = StubConnection(200, "ok", failOnStream = true)
        assertNull(call("fail", stub = stub))
        assertTrue(stub.disconnected)
    }

    @Test
    fun `连接工厂异常照旧向调用方传播`() {
        try {
            HttpUtil.getText("http://unit.test/open", null) { throw IOException("open failed") }
            fail("openConnection 异常应与抽取前一样向外传播")
        } catch (expected: IOException) {
            assertEquals("open failed", expected.message)
        }
    }

    // ---- 2026-11-11 补齐审查 P3-1 原建议的响应体上限 ----

    @Test
    fun `响应体恰好等于上限时完整返回`() {
        val stub = StubConnection(200, "abcdefgh")
        assertEquals("abcdefgh", callWithLimit("exact", 8, stub))
        assertTrue(stub.disconnected)
    }

    @Test
    fun `响应体超过上限时只取前limit字节且仍disconnect`() {
        val stub = StubConnection(200, "abcdefgh")
        assertEquals("abcde", callWithLimit("over", 5, stub))
        assertTrue(stub.disconnected)
    }

    @Test
    fun `默认上限取自NetworkConfig且超限不读满`() {
        val max = NetworkConfig.MAX_RESPONSE_BYTES
        val stub = StubConnection(200, "x".repeat(max + 64))
        val body = call("huge", stub = stub)
        assertEquals("默认上限应为 256 KiB", max, body?.toByteArray(Charsets.UTF_8)?.size)
        assertTrue(stub.disconnected)
    }

    @Test
    fun `按字节截断中文时保持UTF8原样`() {
        val stub = StubConnection(200, "晴天 - 周杰伦")
        assertEquals("晴天 - 周杰伦", callWithLimit("utf8", 64, stub))
    }

    @Test
    fun `readLimited流提前EOF时按实际字节返回不补零`() {
        val body = HttpUtil.readLimited(ByteArrayInputStream("ok".toByteArray(Charsets.UTF_8)), 100)
        assertEquals("ok", body)
    }

    @Test
    fun `readLimited不越过上限`() {
        val body = HttpUtil.readLimited(ByteArrayInputStream("abcdefgh".toByteArray(Charsets.UTF_8)), 3)
        assertEquals("abc", body)
    }

    @Test
    fun `非2xx时不读流但仍disconnect`() {
        val stub = StubConnection(404, "ignored")
        assertNull(callWithLimit("bad", 4, stub))
        assertTrue(stub.disconnected)
    }
}
