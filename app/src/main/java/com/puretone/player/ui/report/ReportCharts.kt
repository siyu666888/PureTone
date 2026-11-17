package com.puretone.player.ui.report

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.puretone.player.domain.report.ReportInsights
import com.puretone.player.domain.report.ReportTrend

/**
 * 报告 V2 图表组件（声色叙事）：面积折线 / 日历热力 / 时段分布条。
 *
 * 所有色值从 [LocalReportPalette] 取，业务侧不写 hex；组件只吃数据，
 * 屏幕端通过 [ProvideReportPalette] 注入主题，与海报渲染共用同一套语义令牌。
 * 零播放/空表均安全返回，不抛异常。
 */

/** 7 日面积折线：峰点用洋红单独标记并加光晕；一周只画起伏，不画柱 */
@Composable
fun SpectrumAreaChart(
    buckets: List<ReportTrend.TrendBucket>,
    modifier: Modifier = Modifier
) {
    if (buckets.isEmpty()) return
    val p = LocalReportPalette.current
    val peak = buckets.withIndex()
        .filter { it.value.listenMs > 0 }
        .maxByOrNull { it.value.listenMs }?.index ?: -1
    Canvas(modifier = modifier) {
        val max = (buckets.maxOfOrNull { it.listenMs } ?: 0L).coerceAtLeast(1L).toFloat()
        val n = buckets.size
        val stepX = if (n > 1) size.width / (n - 1) else 0f
        val topPad = 6.dp.toPx()
        val usableH = size.height - topPad - 2.dp.toPx()
        val pts = buckets.mapIndexed { i, b ->
            Offset(i * stepX, size.height - (b.listenMs / max) * usableH)
        }
        val line = Color(p.spectrum2)
        val fill = Brush.verticalGradient(
            listOf(Color(p.spectrum2).copy(alpha = 0.42f), Color(p.spectrum2).copy(alpha = 0f)),
            startY = 0f,
            endY = size.height
        )
        drawArea(pts, fill)
        pts.forEachIndexed { i, pt ->
            if (i < pts.lastIndex) {
                drawLine(line, pt, pts[i + 1], strokeWidth = 2.dp.toPx())
            }
        }
        if (peak in pts.indices) {
            val c = pts[peak]
            drawCircle(Color(p.spectrum3), radius = 3.4.dp.toPx(), center = c) // 峰点
            drawCircle(Color(p.spectrum3).copy(alpha = 0.22f), radius = 6.5.dp.toPx(), center = c) // 光晕
        }
    }
}

private fun DrawScope.drawArea(pts: List<Offset>, brush: Brush) {
    if (pts.isEmpty()) return
    val path = Path().apply {
        moveTo(pts.first().x, size.height)
        pts.forEach { lineTo(it.x, it.y) }
        lineTo(pts.last().x, size.height)
        close()
    }
    drawPath(path, brush)
}

/** 日历热力：7 列整月铺满，空日留底不描边，色阶按 level 取光谱 1..4 */
@Composable
fun CalendarHeatmap(
    cells: List<ReportInsights.CalendarCell>,
    modifier: Modifier = Modifier
) {
    if (cells.isEmpty()) return
    val p = LocalReportPalette.current
    Canvas(modifier = modifier) {
        val gap = 3.dp.toPx()
        val cols = 7
        val cellW = (size.width - gap * (cols - 1)) / cols
        val rows = (cells.size + cols - 1) / cols
        val cellH = if (rows > 0) (size.height - gap * (rows - 1)) / rows else size.height
        cells.forEachIndexed { i, cell ->
            val col = i % cols
            val row = i / cols
            val x = col * (cellW + gap)
            val y = row * (cellH + gap)
            val color = if (cell.level == 0) {
                Color(p.bg)
            } else {
                Color(p.spectrum(cell.level))
            }
            drawRect(
                color = color,
                topLeft = Offset(x, y),
                size = androidx.compose.ui.geometry.Size(cellW, cellH)
            )
        }
    }
}

/** 时段分布条：一条 4 段比例条，宽度即占比，不用图例；段内显示文字标签 */
@Composable
fun TimeSegmentBar(
    segments: List<ReportInsights.TimeSegment>,
    modifier: Modifier = Modifier,
    showLabels: Boolean = true
) {
    if (segments.isEmpty()) return
    val p = LocalReportPalette.current
    Row(modifier = modifier.fillMaxWidth().height(if (showLabels) 26.dp else 14.dp)) {
        segments.forEach { seg ->
            val w = if (seg.share > 0f) seg.share else 0f
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.weight(w.coerceAtLeast(0.001f))
            ) {
                Canvas(modifier = Modifier.matchParentSize()) {
                    drawRect(Color(p.spectrum(seg.level)))
                }
                if (showLabels && w >= 0.12f) {
                    Text(
                        seg.label,
                        modifier = Modifier.align(Alignment.Center),
                        color = Color.White,
                        fontSize = 9.sp,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/** 时段条 x 轴标签（四段等宽居中） */
@Composable
fun TimeSegmentLabels(segments: List<ReportInsights.TimeSegment>) {
    if (segments.isEmpty()) return
    Row(modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) {
        segments.forEach { seg ->
            Text(
                seg.label,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
                maxLines = 1
            )
        }
    }
}
