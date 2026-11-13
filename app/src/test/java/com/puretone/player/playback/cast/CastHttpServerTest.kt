package com.puretone.player.playback.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [CastHttpServer.parseRange] 单测。
 *
 * 渲染器 seek 全靠 Range：头解析错一处，电视就会从头开始播（或直接 416 卡死）。
 * 覆盖 `a-b` / `a-` / `-n` 三种写法与越界、倒序两种异常。
 */
class CastHttpServerTest {

    private fun parse(header: String?, size: Long) = CastHttpServer.parseRange(header, size)

    @Test
    fun `无 Range 头时整文件下发`() {
        assertEquals(CastHttpServer.RangeSpec.Full, parse(null, 100))
    }

    @Test
    fun `闭区间按请求返回`() {
        assertEquals(CastHttpServer.RangeSpec.Partial(0, 99), parse("bytes=0-99", 100))
    }

    @Test
    fun `只给起点时到文件末尾`() {
        assertEquals(CastHttpServer.RangeSpec.Partial(50, 99), parse("bytes=50-", 100))
    }

    @Test
    fun `后缀写法取末尾若干字节`() {
        assertEquals(CastHttpServer.RangeSpec.Partial(90, 99), parse("bytes=-10", 100))
    }

    @Test
    fun `终点越界会被夹到文件末尾`() {
        assertEquals(CastHttpServer.RangeSpec.Partial(0, 99), parse("bytes=0-9999", 100))
    }

    @Test
    fun `起点越界不可满足`() {
        assertEquals(CastHttpServer.RangeSpec.Unsatisfiable, parse("bytes=100-", 100))
    }

    @Test
    fun `终点小于起点不可满足`() {
        assertEquals(CastHttpServer.RangeSpec.Unsatisfiable, parse("bytes=50-10", 100))
    }

    @Test
    fun `空文件不参与分段`() {
        assertEquals(CastHttpServer.RangeSpec.Full, parse("bytes=0-10", 0))
    }

    @Test
    fun `大小写不敏感`() {
        assertEquals(CastHttpServer.RangeSpec.Partial(0, 9), parse("Bytes=0-9", 100))
    }

    // P1-1：响应状态只认解析结果，不能因为「请求里带过 Range 头」就回 206。
    // 旧实现 sendFile 用 rangeHeader != null 判分段，非法 Range 会回 206 +
    // Content-Range: 0-{size-1} 却发整文件，协议自相矛盾。

    @Test
    fun `无法识别的Range按整文件200下发`() {
        val spec = parse("garbage", 100)
        assertEquals(CastHttpServer.RangeSpec.Full, spec)
        assertEquals(200, CastHttpServer.responseStatus(spec))
    }

    @Test
    fun `不以bytes开头的Range按整文件200下发`() {
        assertEquals(200, CastHttpServer.responseStatus(parse("items=0-9", 100)))
    }

    @Test
    fun `合法分段才是206`() {
        assertEquals(206, CastHttpServer.responseStatus(parse("bytes=0-9", 100)))
    }

    @Test
    fun `没有Range头是200且越界范围是416`() {
        assertEquals(200, CastHttpServer.responseStatus(parse(null, 100)))
        assertEquals(416, CastHttpServer.responseStatus(parse("bytes=100-", 100)))
    }

    // R7：供流明细必须同时给出「实际写出 / 计划长度 / 起点 / 耗时 / 异常」，
    // 否则「电视一次拉完大块后几十秒没请求」与「写出一半就断了」在诊断里长得一样。

    @Test
    fun `正常供流明细带写出字节与耗时`() {
        assertEquals(
            "sent=25123456/25123456 start=0 cost=842ms err=-",
            CastHttpServer.formatFetchDetail(0L, 25_123_456L, 25_123_456L, 842L, null)
        )
    }

    @Test
    fun `部分写出与异常原因都可见`() {
        assertEquals(
            "sent=4096/25123456 start=102400 cost=31ms err=IOException:Connection reset",
            CastHttpServer.formatFetchDetail(
                102_400L, 25_123_456L, 4_096L, 31L, "IOException:Connection reset"
            )
        )
    }

    // 缓存文件被清理/清空时不能发「200 + Content-Length: 1」却零字节：
    // 抽成纯函数锁住判定边界，正常文件（size>0）必须一字不变地继续供流。

