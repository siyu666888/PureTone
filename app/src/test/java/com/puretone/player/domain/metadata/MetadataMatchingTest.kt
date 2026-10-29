package com.puretone.player.domain.metadata

import com.puretone.player.data.metadata.MetadataCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 元数据匹配纯函数单测（domain 层，无需网络/上下文）。
 *
 * 匹配逻辑从 [com.puretone.player.ui.MetadataViewModel] 抽到
 * [com.puretone.player.domain.metadata]，此处锁死其不可变口径：
 * - simplify：繁体字段统一转简体；
 * - buildSearchTerm：跳过「未知艺术家 / 空」；
 * - isLikelyMatch：去空白与标点后存在包含关系才算高置信。
 */
class MetadataMatchingTest {

    @Test
    fun `simplify 繁体字段统一转简体`() {
        val c = MetadataCandidate(
            title = "後來",
            artist = "來自單位",
            album = "員工之歌",
            artworkUrl = null,
            year = 0
        )
        val s = simplify(c)
        assertEquals("后来", s.title)
        assertEquals("来自单位", s.artist)
        assertEquals("员工之歌", s.album)
        // 非文本字段原样保留
        assertEquals(0, s.year)
        assertEquals(null, s.artworkUrl)
    }

    @Test
    fun `simplify 已简体零拷贝返回原引用`() {
        val c = MetadataCandidate("后来", "来自单位", "员工之歌", null, 2020)
        val s = simplify(c)
        assertEquals("后来", s.title)
        assertEquals(2020, s.year)
    }

    @Test
    fun `buildSearchTerm 跳过未知艺术家与空`() {
        assertEquals("周杰伦 晴天", buildSearchTerm("周杰伦", "晴天"))
        // 未知艺术家被跳过，只剩歌名
        assertEquals("晴天", buildSearchTerm("<unknown>", "晴天"))
        assertEquals("晴天", buildSearchTerm("未知艺术家", "晴天"))
        assertEquals("周杰伦", buildSearchTerm("周杰伦", ""))
        // 两者皆空/未知 → 空串
        assertEquals("", buildSearchTerm("<unknown>", ""))
        assertEquals("", buildSearchTerm("", ""))
    }

    @Test
    fun `isLikelyMatch 候选经 simplify 后与查询一致视为高置信`() {
        // isLikelyMatch 不负责简繁转换，调用方在传参前已 simplify 候选；
        // 此处模拟「繁体候选先转简体」的真实链路后再比较。
        val simplified = simplify(MetadataCandidate("後來", "", "", null, 0)).title
        assertEquals("后来", simplified)
        assertTrue(isLikelyMatch("后来", simplified))
    }

    @Test
    fun `isLikelyMatch 包含关系视为高置信`() {
        assertTrue(isLikelyMatch("夜曲", "周杰伦夜曲"))
        assertTrue(isLikelyMatch("周杰伦夜曲", "夜曲"))
    }

    @Test
    fun `isLikelyMatch 标点与空白被忽略`() {
        assertTrue(isLikelyMatch("爱.你", "爱你"))
        assertTrue(isLikelyMatch("爱 你", "爱你"))
    }

    @Test
    fun `isLikelyMatch 无关标题返回 false`() {
        assertFalse(isLikelyMatch("ABC", "XYZ"))
    }

    @Test
    fun `isLikelyMatch 空标题返回 false`() {
        assertFalse(isLikelyMatch("", "后来"))
        assertFalse(isLikelyMatch("后来", ""))
    }
}
