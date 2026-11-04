package com.puretone.player.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 简易柱状图（Canvas，GPU 加速）。
 *
 * [values] 为各柱数值；[highlightIndex] 指定的柱用实色，其余半透明。
 * 阶段2 被「听歌统计」与「听歌报告」两处复用，故抽到公共组件（原在 StatsScreen 内为 private）。
 */
@Composable
fun BarChart(
    values: List<Long>,
    barColor: Color,
    modifier: Modifier = Modifier,
    highlightIndex: Int = -1
) {
    if (values.isEmpty()) return
    val max = (values.maxOrNull() ?: 0L).coerceAtLeast(1L).toFloat()
    Canvas(modifier = modifier) {
        val n = values.size
        val gap = 3.dp.toPx()
        val slot = ((size.width - gap * (n - 1)) / n).coerceAtLeast(1f)
        values.forEachIndexed { i, v ->
            val h = (v.toFloat() / max) * (size.height * 0.92f)
            val left = i * (slot + gap)
            drawRect(
                color = if (i == highlightIndex) barColor else barColor.copy(alpha = 0.45f),
                topLeft = Offset(left, size.height - h),
                size = Size(slot, h.coerceAtLeast(if (v > 0) 2.dp.toPx() else 0f))
            )
        }
    }
}
