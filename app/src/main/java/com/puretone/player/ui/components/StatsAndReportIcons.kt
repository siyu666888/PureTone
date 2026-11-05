package com.puretone.player.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 听歌数据 / 听歌报告 入口图标（自绘线形家族，与 [ControlIcons] 同语言）。
 *
 * 二者共用同一圆角面板外框、同一线宽(1.6)、同一圆头圆角与 28×28 视口，
 * 仅在内部图形区分语义：
 * - 数据 = 面板 + 上升折线 + 4 节点（读作「随时间变化的趋势明细」）；
 * - 报告 = 面板 + 标题线 + 3 根高低柱状（读作「成稿文档里夹着图表」）。
 *
 * 颜色全部取 [Color.Black] 占位，由调用处 `Icon(tint = ...)` 着色，
 * 跟随 App 主题（深色/浅色自动适配）。
 */
object StatsAndReportIcons {

    private const val STROKE = 1.6f
    private const val DOT_R = 1.3f
    private const val BAR_RX = 1.2f
    private const val FRAME_RX = 3.5f

    /** 听歌报告：圆角面板 + 顶部标题线 + 3 根高低柱状（已确认采用） */
    val Report: ImageVector by lazy {
        ImageVector.Builder("pt_report", 28.dp, 28.dp, 28f, 28f).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                roundRect(4f, 4f, 20f, 20f, FRAME_RX)
            }
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(8f, 9f); lineTo(15f, 9f)
            }
            path(fill = SolidColor(Color.Black)) {
                bar(8f, 15f, 3.2f, 5f, BAR_RX)
                bar(12.4f, 11.5f, 3.2f, 8.5f, BAR_RX)
                bar(16.8f, 8f, 3.2f, 12f, BAR_RX)
            }
        }.build()
    }

    /** 听歌数据：圆角面板 + 上升折线 + 4 节点 + 基线（无标题线，读作纯图表） */
    val Stats: ImageVector by lazy {
        ImageVector.Builder("pt_stats", 28.dp, 28.dp, 28f, 28f).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                roundRect(4f, 4f, 20f, 20f, FRAME_RX)
            }
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(8f, 20f); lineTo(20f, 20f) // 基线
                moveTo(8f, 17f); lineTo(11.5f, 14f); lineTo(15f, 11f); lineTo(20f, 7.5f) // 上升折线
            }
            path(fill = SolidColor(Color.Black)) {
                dot(8f, 17f); dot(11.5f, 14f); dot(15f, 11f); dot(20f, 7.5f)
            }
        }.build()
    }

    /** 圆角矩形（四角相等圆角，顺时针外框） */
    private fun PathBuilder.roundRect(x: Float, y: Float, w: Float, h: Float, r: Float) {
        val x2 = x + w
        val y2 = y + h
        moveTo(x + r, y)
        lineTo(x2 - r, y)
        arcTo(r, r, 0f, false, true, x2, y + r)
        lineTo(x2, y2 - r)
        arcTo(r, r, 0f, false, true, x2 - r, y2)
        lineTo(x + r, y2)
        arcTo(r, r, 0f, false, true, x, y2 - r)
        lineTo(x, y + r)
        arcTo(r, r, 0f, false, true, x + r, y)
        close()
    }

    /** 顶圆角、底直角的柱状（底贴基线，直角被基线遮住） */
    private fun PathBuilder.bar(x: Float, y: Float, w: Float, h: Float, r: Float) {
        val x2 = x + w
        val y2 = y + h
        moveTo(x, y2)
        lineTo(x, y + r)
        arcTo(r, r, 0f, false, true, x + r, y)
        lineTo(x2 - r, y)
        arcTo(r, r, 0f, false, true, x2, y + r)
        lineTo(x2, y2)
        close()
    }

    /** 实心圆点（两段半弧合成整圆） */
    private fun PathBuilder.dot(cx: Float, cy: Float) {
        moveTo(cx + DOT_R, cy)
        arcTo(DOT_R, DOT_R, 0f, true, true, cx - DOT_R, cy)
        arcTo(DOT_R, DOT_R, 0f, true, true, cx + DOT_R, cy)
        close()
    }
}
