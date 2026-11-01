package com.puretone.player.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 智能歌单入口图标（浏览页横向卡片行）。
 *
 * 极简单色矢量图标：仅保留核心符号（沙漏 / 爱心 / 环形箭头），去掉了原先的「+」与「音符」，
 * 三个图标在 24 坐标系内均居中于 x12，视觉更干净统一：
 * - 环形箭头用填充式扇环绘制，不依赖 Stroke API；
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
        val scale = size.minDimension / 24f
        withTransform({ scale(scale, scale) }) {
            when (kind) {
                SmartPlaylistKind.RECENTLY_ADDED -> drawHourglass(tint)
                SmartPlaylistKind.MY_FAVORITES -> drawHeart(tint)
                SmartPlaylistKind.RECENTLY_PLAYED -> drawLoopArrows(tint)
            }
        }
    }
}

/** 最近添加：沙漏（腰部收窄至颈宽约 2.0，漏斗壁约 61°，各转角二次贝塞尔圆弧平滑） */
private fun DrawScope.drawHourglass(color: Color) {
    val pts = listOf(
        Offset(6f, 3f),     // 左上
        Offset(18f, 3f),    // 右上
        Offset(13f, 12f),   // 腰部右
        Offset(18f, 21f),   // 右下
        Offset(6f, 21f),    // 左下
        Offset(11f, 12f)    // 腰部左
    )
    drawPath(roundedPolygon(pts, 1.5f), color)
}

/**
 * 多边形转角平滑：在每个顶点沿相邻边各回退 r 取得切线点 t1/t2，再用「以原顶点为控制点的
 * 二次贝塞尔曲线」连接相邻切线点。对凸角与腰部凹角均适用，得到无锐利折角的光滑轮廓。
 * r 按相邻边长的一半自动 clamp，防止过圆或自交。
 */
private fun roundedPolygon(points: List<Offset>, radius: Float): Path {
    val n = points.size
    val t1 = Array(n) { Offset.Zero }
    val t2 = Array(n) { Offset.Zero }
    for (i in 0 until n) {
        val prev = points[(i - 1 + n) % n]
        val cur = points[i]
        val next = points[(i + 1) % n]
        val d1 = cur - prev
        val d2 = next - cur
        val len1 = d1.getDistance()
        val len2 = d2.getDistance()
        val r = (len1 / 2f).coerceAtMost(len2 / 2f).coerceAtMost(radius)
        val e1 = if (len1 > 0f) d1 / len1 else Offset.Zero
        val e2 = if (len2 > 0f) d2 / len2 else Offset.Zero
        t1[i] = cur - e1 * r
        t2[i] = cur + e2 * r
    }
    val path = Path()
    path.moveTo(t1[0].x, t1[0].y)
    for (i in 0 until n) {
        val nxt = (i + 1) % n
        path.quadraticTo(points[i].x, points[i].y, t2[i].x, t2[i].y)
        path.lineTo(t1[nxt].x, t1[nxt].y)
    }
    path.close()
    return path
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
