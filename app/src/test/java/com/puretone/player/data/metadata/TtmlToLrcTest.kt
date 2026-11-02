package com.puretone.player.data.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TTML → LRC 转换测试。
 *
 * 转换器是纯函数（无 IO），故用普通 JVM 单测覆盖。
 * 真实 TTML 样本取自 AMLL 词库（周杰伦《晴天》），此处做了最小化裁剪。
 */
class TtmlToLrcTest {

    @Test
    fun `逐词 span 拼接为整行文本并取行首时间戳`() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml"><body><div>
            <p begin="00:29.231" end="00:32.723"><span begin="00:29.231" end="00:29.692">故</span><span begin="00:29.692" end="00:30.057">事</span><span begin="00:30.057" end="00:30.472">的</span></p>
            <p begin="00:32.723" end="00:36.238"><span begin="00:32.723" end="00:33.132">从</span><span begin="00:33.132" end="00:33.571">出</span></p>
            </div></body></tt>
        """.trimIndent()

        val lrc = TtmlToLrc.convert(ttml)

        assertEquals("[00:29.23]故事的\n[00:32.72]从出\n", lrc)
    }

    @Test
    fun `span 之外的裸文本也被保留`() {
        val ttml = """
            <tt><body><div>
            <p begin="00:10.000" end="00:12.000">前面<span begin="00:10.000" end="00:11.000">核心</span>后面</p>
            </div></body></tt>
        """.trimIndent()

        val lrc = TtmlToLrc.convert(ttml)

        assertEquals("[00:10.00]前面核心后面\n", lrc)
    }

    @Test
    fun `缺少 p 的 begin 时退回用第一个 span 的 begin`() {
        val ttml = """
            <tt><body><div>
            <p end="00:12.000"><span begin="00:11.500" end="00:12.000">词</span></p>
            </div></body></tt>
        """.trimIndent()

        val lrc = TtmlToLrc.convert(ttml)

        assertEquals("[00:11.50]词\n", lrc)
    }

    @Test
    fun `空行被跳过`() {
        // 空 <p>（既无文本也无 span）没有任何可显示内容，必须跳过；
        // 而「有 span 但 p 缺 begin」的行会退回用 span 的时间戳（见上一用例）。
        val ttml = """
            <tt><body><div>
            <p begin="00:01.000" end="00:02.000"><span begin="00:01.000" end="00:02.000">有</span></p>
            <p begin="00:03.000" end="00:04.000"></p>
            </div></body></tt>
        """.trimIndent()

        val lrc = TtmlToLrc.convert(ttml)

        assertEquals("[00:01.00]有\n", lrc)
    }

    @Test
    fun `实体被正确反转义`() {
        val ttml = """
            <tt><body><div>
            <p begin="00:01.000" end="00:02.000"><span begin="00:01.000" end="00:02.000">a &amp; b &lt;c&gt;</span></p>
            </div></body></tt>
        """.trimIndent()

        val lrc = TtmlToLrc.convert(ttml)

        assertEquals("[00:01.00]a & b <c>\n", lrc)
    }

    @Test
    fun `非 TTML 内容返回 null`() {
        assertNull(TtmlToLrc.convert(""))
        assertNull(TtmlToLrc.convert("hello world"))
        assertNull(TtmlToLrc.convert("<tt><body></body></tt>"))
    }

    @Test
    fun `时间戳解析覆盖多种写法`() {
        assertEquals(0L, TtmlToLrc.parseTime("00:00.000"))
        assertEquals(29_231L, TtmlToLrc.parseTime("00:29.231"))
        assertEquals(29_230L, TtmlToLrc.parseTime("00:29.23"))
        assertEquals(29_200L, TtmlToLrc.parseTime("00:29.2"))
        assertEquals(3_723_000L, TtmlToLrc.parseTime("01:02:03.000"))
        assertEquals(1_234L, TtmlToLrc.parseTime("1.234"))
        assertNull(TtmlToLrc.parseTime(null))
        assertNull(TtmlToLrc.parseTime("abc"))
    }

    @Test
    fun `转换结果可被 LrcParser 直接解析且行数正确`() {
        val ttml = """
            <tt><body><div>
            <p begin="00:01.000" end="00:02.000"><span begin="00:01.000" end="00:02.000">第一行</span></p>
            <p begin="00:05.500" end="00:06.000"><span begin="00:05.500" end="00:06.000">第二行</span></p>
            </div></body></tt>
        """.trimIndent()

        val lrc = TtmlToLrc.convert(ttml)
        val parsed = com.puretone.player.lyrics.LrcParser.parse(lrc!!)

        assertEquals(2, parsed.lines.size)
        assertEquals("第一行", parsed.lines[0].text)
        assertEquals(1_000L, parsed.lines[0].timeMs)
        assertEquals("第二行", parsed.lines[1].text)
        assertEquals(5_500L, parsed.lines[1].timeMs)
        assertTrue(parsed.lines[0].timeMs < parsed.lines[1].timeMs)
    }
}
