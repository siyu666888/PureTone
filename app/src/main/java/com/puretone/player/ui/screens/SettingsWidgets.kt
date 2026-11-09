package com.puretone.player.ui.screens

import androidx.compose.ui.res.stringResource
import com.puretone.player.R
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.puretone.player.data.settings.PureToneSettings

/** 设置页子项统一行高（分类头 63dp，子项 55dp，全页同一节奏） */
internal val SETTINGS_ROW_HEIGHT = 55.dp
/** 分段控件统一内高（主题 / 倍速 / 定时 视觉一致） */
private val SETTINGS_SEGMENT_HEIGHT = 44.dp

/**
 * 主题分段控件：整行一条分段轨道（无左侧标签，选项自解释）。
 *
 * 行高与其它子项一致（55dp），可见轨道高与倍速/定时分段一致（44dp）——
 * 与 [SettingsSegmentRow] 完全同构：外层 55dp 行（透明，撑行高），
 * 内层 44dp 轨道（surfaceVariant 背景）。旧实现把 55dp 的 Box 直接
 * 铺满背景当轨道，主题行看起来比其它分段行高出一截（用户反馈
 * 「主题切换行的高度同其他子项高度不一致」）。
 */
@Composable
internal fun ThemeSegmentedControl(
    selected: String,
    onSelect: (String) -> Unit
) {
    val options = listOf(
        PureToneSettings.THEME_SYSTEM to stringResource(R.string.pt_settings_045),
        PureToneSettings.THEME_LIGHT to stringResource(R.string.pt_settings_046),
        PureToneSettings.THEME_DARK to stringResource(R.string.pt_settings_047),
        PureToneSettings.THEME_BLACK to stringResource(R.string.pt_settings_048)
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(SETTINGS_ROW_HEIGHT),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(SETTINGS_SEGMENT_HEIGHT)
                .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium)
                .padding(3.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxHeight(),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                options.forEach { (value, label) ->
                    val isSelected = selected == value
                    val containerColor by animateColorAsState(
                        if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                        label = "segmentBg"
                    )
                    val contentColor by animateColorAsState(
                        if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        label = "segmentFg"
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(containerColor, CircleShape)
                            .clickable { onSelect(value) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelLarge,
                            color = contentColor,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

/**
 * 单行分段控件行：左侧标签 + 右侧分段选项（切换与显示方式同主题分段）。
 * 行高与其它子项一致（55dp）；选中态 primary / onPrimary，各主题下均清晰。
 */
@Composable
internal fun SettingsSegmentRow(
    label: String,
    options: List<String>,
    selectedIndex: Int,
    enabled: Boolean = true,
    onSelect: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(SETTINGS_ROW_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(SETTINGS_SEGMENT_HEIGHT)
                .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium)
                .padding(3.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxHeight(),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                options.forEachIndexed { index, option ->
                    val isSelected = index == selectedIndex
                    val containerColor by animateColorAsState(
                        if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                        label = "segRowBg"
                    )
                    val contentColor by animateColorAsState(
                        if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        label = "segRowFg"
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(containerColor, CircleShape)
                            .clickable(enabled = enabled) { onSelect(index) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            option,
                            style = MaterialTheme.typography.labelLarge,
                            color = contentColor,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

/**
 * 设置分类卡片：分类头行高统一 63dp、字号 titleLarge（比子项 bodyLarge 大一级），
 * 头下一条分隔线，子项由调用方提供（行高统一 55dp）。
 */
@Composable
internal fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            Box(
                modifier = Modifier.fillMaxWidth().height(63.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            content()
        }
    }
}

/** 开关子项行：行高统一 55dp */
@Composable
internal fun SwitchRow(label: String, checked: Boolean, enabled: Boolean = true, onChanged: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(SETTINGS_ROW_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, enabled = enabled, onCheckedChange = onChanged)
    }
}

/**
 * 滑杆子项行：左侧标签 + 右侧滑杆 + 末端数值。
 * 用于 ReplayGain 补偿（dB）这类连续值设置。行高统一 55dp。
 *
 * [valueRange] 用 Kotlin 区间表达；[steps] 为离散档位数（不含两端）。
 */
@Composable
internal fun SliderRow(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    enabled: Boolean = true,
    valueText: String,
    onValueChange: (Float) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(SETTINGS_ROW_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
            modifier = Modifier.weight(1f)
        )
        Text(
            valueText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp)
        )
    }
}

/** 睡眠定时剩余时间：mm:ss，满 1 小时显示 h:mm:ss */
internal fun formatRemaining(ms: Long): String {
    val totalSec = (ms / 1000).toInt()
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/** 缓存大小的人类可读格式 */
internal fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> "0 B"
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
}
