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
}
