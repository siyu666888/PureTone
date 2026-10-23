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
 * 播放控制图标（自绘线形家族，统一圆头圆角）。
 *
 * v1.1.7 调整：
 * - 播放 / 上一首 / 下一首 / 暂停 / 播放队列：还原上一版（v1.1.5）细线观感——
 *   « » 双尖角、线宽 1.5、实心圆角播放三角、暂停双圆头竖条（3.4）、队列三线两圆点；
 * - 顺序循环 🔁 / 单曲循环 🔂 / 随机播放 🔀：改为曲线箭头（弧线 + 箭头），
 *   与细线家族统一，颜色由 `Icon(tint = ...)` 着色。
 *
 * 注意：本项目 Compose 的 PathBuilder 无 cubicTo / quadraticBezierTo，
 * 曲线一律用 arcTo（真圆弧）或 lineTo（密集折线近似）。
 */
object ControlIcons {

    /** 上一首 / 下一首 chevron 线宽（还原上一版细线） */
    private const val STROKE_CHEVRON = 1.5f
    /** 队列横线 / 循环 / 随机线宽 */
    private const val STROKE_LINE = 1.8f
    /** 暂停竖条（上一版即较粗） */
    private const val STROKE_PAUSE = 3.4f
    /** 播放实心三角描边（柔化尖角） */
    private const val STROKE_PLAY = 2.4f
    /** 队列圆点半径 */
    private const val DOT_R = 1.7f

    /** 上一首：双左尖角 «（细线 · 圆头圆角） */
    val SkipPrevious: ImageVector by lazy {
        line("pt_skip_previous", STROKE_CHEVRON) {
            moveTo(11.5f, 6f); lineTo(6f, 14f); lineTo(11.5f, 22f)
            moveTo(17.5f, 6f); lineTo(12f, 14f); lineTo(17.5f, 22f)
        }
    }

    /** 下一首：双右尖角 » */
    val SkipNext: ImageVector by lazy {
        line("pt_skip_next", STROKE_CHEVRON) {
            moveTo(16.5f, 6f); lineTo(22f, 14f); lineTo(16.5f, 22f)
            moveTo(10.5f, 6f); lineTo(16f, 14f); lineTo(10.5f, 22f)
        }
    }

    /** 播放：实心圆角三角（指向右，描边柔化尖角） */
    val Play: ImageVector by lazy {
        filled("pt_play", STROKE_PLAY) {
            moveTo(21f, 14f); lineTo(9f, 8f); lineTo(9f, 20f); close()
        }
    }

    /** 暂停：两条圆头竖条（较粗） */
    val Pause: ImageVector by lazy {
        line("pt_pause", STROKE_PAUSE) {
            moveTo(10.5f, 7f); lineTo(10.5f, 21f)
            moveTo(17.5f, 7f); lineTo(17.5f, 21f)
        }
    }

