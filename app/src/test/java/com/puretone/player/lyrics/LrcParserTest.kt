package com.puretone.player.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserTest {

    @Test
    fun `parses standard lrc with single timestamps`() {
        val lrc = """
            [ti:青花瓷]
            [ar:周杰伦]
            [00:00.50]素胚勾勒出青花笔锋浓转淡
            [00:12.00]瓶身描绘的牡丹一如你初妆
        """.trimIndent()

        val result = LrcParser.parse(lrc)

        assertEquals("青花瓷", result.title)
        assertEquals("周杰伦", result.artist)
        assertEquals(2, result.lines.size)
        assertEquals(500L, result.lines[0].timeMs)
        assertEquals("素胚勾勒出青花笔锋浓转淡", result.lines[0].text)
        assertEquals(12_000L, result.lines[1].timeMs)
    }

    @Test
    fun `parses multiple timestamps on one line`() {
        val lrc = "[00:01.00][00:30.00]副歌"

        val result = LrcParser.parse(lrc)

        assertEquals(2, result.lines.size)
        assertTrue(result.lines.all { it.text == "副歌" })
        assertEquals(1_000L, result.lines[0].timeMs)
        assertEquals(30_000L, result.lines[1].timeMs)
    }

    @Test
    fun `applies offset positive makes lyrics earlier`() {
        val lrc = """
            [offset:+500]
            [00:10.00]测试
        """.trimIndent()

        val result = LrcParser.parse(lrc)

        assertEquals(9_500L, result.lines[0].timeMs)
    }

    @Test
    fun `applies offset negative delays lyrics`() {
        val lrc = """
            [offset:-250]
            [00:10.00]测试
        """.trimIndent()

        val result = LrcParser.parse(lrc)

        assertEquals(10_250L, result.lines[0].timeMs)
    }

    @Test
    fun `centisecond and millisecond fractions`() {
        val lrc = "[00:01.5]半秒\n[00:02.34]三点四\n[00:03.567]五六七"

        val result = LrcParser.parse(lrc)

        assertEquals(1_500L, result.lines[0].timeMs)
        assertEquals(2_340L, result.lines[1].timeMs)
        assertEquals(3_567L, result.lines[2].timeMs)
    }

    @Test
    fun `skips empty text lines and metadata`() {
        val lrc = "[ti:歌名]\n[00:00.00]\n[00:01.00]有词"

        val result = LrcParser.parse(lrc)

        assertEquals(1, result.lines.size)
        assertEquals("有词", result.lines[0].text)
    }

    @Test
    fun `empty content returns empty`() {
        assertTrue(LrcParser.parse("").lines.isEmpty())
        assertTrue(LrcParser.parse("   \n  ").lines.isEmpty())
    }

    @Test
    fun `lineIndexAt binary search`() {
        val parsed = LrcParser.parse(
            "[00:00.00]一\n[00:10.00]二\n[00:20.00]三"
        )
        assertEquals(-1, parsed.lineIndexAt(-1))
        assertEquals(0, parsed.lineIndexAt(0))
        assertEquals(0, parsed.lineIndexAt(9_999))
        assertEquals(1, parsed.lineIndexAt(10_000))
        assertEquals(2, parsed.lineIndexAt(100_000))
    }

    @Test
    fun `decode falls back to gbk for invalid utf8`() {
        // "歌词" 的 GBK 编码不是合法 UTF-8
        val gbkBytes = "歌词".toByteArray(java.nio.charset.Charset.forName("GBK"))
        assertEquals("歌词", LrcParser.decode(gbkBytes))

        val utf8Bytes = "歌词".toByteArray(java.nio.charset.Charset.forName("UTF-8"))
        assertEquals("歌词", LrcParser.decode(utf8Bytes))
    }

    @Test
    fun `missing metadata stays null`() {
        val parsed = LrcParser.parse("[00:01.00]无元信息")
        assertNull(parsed.title)
        assertNull(parsed.artist)
    }

    @Test
    fun `formatTime`() {
        assertEquals("01:02.03", LrcParser.formatTime(62_030))
        assertEquals("00:00.00", LrcParser.formatTime(-5))
    }
}
