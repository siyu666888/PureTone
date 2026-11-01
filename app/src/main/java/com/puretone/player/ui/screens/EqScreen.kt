package com.puretone.player.ui.screens
import androidx.compose.ui.res.stringResource
import com.puretone.player.R

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.puretone.player.effect.AudioEffectController
import com.puretone.player.effect.EffectAvailability
import com.puretone.player.effect.EffectStatus
import com.puretone.player.ui.EqViewModel

/** 均衡器页 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqScreen(
    navController: NavHostController,
    vm: EqViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val customPresets by vm.customPresets.collectAsStateWithLifecycle()
    var showSavePreset by remember { mutableStateOf(false) }
    var presetName by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.pt_eq_001)) },
            // P1-7：状态栏 inset 由外层 Scaffold 统一负责，内层不再重复叠加
            windowInsets = WindowInsets(0),
            navigationIcon = {
                IconButton(onClick = navController::popBackStack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.pt_eq_002))
                }
            },
            actions = {
                Switch(checked = state.enabled, onCheckedChange = vm::setEnabled)
            }
        )

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 预设（P2-1：窄屏 7 个胶囊会溢出裁切，改横向滚动）
            Text(stringResource(R.string.pt_eq_003), style = MaterialTheme.typography.titleMedium)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            ) {
                vm.presets.keys.forEach { preset ->
                    ChipEq(selected = state.preset.equals(preset, ignoreCase = true), label = presetLabel(preset)) {
                        vm.applyPreset(preset)
                    }
                }
            }

            // 我的预设（② 均衡器自定义：保存/应用/删除用户曲线）
            Text(stringResource(R.string.pt_eq_004), style = MaterialTheme.typography.titleMedium)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            ) {
                // 入口：把当前 10 段曲线保存为命名预设
                Card(modifier = Modifier.clickable { showSavePreset = true }) {
                    Text(
                        stringResource(R.string.pt_eq_005),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
                // 已保存的自定义预设：点击应用，右侧图标删除
                customPresets.forEach { (name, _) ->
                    Card(modifier = Modifier.clickable { vm.applyCustomPreset(name) }) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp)
                        ) {
                            Text(
                                name,
                                modifier = Modifier.padding(vertical = 6.dp),
                                style = MaterialTheme.typography.labelLarge
                            )
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.pt_eq_006, name),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .size(20.dp)
                                    .clickable { vm.deleteCustomPreset(name) }
                            )
                        }
                    }
                }
            }

            // 频段
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AudioEffectController.CENTER_FREQS.forEachIndexed { index, freq ->
                        val dbLevel = state.bandLevels.getOrElse(index) { 0 }
                        // P1-5：拖动过程只改本地态，松手（onValueChangeFinished）才落库。
                        // 旧实现每帧写 DataStore：几十上百次磁盘写，且多个协程读到同一份
                        // 旧列表后写回会互相覆盖，最终只保留最后一个频段的改动。
                        var dragging by remember(index) { mutableStateOf(false) }
                        var localLevel by remember(index) { mutableFloatStateOf(dbLevel.toFloat()) }
                        val shown = if (dragging) localLevel else dbLevel.toFloat()
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(freqLabel(freq), style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${shown.toInt() / 100} dB", style = MaterialTheme.typography.labelMedium)
                            }
                            Slider(
                                value = shown,
                                onValueChange = {
                                    dragging = true
                                    localLevel = it
                                },
                                onValueChangeFinished = {
                                    dragging = false
                                    vm.setBandLevel(index, localLevel.toInt())
                                },
                                valueRange = AudioEffectController.MIN_LEVEL_MB.toFloat()..
                                    AudioEffectController.MAX_LEVEL_MB.toFloat(),
                                steps = ((AudioEffectController.MAX_LEVEL_MB - AudioEffectController.MIN_LEVEL_MB) / 100) - 1,
                                enabled = state.enabled
                            )
                        }
                    }
                }
            }

            // 重低音 / 环绕（同样本地态 + 松手落库）
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.pt_eq_007), style = MaterialTheme.typography.titleMedium)
                    LabeledSlider(
                        label = stringResource(R.string.pt_eq_008),
                        value = state.bassBoost,
                        enabled = state.enabled,
                        onValueChange = {},
                        onValueChangeFinished = vm::setBassBoost
                    )
                    LabeledSlider(
                        label = stringResource(R.string.pt_eq_009),
                        value = state.virtualizer,
                        enabled = state.enabled,
                        onValueChange = {},
                        onValueChangeFinished = vm::setVirtualizer
                    )
                }
            }

            Text(
                // P2-1：设备可用性提示——旧实现只有一句笼统的「部分机型可能无变化」，
                // 用户拖动滑杆完全无声时得不到任何有效反馈
                eqHintText(status),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    // 保存当前曲线为自定义预设的弹窗（② 均衡器自定义）
    if (showSavePreset) {
        AlertDialog(
            onDismissRequest = { showSavePreset = false },
            title = { Text(stringResource(R.string.pt_eq_010)) },
            text = {
                OutlinedTextField(
                    value = presetName,
                    onValueChange = { presetName = it },
                    label = { Text(stringResource(R.string.pt_eq_011)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = presetName.trim()
                    if (name.isNotEmpty()) {
                        vm.saveCustomPreset(name)
                        presetName = ""
                        showSavePreset = false
                    }
                }) { Text(stringResource(R.string.pt_eq_012)) }
            },
            dismissButton = {
                TextButton(onClick = { showSavePreset = false }) { Text(stringResource(R.string.pt_eq_013)) }
            }
        )
    }
}

/** 均衡器页底部提示：按设备音效可用性给出可操作的说明 */
@Composable
private fun eqHintText(status: EffectStatus): String = when (status.availability) {
    EffectAvailability.UNSUPPORTED ->
        stringResource(R.string.pt_eq_014)
    EffectAvailability.UNKNOWN ->
        stringResource(R.string.pt_eq_015)
    EffectAvailability.SUPPORTED ->
        if (status.deviceBandCount in 1 until AudioEffectController.CENTER_FREQS.size) {
            stringResource(R.string.pt_eq_016, status.deviceBandCount, AudioEffectController.CENTER_FREQS.size)
        } else {
            stringResource(R.string.pt_eq_017)
        }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Int,
    enabled: Boolean,
    onValueChange: (Int) -> Unit,
    onValueChangeFinished: (Int) -> Unit
) {
    // P1-5：拖动只动本地态，松手才提交，避免每帧写库 + 并发覆盖丢更新
    var dragging by remember { mutableStateOf(false) }
    var local by remember { mutableFloatStateOf(value.toFloat()) }
    val shown = if (dragging) local else value.toFloat()
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text("${shown.toInt() / 10}%", style = MaterialTheme.typography.labelMedium)
        }
        Slider(
            value = shown,
            onValueChange = {
                dragging = true
                local = it
                onValueChange(it.toInt())
            },
            onValueChangeFinished = {
                dragging = false
                onValueChangeFinished(local.toInt())
            },
            valueRange = AudioEffectController.MIN_STRENGTH.toFloat()..AudioEffectController.MAX_STRENGTH.toFloat(),
            steps = 99,
            enabled = enabled
        )
    }
}

@Composable
private fun ChipEq(selected: Boolean, label: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.clickableChip(onClick),
        colors = if (selected) androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ) else androidx.compose.material3.CardDefaults.cardColors()
    ) {
        Text(label, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}

private fun freqLabel(freq: Int): String = when {
    freq >= 1000 -> "${freq / 1000}k"
    else -> "$freq"
}

/** 预设名称（内部键 → 中文显示） */
@Composable
private fun presetLabel(key: String): String = when (key.uppercase()) {
    "FLAT" -> stringResource(R.string.pt_eq_preset_flat)
    "POP" -> stringResource(R.string.pt_eq_preset_pop)
    "ROCK" -> stringResource(R.string.pt_eq_preset_rock)
    "JAZZ" -> stringResource(R.string.pt_eq_preset_jazz)
    "CLASSICAL" -> stringResource(R.string.pt_eq_preset_classical)
    "VOCAL" -> stringResource(R.string.pt_eq_preset_vocal)
    "BASS" -> stringResource(R.string.pt_eq_preset_bass)
    else -> key
}

private fun Modifier.clickableChip(onClick: () -> Unit): Modifier =
    this.clickable(onClick = onClick)
