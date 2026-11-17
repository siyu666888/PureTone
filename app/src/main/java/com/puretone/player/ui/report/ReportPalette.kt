package com.puretone.player.ui.report

import kotlin.math.pow

/**
 * 听歌报告 V2「声色叙事」配色令牌（方案稿 2026-11-17）。
 *
 * 双轨色彩体系：
 * - 骨架色（bg/card/line/ink/ink2/ink3/brand）：中性紫灰，负责结构与文字层级，稳定。
 * - 叙事色（spectrum1..6）：琥珀→焰橙→洋红→幽紫→静蓝→青鸣，负责数据表达，色相本身携带信息。
 *
 * 两条硬规则（写进 [contrastRatio] 自查单测）：
 * 1. spectrum1 / spectrum2（琥珀、焰橙）在任何主题下禁止承载文字，只能做填充与描边。
 * 2. 需要暖色文字一律用 spectrum3（洋红），且仅用于大字（>=18px）；正文暖色用 spectrum4。
 *
 * 颜色一律以 AARRGGBB `Int` 存储：Canvas 海报直接用，Compose 侧 `Color(p.xxx)` 转换。
 * 业务代码不得出现 hex 字面量，只能引用本文件的具名令牌。
 *
 * 注意：设计稿表格中的对比度数字为**示意值**，真实取值以 [contrastRatio] 实测为准
 * （如 Light 三级字实测 2.77、洋红文字实测 4.26），详见 dist 证据文档。
 */
data class ReportPalette(
    // 骨架色
    val bg: Int,
    val card: Int,
    val line: Int,
    val ink: Int,
    val ink2: Int,
    val ink3: Int,
    val brand: Int,
    // 叙事光谱（1 低 / 2 中低 / 3 峰 / 4 深夜长尾 / 5 冷春 / 6 其他）
    val spectrum1: Int,
    val spectrum2: Int,
    val spectrum3: Int,
    val spectrum4: Int,
    val spectrum5: Int,
    val spectrum6: Int
) {
    /** 按 1..6 取叙事光谱；越界钳到两端，避免调用方越界崩溃 */
    fun spectrum(level: Int): Int = when (level.coerceIn(1, 6)) {
        1 -> spectrum1
        2 -> spectrum2
        3 -> spectrum3
        4 -> spectrum4
        5 -> spectrum5
        else -> spectrum6
    }

    /** 主叙事渐变三色（Aurora）：洋红为峰点，末端幽紫收尾 */
    val auroraStops: List<Int> get() = listOf(spectrum2, spectrum3, spectrum4)

    /** 柱体渐变两色（Bar，唯一纵向渐变） */
    val barStops: List<Int> get() = listOf(spectrum2, spectrum3)

    /** 冷调渐变两色（Cool，深夜/长尾反向强调） */
    val coolStops: List<Int> get() = listOf(spectrum5, spectrum4)

    companion object {
        val LIGHT = ReportPalette(
            bg = 0xFFFBF8FD.toInt(), card = 0xFFFFFFFF.toInt(), line = 0xFFEAE5F1.toInt(),
            ink = 0xFF22202E.toInt(), ink2 = 0xFF615B78.toInt(), ink3 = 0xFF9A94AE.toInt(),
            brand = 0xFF5E6AD2.toInt(),
            spectrum1 = 0xFFF0884F.toInt(), spectrum2 = 0xFFE8643C.toInt(),
            spectrum3 = 0xFFD63484.toInt(), spectrum4 = 0xFF7B4DE8.toInt(),
            spectrum5 = 0xFF4E9FD8.toInt(), spectrum6 = 0xFF3EBFA5.toInt()
        )

        val DARK = ReportPalette(
            bg = 0xFF0C0B12.toInt(), card = 0xFF16141F.toInt(), line = 0xFF25212F.toInt(),
            ink = 0xFFEFECF6.toInt(), ink2 = 0xFFB8B2CC.toInt(), ink3 = 0xFF807A96.toInt(),
            brand = 0xFFA17BF5.toInt(),
            // 深色底暖橙会「光晕膨胀」压过洋红，整条光谱向高明度侧平移一档。
            spectrum1 = 0xFFF0884F.toInt(), spectrum2 = 0xFFF0884F.toInt(),
            spectrum3 = 0xFFE0559E.toInt(), spectrum4 = 0xFFA17BF5.toInt(),
            spectrum5 = 0xFF5FB0E4.toInt(), spectrum6 = 0xFF4FD3B6.toInt()
        )

        /** 纯黑（AMOLED）复用 Dark 光谱，仅压暗底色与卡片 */
        val BLACK = DARK.copy(bg = 0xFF000000.toInt(), card = 0xFF101016.toInt())

        /** 三主题映射，与 [PosterTheme] 一一对应 */
        fun of(theme: PosterTheme): ReportPalette = when (theme) {
            PosterTheme.LIGHT -> LIGHT
            PosterTheme.DARK -> DARK
            PosterTheme.BLACK -> BLACK
        }

        /** WCAG 2.1 对比度（1..21），纯函数供单测与自查脚本共用 */
        fun contrastRatio(fg: Int, bg: Int): Double {
            val a = luminance(fg)
            val b = luminance(bg)
            val hi = maxOf(a, b)
            val lo = minOf(a, b)
            return (hi + 0.05) / (lo + 0.05)
        }

        private fun luminance(argb: Int): Double {
            fun channel(v: Int): Double {
                val c = (v and 0xFF) / 255.0
                return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
            }
            val r = channel(argb shr 16)
            val g = channel(argb shr 8)
            val b = channel(argb)
            return 0.2126 * r + 0.7152 * g + 0.0722 * b
        }
    }
}
