package com.puretone.player.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.puretone.player.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 智能歌单入口图标（浏览页横向卡片行）。
 *
 * 极简单色矢量图标：仅保留核心符号（时钟 / 爱心 / 环形箭头），去掉了原先的「+」与「音符」，
 * 三个图标在 24 坐标系内均居中于 x12，视觉更干净统一：
 * - 环形箭头用填充式扇环绘制，不依赖 Stroke API；
 * - 时钟为描边式（钟面 + 指针），按 2026-11-02 确认的 v5 预览规格实现；
 * - 配色复用品牌体系：最近添加=primary、我的最爱=FavoriteRed（与红心开关同源）、
 *   最近播放=tertiary，深浅三套主题自动适配。
 */
enum class SmartPlaylistKind { RECENTLY_ADDED, MY_FAVORITES, RECENTLY_PLAYED }

@Composable
fun SmartPlaylistGlyph(kind: SmartPlaylistKind, modifier: Modifier = Modifier) {
    val tint = when (kind) {
        SmartPlaylistKind.RECENTLY_ADDED -> MaterialTheme.colorScheme.primary
        SmartPlaylistKind.MY_FAVORITES -> FavoriteRed
        SmartPlaylistKind.RECENTLY_PLAYED -> MaterialTheme.colorScheme.tertiary
    }
    Canvas(modifier.defaultMinSize(28.dp, 28.dp)) {
        // 图标路径全部按 24×24 的设计坐标系书写（与 Material 图标规范一致），
        // 这里把 0..24 的坐标系线性映射到画布尺寸。
        //
        // 必须显式指定 pivot = Offset.Zero：DrawTransform.scale 默认以**画布中心**
        // 为缩放轴心，而本图标的坐标原点在左上角。以中心为轴心缩放等于
        //   p' = center + (p - center) * s
        // 设计坐标 (12,12)（图标几何中心）会被算成 center - 12s，在 44dp 画布上
        // ≈ (-231, -231)px —— 整个图标被推到画布左上方之外，屏幕上完全看不到。
        // 这正是「智能歌单图标不显示」的根因（三个图标同源，故同时失效）。
        val scale = size.minDimension / 24f
        withTransform({ scale(scale, scale, pivot = Offset.Zero) }) {
            when (kind) {
                SmartPlaylistKind.RECENTLY_ADDED -> drawClock(tint)
                SmartPlaylistKind.MY_FAVORITES -> drawHeart(tint)
                SmartPlaylistKind.RECENTLY_PLAYED -> drawLoopArrows(tint)
            }
        }
    }
}

/**
 * 最近添加：时钟（描边钟面 + 时针分针）。
 *
 * 与另外两个图标（红心 / 环箭头为**填充**式）不同，本图标按 2026-11-02 用户确认的
 * v5 预览规格实现为**描边**式：钟面圆环 r=8.5、线宽 1.8、圆头端点，
 * 指针路径 M12 7.5 V12 L15 14（时针指向 12 点、分针指向 4 点方向）。
 *
 * 为什么用描边而非填充：填充式时钟在 24dp 小尺寸下指针与表盘糊成一团、辨识度差；
 * 描边式轮廓清晰、缩小后仍可读，与 Material 时钟图标（schedule）的视觉语言一致。
 */
private fun DrawScope.drawClock(color: Color) {
    val stroke = 1.8f
    // 钟面：Material 时钟图标同款半径
    drawCircle(
        color = color,
        radius = 8.5f,
        center = Offset(12f, 12f),
        style = Stroke(width = stroke, cap = StrokeCap.Round)
    )
    // 指针：中心点 (12,12) → 时针上至 (12,7.5) → 分针右至 (15,14)
    drawLine(
        color = color,
        start = Offset(12f, 7.5f),
        end = Offset(12f, 12f),
        strokeWidth = stroke,
        cap = StrokeCap.Round
    )
    drawLine(
        color = color,
        start = Offset(12f, 12f),
        end = Offset(15f, 14f),
        strokeWidth = stroke,
        cap = StrokeCap.Round
    )
}

