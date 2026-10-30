package com.puretone.player.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import android.os.SystemClock
import com.puretone.player.data.settings.PureToneSettings
import com.puretone.player.data.settings.SleepTimerMode
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
            SleepTimerSection(
                // 读取层已完成「旧数据迁移 + 过期归一化」，这里拿到的模式即真实模式
                mode = s.sleepTimerMode,
                minutes = s.sleepTimerMinutes,
                deadlineMs = s.sleepTimerDeadlineMs,
                onPickMinutes = vm::setSleepTimer,
                onEndOfTrack = vm::setSleepTimerEndOfTrack
            )
        }

        // 桌面小部件：系统入口（长按桌面 → 小组件 → 翻列表找 App）路径很深，
        // 且部分第三方桌面压根不列出新装 App，这里提供一键「固定到桌面」。
        val canPinWidget = remember { vm.canPinWidget() }
        SettingsGroup(title = "桌面") {
            Row(
                modifier = Modifier.fillMaxWidth().height(SETTINGS_ROW_HEIGHT),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("桌面播放控制小部件", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (canPinWidget) "添加后可显示当前歌曲并直接控制播放" else "当前桌面不支持添加小部件",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(enabled = canPinWidget, onClick = { vm.pinWidget() }) { Text("添加到桌面") }
            }
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
 * 睡眠定时区（细粒度定时）。
 *
 * 三种模式互斥地写在同一份持久化状态里（见 [SettingsRepository]）：
 * - 关：全字段归零；
 * - 播完本曲：当前这首自然播完就暂停，不限时长（夜间听单张专辑最常用）；
 * - 分钟：预设档位大 chip 一目了然，另有「自定义」支持 1~600 分钟。
 *
 * 状态显示行按模式分化：倒计时型走真实剩余时间（按到期时刻倒推），
 * 播完本曲没有剩余时间概念，只提示本届..行为与取消入口。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SleepTimerSection(
    mode: SleepTimerMode,
    minutes: Int,
    deadlineMs: Long,
    onPickMinutes: (Int) -> Unit,
    onEndOfTrack: () -> Unit
) {
    val presets = remember { listOf(5, 10, 15, 20, 30, 45, 60, 90) }
    var showCustomDialog by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().height(SETTINGS_ROW_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("定时", style = MaterialTheme.typography.bodyLarge)
        SleepChip("关", mode == SleepTimerMode.OFF) { onPickMinutes(0) }
        SleepChip("播完本曲", mode == SleepTimerMode.END_OF_TRACK, onEndOfTrack)
        SleepChip(
            "自定义",
            mode == SleepTimerMode.MINUTES && minutes !in presets
        ) { showCustomDialog = true }
    }
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        presets.forEach { m ->
            SleepChip("${m}m", mode == SleepTimerMode.MINUTES && minutes == m) { onPickMinutes(m) }
        }
    }

    when (mode) {
        SleepTimerMode.OFF -> Unit
        SleepTimerMode.END_OF_TRACK -> SleepStatusRow(
            text = "本曲播完后停止播放",
            onCancel = { onPickMinutes(0) }
        )
        SleepTimerMode.MINUTES -> SleepCountdownRow(
            deadlineMs = deadlineMs,
            onCancel = { onPickMinutes(0) }
        )
    }

    if (showCustomDialog) {
        CustomMinutesDialog(
            initialMinutes = minutes.takeIf { it > 0 },
            onDismiss = { showCustomDialog = false },
            onConfirm = { value ->
                showCustomDialog = false
                if (value > 0) onPickMinutes(value)
            }
        )
    }
}

/** 定时胶囊：选中态 primary/onPrimary，与分段控件视觉一致 */
@Composable
private fun SleepChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1) }
    )
}

/** 倒计时型定时：按「到期时刻」倒推剩余时间并每秒刷新；到期后 deadline 归零，本行自动消失 */
@Composable
private fun SleepCountdownRow(deadlineMs: Long, onCancel: () -> Unit) {
    if (deadlineMs <= 0L) return
    var remainingMs by remember(deadlineMs) {
        mutableStateOf((deadlineMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
    }
    LaunchedEffect(deadlineMs) {
        while (remainingMs > 0L) {
            delay(1_000)
            remainingMs = (deadlineMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        }
    }
    SleepStatusRow(text = "睡眠定时剩余 ${formatRemaining(remainingMs)}", onCancel = onCancel)
}

/** 定时状态行（提示文案 + 取消入口），行高与其它子项一致 */
@Composable
private fun SleepStatusRow(text: String, onCancel: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(SETTINGS_ROW_HEIGHT),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onCancel) { Text("取消定时") }
    }
}

/** 自定义分钟数输入：限定 1 ~ [PureToneSettings.SLEEP_MAX_MINUTES] 分钟，非法值不落库 */
@Composable
private fun CustomMinutesDialog(
    initialMinutes: Int?,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var text by remember { mutableStateOf(initialMinutes?.toString().orEmpty()) }
    val parsed = text.trim().toIntOrNull()
    val valid = parsed != null && parsed in PureToneSettings.SLEEP_MIN_MINUTES..PureToneSettings.SLEEP_MAX_MINUTES
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自定义定时") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "范围 ${PureToneSettings.SLEEP_MIN_MINUTES} ~ ${PureToneSettings.SLEEP_MAX_MINUTES} 分钟",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { if (it.all(Char::isDigit) && it.length <= 3) text = it },
                    singleLine = true,
                    suffix = { Text("分钟") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = text.isNotBlank() && !valid
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { parsed?.let(onConfirm) }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
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
    onSelect: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(SETTINGS_ROW_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
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
                            .clickable { onSelect(index) },
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
