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
    fun `P2-9 offset after lyrics still applies to preceding lines`() {
        // offset 标签出现在歌词行之后（非头部）：之前的行必须以同一 offset 计算，
        // 否则时间轴整体错位（单遍解析的历史 bug）。
        val lrc = """
            [00:10.00]第一句
            [00:20.00]第二句
            [offset:+500]
            [00:30.00]第三句
        """.trimIndent()

        val result = LrcParser.parse(lrc)

        assertEquals(3, result.lines.size)
        assertEquals("第一句", result.lines[0].text)
        assertEquals(9_500L, result.lines[0].timeMs)   // 10s - 500ms
        assertEquals(19_500L, result.lines[1].timeMs)  // 20s - 500ms
        assertEquals(29_500L, result.lines[2].timeMs)  // 30s - 500ms
    }

    @Test
    fun `formatTime`() {
        assertEquals("01:02.03", LrcParser.formatTime(62_030))
        assertEquals("00:00.00", LrcParser.formatTime(-5))
    }

    @Test
    fun `parse strips utf8 bom from content`() {
        // 带 BOM 的字符串直接解析：第一行元信息不能被丢弃
        val lrc = "\uFEFF[ti:青花瓷]\n[00:01.00]素胚"

        val result = LrcParser.parse(lrc)

        assertEquals("青花瓷", result.title)
        assertEquals(1, result.lines.size)
        assertEquals("素胚", result.lines[0].text)
    }

    @Test
    fun `parse strips bom on first lyric line`() {
        val lrc = "\uFEFF[00:01.00]第一句"

        val result = LrcParser.parse(lrc)

        assertEquals(1, result.lines.size)
        assertEquals("第一句", result.lines[0].text)
    }

    @Test
    fun `parse strips enhanced per-word tags`() {
        // 增强型 LRC：逐字标签必须剥掉，不能把 <0,120> 当正文显示
        val lrc = "[00:01.00]<0,120>你<120,260>好<260,300>世<300,400>界"

        val result = LrcParser.parse(lrc)

        assertEquals(1, result.lines.size)
        assertEquals("你好世界", result.lines[0].text)
    }

    @Test
    fun `decode strips utf8 bom bytes`() {
        val text = "[00:01.00]歌词"
        val withBom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            text.toByteArray(Charsets.UTF_8)

        assertEquals(text, LrcParser.decode(withBom))
    }

    @Test
    fun `decode reads utf16le with bom`() {
        val text = "[00:01.00]歌词"
        val utf16 = text.toByteArray(Charsets.UTF_16LE)
        val withBom = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + utf16

        assertEquals(text, LrcParser.decode(withBom))
    }

    @Test
    fun `decode reads utf16be with bom`() {
        val text = "[00:01.00]歌词"
        val utf16 = text.toByteArray(Charsets.UTF_16BE)
        val withBom = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + utf16

        assertEquals(text, LrcParser.decode(withBom))
    }

    @Test
    fun `decode falls back to gb18030 as last resort`() {
        // GB18030 独有的 4 字节序列（GBK 严格解码失败）必须能兜底解码
        val gb18030 = java.nio.charset.Charset.forName("GB18030")
        val bytes = "𠀀".toByteArray(gb18030) // CJK 扩展 B 区，GB18030 四字节编码
        assertEquals("𠀀", LrcParser.decode(bytes))
    }
}
