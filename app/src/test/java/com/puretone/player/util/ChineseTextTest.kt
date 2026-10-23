package com.puretone.player.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ChineseText] 繁→简转换回归测试。
 *
 * 重点覆盖 **增补平面汉字（CJK 扩展 B）导致的码点错位**：
 * 映射表里有 631 个扩展 B 汉字，在 JVM 里各占两个 UTF-16 代理码元。
 * 若按 UTF-16 下标成对取字，首个扩展 B 汉字之后的每一对都会错位一格，
 * 于是「繁體中文測試」被转成「緑體中文测試」、「周杰伦」被转成「周傖倲」。
 * 这些用例把该行为钉死，防止回归。
 */
class ChineseTextTest {

    @Test
    fun `converts traditional to simplified`() {
        assertEquals("繁体中文测试", ChineseText.toSimplified("繁體中文測試"))
        assertEquals("周杰伦", ChineseText.toSimplified("周杰倫"))
        assertEquals("华语流行", ChineseText.toSimplified("華語流行"))
        assertEquals("爱你一万年", ChineseText.toSimplified("愛你一萬年"))
    }

    @Test
    fun `keeps already simplified text unchanged`() {
        // 曾经的错位映射会把简体字也改错（杰→傖、伦→倲），这里必须保持原样
        assertEquals("周杰伦", ChineseText.toSimplified("周杰伦"))
        assertEquals("青花瓷", ChineseText.toSimplified("青花瓷"))
    }

    @Test
    fun `converts across surrogate pair boundary correctly`() {
        // 「靐」之后紧跟扩展 B 汉字，是映射表中代理对出现的位置；
        // 逐字转换仍应正确
        assertEquals("简简单单", ChineseText.toSimplified("簡簡單單"))
        assertEquals("韦伯", ChineseText.toSimplified("韋伯"))
    }

    @Test
    fun `handles supplementary plane characters in input`() {
        // 扩展 B 汉字（𦈏 之类）在输入里必须原样保留，不能被拆成代理对
        val ext = "\uD842\uDF8F" // 𦈏
        assertEquals(ext, ChineseText.toSimplified(ext))
        assertEquals("繁\uD842\uDF8F", ChineseText.toSimplified("繁\uD842\uDF8F"))
    }

    @Test
    fun `returns same reference when no change needed`() {
        val input = "already simple 123"
        assertSame(input, ChineseText.toSimplified(input))
    }

    @Test
    fun `handles null and empty`() {
        assertNull(ChineseText.toSimplified(null))
        assertEquals("", ChineseText.toSimplified(""))
    }

    @Test
    fun `mapping table has no duplicate keys with conflicting values`() {
        // 独立复核：映射表按码点成对解读后不应出现「同键不同值」
        val cps = ChineseText.T2S_PAIRS.codePoints().toArray()
        assertTrue("table length must be even (code points): ${cps.size}", cps.size % 2 == 0)
        val seen = HashMap<Int, Int>()
        var i = 0
        while (i + 1 < cps.size) {
            val k = cps[i]
            val v = cps[i + 1]
            val prev = seen.put(k, v)
            assertTrue(
                "conflicting mapping for U+%04X: %s vs %s".format(k, prev, v),
                prev == null || prev == v
            )
            i += 2
        }
    }
}
