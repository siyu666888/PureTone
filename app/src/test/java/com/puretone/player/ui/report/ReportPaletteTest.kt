package com.puretone.player.ui.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 报告 V2 配色令牌自查（方案稿第 6 节 WCAG AA 校验）。
 *
 * 断言只盯「性质」而非设计稿标注的示意数字：骨架正文必须达 AA（>=4.5），
 * 琥珀/焰橙在两种主题下都不得作为文字（浅底 <4.5），洋红暖色仅达大字级（>=3）。
 * 实测值与设计稿表格的差异记录在 dist 证据文档。
 */
class ReportPaletteTest {

    @Test
    fun `两主题骨架正文字色相对底色达 WCAG AA`() {
        listOf(ReportPalette.LIGHT, ReportPalette.DARK, ReportPalette.BLACK).forEach { p ->
            assertTrue("ink/bg=${ReportPalette.contrastRatio(p.ink, p.bg)}", ReportPalette.contrastRatio(p.ink, p.bg) >= 4.5)
            assertTrue("ink2/bg=${ReportPalette.contrastRatio(p.ink2, p.bg)}", ReportPalette.contrastRatio(p.ink2, p.bg) >= 4.5)
        }
    }

    @Test
    fun `琥珀与焰橙在浅底不可承载文字而在深底可以`() {
        val l = ReportPalette.LIGHT
        // 浅底：s1/s2 对比度 < 4.5，故禁用作文字，只能填充/描边
        val cr1 = ReportPalette.contrastRatio(l.spectrum1, l.bg)
        val cr2 = ReportPalette.contrastRatio(l.spectrum2, l.bg)
        assertTrue("s1/light=$cr1", cr1 < 4.5)
        assertTrue("s2/light=$cr2", cr2 < 4.5)
    }

    @Test
    fun `洋红暖色文字在浅底达大字级但不达正文级`() {
        val l = ReportPalette.LIGHT
        val cr = ReportPalette.contrastRatio(l.spectrum3, l.bg)
        assertTrue("洋红浅底对比度应 >=3（大字可用），实测 $cr", cr >= 3.0)
        assertTrue("洋红浅底对比度应 <4.5（正文不可用），实测 $cr", cr < 4.5)
    }

    @Test
    fun `Dark 光谱整体向高明度侧平移而非 Light 反相`() {
        val l = ReportPalette.LIGHT
        val d = ReportPalette.DARK
        // 洋红/幽紫在 Dark 侧提亮：DARK.s3 != LIGHT.s3，且对深底对比度 >= 4.5
        assertNotEquals(l.spectrum3, d.spectrum3)
        assertNotEquals(l.spectrum4, d.spectrum4)
        val crMagentaDark = ReportPalette.contrastRatio(d.spectrum3, d.bg)
        assertTrue("洋红/深底=$crMagentaDark", crMagentaDark >= 4.5)
    }

    @Test
    fun `叙事光谱严格六阶且越界钳到两端`() {
        val p = ReportPalette.LIGHT
        val levels = (1..6).map { p.spectrum(it) }
        assertEquals(6, levels.toSet().size)
        assertEquals(p.spectrum1, p.spectrum(0))
        assertEquals(p.spectrum1, p.spectrum(-5))
        assertEquals(p.spectrum6, p.spectrum(7))
        assertEquals(p.spectrum6, p.spectrum(Int.MAX_VALUE))
    }

    @Test
    fun `三主题映射与纯黑仅压暗底色`() {
        assertEquals(ReportPalette.LIGHT, ReportPalette.of(PosterTheme.LIGHT))
        assertEquals(ReportPalette.DARK, ReportPalette.of(PosterTheme.DARK))
        assertEquals(ReportPalette.BLACK, ReportPalette.of(PosterTheme.BLACK))
        // BLACK 与 DARK 光谱一致，仅 bg/card 不同（AMOLED）
        assertEquals(ReportPalette.DARK.spectrum2, ReportPalette.BLACK.spectrum2)
        assertNotEquals(ReportPalette.DARK.bg, ReportPalette.BLACK.bg)
    }

    @Test
    fun `渐变色标取自叙事光谱且色数固定`() {
        val p = ReportPalette.LIGHT
        assertEquals(listOf(p.spectrum2, p.spectrum3, p.spectrum4), p.auroraStops)
        assertEquals(2, p.barStops.size)
        assertEquals(2, p.coolStops.size)
        assertEquals(listOf(p.spectrum5, p.spectrum4), p.coolStops)
    }
}