    /** 播放队列：三条横线 + 右侧两个圆点 */
    val Queue: ImageVector by lazy {
        ImageVector.Builder("pt_queue", 28.dp, 28.dp, 28f, 28f).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = STROKE_LINE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(7f, 9f); lineTo(15.5f, 9f)
                moveTo(7f, 14f); lineTo(15.5f, 14f)
                moveTo(7f, 19f); lineTo(15.5f, 19f)
            }
            path(fill = SolidColor(Color.Black)) {
                dot(19.5f, 9f)
                dot(19.5f, 19f)
            }
        }.build()
    }

    /**
     * 圆角长方形循环轨道：上下两段路径，各带一个箭头收尾相连，
     * 相连处（左右两侧中部）留空隙——远看是一个循环，近看两段分明。
     *
     * 轨道 x=4..24 / y=6..22（20×16），圆角半径 4；
     * 箭头翼距 4.4 / 高 2.5（与既有箭头一致）；
     * 空隙：右侧 箭头尖 14.1 ↔ 下段起点 16.4 = 2.3，左侧对称。
     */
    private fun PathBuilder.rectLoop() {
        // 上半段：左竖上行 → 左上圆角 → 上边向右 → 右上圆角 → 右竖下行
        moveTo(4f, 11.6f); lineTo(4f, 10f)
        arcTo(4f, 4f, 0f, false, true, 8f, 6f)
        lineTo(20f, 6f)
        arcTo(4f, 4f, 0f, false, true, 24f, 10f)
        lineTo(24f, 11.6f)
        // 右上箭头（向下，衔接下半段起点，相连处留空隙）
        moveTo(24f, 14.1f); lineTo(21.8f, 11.6f)
        moveTo(24f, 14.1f); lineTo(26.2f, 11.6f)
        // 下半段：右竖下行 → 右下圆角 → 下边向左 → 左下圆角 → 左竖上行
        moveTo(24f, 16.4f); lineTo(24f, 18f)
        arcTo(4f, 4f, 0f, false, true, 20f, 22f)
        lineTo(8f, 22f)
        arcTo(4f, 4f, 0f, false, true, 4f, 18f)
        lineTo(4f, 16.4f)
        // 左下箭头（向上，衔接上半段起点，相连处留空隙）
        moveTo(4f, 13.9f); lineTo(1.8f, 16.4f)
        moveTo(4f, 13.9f); lineTo(6.2f, 16.4f)
    }

    /** 顺序循环（列表循环）：圆角长方形轨道 + 两箭头收尾相连、相连处留空隙 */
    val RepeatAll: ImageVector by lazy {
        line("pt_repeat_all", STROKE_LINE) { rectLoop() }
    }

    /** 单曲循环：顺序循环 + 中央「1」 */
    val RepeatOne: ImageVector by lazy {
        line("pt_repeat_one", STROKE_LINE) {
            rectLoop()
            moveTo(14f, 11.8f); lineTo(14f, 16.2f)
            moveTo(12.2f, 13f); lineTo(14f, 11.8f)
        }
    }

    /** 随机播放 🔀：两条交叉曲线 + 右端双向箭头 */
    val Shuffle: ImageVector by lazy {
        line("pt_shuffle", STROKE_LINE) {
            // 上：左水平 → 曲线下行穿中 → 右水平
            moveTo(4f, 10f); lineTo(9f, 10f)
            lineTo(12.5f, 12f); lineTo(15f, 15f); lineTo(18f, 18f); lineTo(20.5f, 18f)
            moveTo(18.3f, 16.4f); lineTo(20.5f, 18f); lineTo(18.3f, 19.6f)
            // 下：左水平 → 曲线上行穿中 → 右水平
            moveTo(4f, 18f); lineTo(9f, 18f)
            lineTo(12.5f, 16f); lineTo(15f, 13f); lineTo(18f, 10f); lineTo(20.5f, 10f)
            moveTo(18.3f, 8.4f); lineTo(20.5f, 10f); lineTo(18.3f, 11.6f)
        }
    }

    /** 在当前子路径画一个实心圆点（两段半弧合成整圆） */
    private fun PathBuilder.dot(cx: Float, cy: Float) {
        moveTo(cx + DOT_R, cy)
        arcTo(DOT_R, DOT_R, 0f, true, true, cx - DOT_R, cy)
        arcTo(DOT_R, DOT_R, 0f, true, true, cx + DOT_R, cy)
        close()
    }

    private fun line(
        name: String,
        w: Float,
        block: PathBuilder.() -> Unit
    ): ImageVector = ImageVector.Builder(name, 28.dp, 28.dp, 28f, 28f).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = w,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) { block() }
    }.build()

    private fun filled(
        name: String,
        w: Float,
        block: PathBuilder.() -> Unit
    ): ImageVector = ImageVector.Builder(name, 28.dp, 28.dp, 28f, 28f).apply {
        path(
            fill = SolidColor(Color.Black),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = w,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) { block() }
    }.build()
}