    @Test
    fun `正常文件返回实际字节数`() {
        val file = File.createTempFile("cast-servable", ".bin")
        try {
            file.writeBytes(ByteArray(37))
            assertEquals(37L, CastHttpServer.servableSize(file)!!)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `空文件不可供流`() {
        val file = File.createTempFile("cast-empty", ".bin")
        try {
            assertNull(CastHttpServer.servableSize(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `被删除的文件不可供流`() {
        val file = File.createTempFile("cast-gone", ".bin")
        file.writeBytes(ByteArray(16))
        file.delete()
        assertNull(CastHttpServer.servableSize(file))
    }

    @Test
    fun `目录不可供流`() {
        val probe = File.createTempFile("cast-dir", ".bin")
        val dir = probe.parentFile!!
        probe.delete()
        assertNull(CastHttpServer.servableSize(dir))
    }

    @Test
    fun `旧token沉寂超时被淘汰后按404处理`() {
        val file = File.createTempFile("cast-old", ".bin")
        try {
            file.writeBytes(ByteArray(16))
            var now = 1_000L
            val registry = CastEntryRegistry(clock = { now })
            registry.register("old", file, "audio/flac")
            now += CastEntryRegistry.ENTRY_TTL_MS + 1
            registry.register("new", file, "audio/flac")
            assertNull(registry.find("old"))
            // 文件本身仍可供流：404 只由 token 失效引起，两条路径共用同一出口
            assertEquals(16L, CastHttpServer.servableSize(file)!!)
        } finally {
            file.delete()
        }
    }

    // 供流链路一致性收口：HEAD 不算取流、响应头只用一次 size、skip 失败必须留痕。

    @Test
    fun `只有GET算真正供流`() {
        assertEquals(true, CastHttpServer.countsAsFetch("GET"))
        assertEquals(false, CastHttpServer.countsAsFetch("HEAD"))
        assertEquals(false, CastHttpServer.countsAsFetch("POST"))
    }

    @Test
    fun `200响应头逐字保持原格式且不带Content-Range`() {
        assertEquals(
            "HTTP/1.1 200 OK\r\nContent-Type: audio/flac\r\nContent-Length: 1000\r\n" +
                "Accept-Ranges: bytes\r\nConnection: close\r\n\r\n",
            CastHttpServer.buildFileHeader(200, "audio/flac", 1000L, 0L, 999L, 1000L)
        )
    }

    // R4-6：MIME 拼进响应头前统一过白名单，锁住「不注入、不抛异常、正常值逐字不变」。

    @Test
    fun `含CRLF的MIME被回退且不会拼出伪造响应头`() {
        val header = CastHttpServer.buildFileHeader(
            200, "audio/flac\r\nX-Injected: 1", 1000L, 0L, 999L, 1000L
        )
        assertEquals(
            "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: 1000\r\n" +
                "Accept-Ranges: bytes\r\nConnection: close\r\n\r\n",
            header
        )
        assertEquals(1, header.lines().count { it.startsWith("Content-Type: ") })
        assertTrue(!header.contains("X-Injected"))
    }

    @Test
    fun `各种破坏头结构的MIME都回退到同一个安全值`() {
        val dirty = listOf(
            "audio/flac\r\nX-Injected: 1",
            "audio/flac\nX-Injected: 1",
            "audio/flac\rX-Injected: 1",
            "audio/flac; charset=utf-8",
            "audio/flac\" onload=\"x",
            "audio / flac",
            "",
            "\r\n"
        )
        dirty.forEach { mime ->
            val header = CastHttpServer.buildFileHeader(200, mime, 10L, 0L, 9L, 10L)
            assertTrue(
                "脏 MIME 未回退: $mime",
                header.contains("Content-Type: application/octet-stream\r\n")
            )
            assertEquals(
                "脏 MIME 改变了头结构: $mime",
                1,
                header.lines().count { it.startsWith("Content-Type: ") }
            )
            assertTrue("脏 MIME 破坏了头尾: $mime", header.endsWith("Connection: close\r\n\r\n"))
        }
    }

    @Test
    fun `mimeTypeOf的全部返回值拼头后逐字不变`() {
        val names = listOf(
            "a.mp3", "a.flac", "a.wav", "a.m4a", "a.aac",
            "a.mp4", "a.ogg", "a.oga", "a.unknown"
        )
        names.forEach { name ->
            val mime = CastSourcePolicy.mimeTypeOf(name)
            assertEquals(
                "正常 MIME 被改写: $name / $mime",
                "HTTP/1.1 200 OK\r\nContent-Type: $mime\r\nContent-Length: 1000\r\n" +
                    "Accept-Ranges: bytes\r\nConnection: close\r\n\r\n",
                CastHttpServer.buildFileHeader(200, mime, 1000L, 0L, 999L, 1000L)
            )
        }
    }

    @Test
    fun `206响应头的total与end共用同一次size判定`() {
        val size = 1000L
        val header = CastHttpServer.buildFileHeader(206, "audio/flac", 500L, 500L, 999L, size)
        assertTrue(header.startsWith("HTTP/1.1 206 Partial Content\r\n"))
        assertTrue(header.contains("Content-Range: bytes 500-999/$size\r\n"))
        assertTrue(header.contains("Content-Length: 500\r\n"))
        assertTrue(header.endsWith("Connection: close\r\n\r\n"))
    }

    @Test
    fun `servableSize与响应头串起同一次size`() {
        val file = File.createTempFile("cast-header", ".bin")
        try {
            file.writeBytes(ByteArray(1000))
            val size = CastHttpServer.servableSize(file)!!
            val header = CastHttpServer.buildFileHeader(
                206, "audio/flac", size, 0L, size - 1, size
            )
            assertTrue(header.contains("Content-Range: bytes 0-999/1000\r\n"))
            assertTrue(header.contains("Content-Length: 1000\r\n"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `skip未到位时给出可落盘的失败原因`() {
        assertEquals("FileTooShort:skip=128", CastHttpServer.skipShortfallReason(128L))
    }
}