/** 我的最爱：爱心（Material 标准心形路径） */
private fun DrawScope.drawHeart(color: Color) {
    val heart = Path().apply {
        moveTo(12f, 21.35f)
        lineTo(10.55f, 20.03f)
        cubicTo(5.4f, 15.36f, 2f, 12.28f, 2f, 8.5f)
        cubicTo(2f, 5.42f, 4.42f, 3f, 7.5f, 3f)
        cubicTo(9.24f, 3f, 10.91f, 3.81f, 12f, 5.09f)
        cubicTo(13.09f, 3.81f, 14.76f, 3f, 16.5f, 3f)
        cubicTo(19.58f, 3f, 22f, 5.42f, 22f, 8.5f)
        cubicTo(22f, 12.28f, 18.6f, 15.36f, 13.45f, 20.04f)
        lineTo(12f, 21.35f)
        close()
    }
    drawPath(heart, color)
}

/** 最近播放：三段顺时针环形箭头（历史回放） */
private fun DrawScope.drawLoopArrows(color: Color) {
    val c = Offset(12f, 12f)
    drawCircularArrow(c, 8.5f, 250f, 70f, color)
    drawCircularArrow(c, 8.5f, 10f, 70f, color)
    drawCircularArrow(c, 8.5f, 130f, 70f, color)
}

/** 填充式扇环 + 实心箭头三角，避免依赖 Stroke API */
private fun DrawScope.drawCircularArrow(
    center: Offset, radius: Float, startDeg: Float, sweepDeg: Float, color: Color
) {
    val outerR = radius + 0.9f
    val innerR = radius - 0.9f
    val outer = Rect(center.x - outerR, center.y - outerR, center.x + outerR, center.y + outerR)
    val inner = Rect(center.x - innerR, center.y - innerR, center.x + innerR, center.y + innerR)
    val sR = startDeg * PI.toFloat() / 180f
    val eR = (startDeg + sweepDeg) * PI.toFloat() / 180f
    val oS = Offset(center.x + outerR * cos(sR), center.y + outerR * sin(sR))
    val oE = Offset(center.x + outerR * cos(eR), center.y + outerR * sin(eR))
    val iE = Offset(center.x + innerR * cos(eR), center.y + innerR * sin(eR))
    val seg = Path().apply {
        moveTo(oS.x, oS.y)
        arcTo(outer, startDeg, sweepDeg, false)
        lineTo(iE.x, iE.y)
        arcTo(inner, startDeg + sweepDeg, -sweepDeg, false)
        close()
    }
    drawPath(seg, color)
    val tx = -sin(eR); val ty = cos(eR)
    val ah = 3.0f
    val tipX = oE.x + tx * ah; val tipY = oE.y + ty * ah
    val px = -ty; val py = tx
    val lx = oE.x - px * ah * 0.7f; val ly = oE.y - py * ah * 0.7f
    val rx = oE.x + px * ah * 0.7f; val ry = oE.y + py * ah * 0.7f
    val tri = Path().apply { moveTo(tipX, tipY); lineTo(lx, ly); lineTo(rx, ry); close() }
    drawPath(tri, color)
}

/**
 * 「智能歌单」整体功能图标（区别于三个子类型 最近添加 / 我的最爱 / 最近播放）。
 *
 * 符号语义：左侧竖播放条 + 三条曲目横线 = 播放列表（歌单）；右上 + 右下一大一小四角星火花
 * = 智能 / 自动生成。双含义清晰，单色面性，符合 Material 24 网格规范。
 *
 * 资源为矢量 XML（res/drawable/ic_smart_playlist.xml，24×24，单色占位黑可被 tint），
 * 自适应前景层见 ic_smart_playlist_foreground.xml（108×108，内容落在 66dp 安全区内）。
 * tint 复用品牌 primary，深浅三套主题自动适配，与既有 SmartPlaylistGlyph 风格一致。
 */
@Composable
fun SmartPlaylistBrandIcon(modifier: Modifier = Modifier) {
    Icon(
        painter = painterResource(R.drawable.ic_smart_playlist),
        contentDescription = stringResource(R.string.pt_smart_playlist_000),
        tint = MaterialTheme.colorScheme.primary,
        modifier = modifier
    )
}
