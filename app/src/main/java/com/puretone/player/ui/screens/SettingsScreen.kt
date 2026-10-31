package com.puretone.player.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import android.os.SystemClock
import com.puretone.player.data.settings.PureToneSettings
import com.puretone.player.ui.Routes
import com.puretone.player.ui.SettingsViewModel
import kotlinx.coroutines.delay

/**
 * 设置页。
 *
 * 排版规范（2026-10-22 用户反馈重构）：
 * - 分类标题字号/行高比子项大一级：titleLarge(22sp) / 63dp 行高 vs 子项 bodyLarge(16sp) / 55dp 行高；
 * - 所有分类头行高一致（63dp）、所有子项行高一致（55dp），全页同一节奏；
 * - 内外边距适度放大，内容铺满整屏为宜（超出可滚动）。
 */
/** 设置页子项统一行高（分类头 63dp，子项 55dp，全页同一节奏） */
private val SETTINGS_ROW_HEIGHT = 55.dp
/** 分段控件统一内高（主题 / 倍速 / 定时 视觉一致） */
private val SETTINGS_SEGMENT_HEIGHT = 44.dp

@Composable
fun SettingsScreen(
    navController: NavHostController,
    vm: SettingsViewModel = hiltViewModel()
) {
    val s by vm.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 外观
        SettingsGroup(title = "外观") {
            ThemeSegmentedControl(selected = s.themeMode, onSelect = vm::setThemeMode)
            // 决策 D2：系统动态取色默认关
            SwitchRow(label = "启用系统动态取色（实验）", checked = s.dynamicColor) { vm.setDynamicColor(it) }
            // P2-5：AMOLED 纯黑开关（优先级高于动态取色，见 Theme.kt）
            SwitchRow(label = "AMOLED 纯黑（省电）", checked = s.amoledBlack) { vm.setAmoledBlack(it) }
        }

        // 播放
        SettingsGroup(title = "播放") {
            SwitchRow(label = "无缝播放（gapless）", checked = s.gapless) { vm.setGapless(it) }
            SwitchRow(label = "启动时续播上次歌曲", checked = s.resumeOnStart) { vm.setResumeOnStart(it) }
            // 交叉淡化（Crossfade）：0 = 关；1~12 秒，步长 500ms。
            // 仅常规格式生效；APE 等需转码格式在边界退化为无缝/曲间停顿。
            SliderRow(
                label = "交叉淡化",
                value = s.crossfadeMs.toFloat(),
                valueRange = 0f..PureToneSettings.CROSSFADE_MAX_MS.toFloat(),
                steps = (PureToneSettings.CROSSFADE_MAX_MS / 500) - 1,
                valueText = if (s.crossfadeMs <= 0) "关" else "%.1fs".format(s.crossfadeMs / 1000f),
                onValueChange = { vm.setCrossfadeMs(it.toInt()) }
            )

            // 倍速 / 睡眠定时：单行分段控件，切换与显示方式同主题分段
            val speedOptions = listOf(0.5f, 1f, 1.25f, 1.5f, 2f)
            SettingsSegmentRow(
                label = "倍速",
                options = speedOptions.map { if (it % 1f == 0f) "${it.toInt()}x" else "${it}x" },
                // P3-24：旧实现 `.coerceAtLeast(0)` 会把「不在选项里的值」（例如旧版本
                // 存下的 0.75x）静默显示成第一项 0.5x，用户看到的状态与真实值不符。
                // 这里传 -1 = 无选中（SettingsSegmentRow 按 index == selectedIndex 判定，安全）。
                selectedIndex = speedOptions.indexOf(s.playbackSpeed),
                onSelect = { vm.setPlaybackSpeed(speedOptions[it]) }
            )
            // 睡眠定时：单行分段控件（关 / 15m / 30m / 45m / 60m），切换与显示方式同主题分段
            val sleepOptions = listOf(0, 15, 30, 45, 60)
            SettingsSegmentRow(
                label = "定时",
                options = sleepOptions.map { if (it == 0) "关" else "${it}m" },
                selectedIndex = sleepOptions.indexOf(s.sleepTimerMinutes),
                onSelect = { vm.setSleepTimer(sleepOptions[it]) }
            )
            // 定时进行中时显示真实剩余时间（按「到期时刻」倒推，每秒刷新），并提供「取消定时」。
            // 到期后 deadline 清零，本行自动消失。
            if (s.sleepTimerDeadlineMs > 0L) {
                var remainingMs by remember(s.sleepTimerDeadlineMs) {
                    mutableStateOf(
                        (s.sleepTimerDeadlineMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                    )
                }
                LaunchedEffect(s.sleepTimerDeadlineMs) {
                    while (remainingMs > 0L) {
                        delay(1_000)
                        remainingMs =
                            (s.sleepTimerDeadlineMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().height(SETTINGS_ROW_HEIGHT),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "睡眠定时剩余 ${formatRemaining(remainingMs)}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { vm.setSleepTimer(0) }) { Text("取消定时") }
                }
            }
        }

        // 桌面小部件：系统入口（长按桌面 → 小组件 → 翻列表找 App）路径很深，
        // 且部分第三方桌面压根不列出新装 App，这里提供一键「固定到桌面」。
        //
        // 交互改为开关 + 前置确认弹窗：
        // - 开关的 checked 直接来自 AppWidgetManager 的真实记录（[SettingsViewModel.widgetAdded]），
        //   不是偏好值——用户可能在系统确认里取消，也可能添加后又在桌面上长按删掉；
        // - 勾选前先弹对话框征得同意，确认后才发起请求；取消则开关自行弹回（状态未变）；
        // - 已添加时把它拨到「关」不能真的卸载（Android 不允许 App 删除桌面组件），
        //   改为提示用户到桌面长按移除，避免给一个「点了没反应」的开关。
        val canPinWidget = remember { vm.canPinWidget() }
        val widgetAdded by vm.widgetAdded.collectAsStateWithLifecycle()
        var showAddDialog by remember { mutableStateOf(false) }
        var showUnpinHint by remember { mutableStateOf(false) }
        // 从桌面（含确认添加）返回设置页时按真实记录重算开关状态
        LifecycleResumeEffect(Unit) {
            vm.refreshWidgetAdded()
            onPauseOrDispose {}
        }
        // 发起添加后补一次刷新：系统那层确认插页不在 App 内，无法拿到完成回调
        var pinRequested by remember { mutableStateOf(0) }
        LaunchedEffect(pinRequested) {
            if (pinRequested > 0) {
                delay(1_200)
                vm.refreshWidgetAdded()
            }
        }
        SettingsGroup(title = "桌面") {
            SwitchRow(
                label = "桌面播放控制小部件",
                checked = widgetAdded,
                enabled = canPinWidget || widgetAdded,
                onChanged = { checked ->
                    when {
                        checked && !widgetAdded -> showAddDialog = true
                        !checked -> showUnpinHint = true
                    }
                }
            )
        }

        if (showAddDialog) {
            AlertDialog(
                onDismissRequest = { showAddDialog = false },
                title = { Text("添加桌面小部件") },
                text = {
                    Text(
                        "将在桌面添加 PureTone 播放控制小部件，" +
                            "可直接查看当前歌曲并控制播放 / 切歌。是否继续？"
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        showAddDialog = false
                        if (vm.pinWidget()) pinRequested++
                    }) { Text("确认添加") }
                },
                dismissButton = {
                    TextButton(onClick = { showAddDialog = false }) { Text("取消") }
                }
            )
        }

        if (showUnpinHint) {
            AlertDialog(
                onDismissRequest = { showUnpinHint = false },
                title = { Text("已在桌面上添加") },
                text = { Text("如需移除，请在桌面长按该小部件，拖到「移除」区域即可。") },
                confirmButton = {
                    TextButton(onClick = { showUnpinHint = false }) { Text("知道了") }
                }
            )
        }

        // 存储
        SettingsGroup(title = "存储") {
            // APE 转码缓存：按字节上限 LRU（见 ApeTranscoder），此处可查大小并一键清理
            val cacheSize by vm.apeCacheSize.collectAsStateWithLifecycle()
            LaunchedEffect(Unit) { vm.refreshApeCacheSize() }
            Row(
                modifier = Modifier.fillMaxWidth().height(SETTINGS_ROW_HEIGHT),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "APE 转码缓存" + if (cacheSize >= 0L) "（${formatBytes(cacheSize)}）" else "",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = { vm.clearApeCache() },
                    enabled = cacheSize != 0L
                ) { Text("清理") }
            }
        }

        // 音效
        SettingsGroup(title = "音效") {
            // 均衡器：行可点击进入均衡器页调节；右侧新增开关，直接启停音效
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(SETTINGS_ROW_HEIGHT)
                    .clickable { navController.navigate(Routes.EQ) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("均衡器", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(checked = s.eqEnabled, onCheckedChange = { vm.setEqEnabled(it) })
            }
        }

        // ReplayGain（P1）：响度归一化，让不同音量的歌曲听起来响度一致
        SettingsGroup(title = "响度（ReplayGain）") {
            SwitchRow(
                label = "启用 ReplayGain",
                checked = s.replayGainEnabled,
                onChanged = { vm.setReplayGainEnabled(it) }
            )
            // 模式：按曲目（每首独立归一）/ 按专辑（同专辑内响度一致，曲目间过渡更平）
            val rgModes = listOf(PureToneSettings.RG_TRACK, PureToneSettings.RG_ALBUM)
            val rgModeLabels = listOf("曲目", "专辑")
            SettingsSegmentRow(
                label = "模式",
                options = rgModeLabels,
                selectedIndex = rgModes.indexOf(s.replayGainMode).coerceAtLeast(0),
                enabled = s.replayGainEnabled,
                onSelect = { vm.setReplayGainMode(rgModes[it]) }
            )
            // preamp：在 RG 增益之上叠加的手调补偿（dB，范围 -12 ~ +12）
            SliderRow(
                label = "补偿 (dB)",
                value = s.replayGainPreampDb,
                valueRange = PureToneSettings.RG_PREAMP_MIN_DB..PureToneSettings.RG_PREAMP_MAX_DB,
                steps = 48, // -12..12 共 24 个整数 dB，每 0.5 一档 → 48 步
                enabled = s.replayGainEnabled,
                valueText = "%.1f dB".format(s.replayGainPreampDb),
                onValueChange = { vm.setReplayGainPreamp(it) }
            )
            Text(
                "依据音频文件内 ReplayGain 标签自动调整响度，文件无标签时本项不生效。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }

        // 品牌 + 版本号（居中，置于内容最底部）
        val context = LocalContext.current
        val versionText = remember {
            runCatching {
                val pi = context.packageManager.getPackageInfo(context.packageName, 0)
                "v${pi.versionName}"
            }.getOrDefault("")
        }
        Text(
            "PureTone · 纯净之音",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        )
        Text(
            versionText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

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
private fun ThemeSegmentedControl(
    selected: String,
    onSelect: (String) -> Unit
) {
    val options = listOf(
        PureToneSettings.THEME_SYSTEM to "系统",
        PureToneSettings.THEME_LIGHT to "浅色",
        PureToneSettings.THEME_DARK to "深色",
        PureToneSettings.THEME_BLACK to "纯黑"
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
private fun SettingsSegmentRow(
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
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
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
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean = true, onChanged: (Boolean) -> Unit) {
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
private fun SliderRow(
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
private fun formatRemaining(ms: Long): String {
    val totalSec = (ms / 1000).toInt()
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/** 缓存大小的人类可读格式 */
private fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> "0 B"
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
}
