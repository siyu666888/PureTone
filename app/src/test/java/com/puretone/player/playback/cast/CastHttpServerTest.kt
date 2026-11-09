package com.puretone.player.playback.cast

import org.junit.Assert.assertEquals
import org.junit.Test

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
}
