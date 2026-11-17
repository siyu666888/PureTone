package com.puretone.player.ui.report

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * 报告 V2 配色在 Compose 侧的注入点。
 *
 * [LocalReportPalette] 由报告页用 [ProvideReportPalette] 下发，子树一律通过它取色，
 * 不在业务 Composable 里写 hex 或 `MaterialTheme.colorScheme`（报告是情绪产品，
 * 配色独立于工具型主界面）。Canvas 海报侧走 [ReportPalette.of] 同一份令牌，保证同源。
 */
val LocalReportPalette = compositionLocalOf<ReportPalette> {
    error("ReportPalette 未注入：报告子树必须由 ProvideReportPalette 包裹")
}

/** 用海报主题对应的调色板包裹报告子树 */
@Composable
fun ProvideReportPalette(theme: PosterTheme, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalReportPalette provides ReportPalette.of(theme)) {
        content()
    }
}

/**
 * 报告渐变唯一入口——设计稿规定「只留 4 组，禁止自创」，其中文字/大色块用 Aurora，
 * 柱体用 Bar，深夜长尾反向强调用 Cool，Dark 模式主渐变用 AuroraDark。
 *
 * 业务侧不得直接调用 `Brush.linearGradient`，一律走这里，角度与色标内聚。
 */
object ReportGradients {
    private val AURORA_DIR = Offset(1000f, 360f) // 104deg：左上→右下，符合中文阅读落点
    private val BAR_DIR = Offset(0f, 1000f) // 180deg：唯一纵向渐变
    private val COOL_DIR = Offset(1000f, 1000f) // 135deg

    /** 主叙事：Hero 数字 / 人格卡 / 封面占位（3 stops） */
    fun aurora(p: ReportPalette): Brush = brush(p.auroraStops, AURORA_DIR)

    /** 柱体填充（2 stops，纵向） */
    fun bar(p: ReportPalette): Brush = brush(p.barStops, BAR_DIR)

    /** 深夜/长尾冷调强调（2 stops） */
    fun cool(p: ReportPalette): Brush = brush(p.coolStops, COOL_DIR)

    /** ARGB Int 令牌 → Compose Color 并构造线性渐变，唯一转换点 */
    private fun brush(stops: List<Int>, end: Offset): Brush =
        Brush.linearGradient(stops.map { Color(it) }, start = Offset.Zero, end = end)
}
