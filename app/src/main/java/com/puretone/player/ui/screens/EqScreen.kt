package com.puretone.player.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.puretone.player.data.settings.PureToneSettings
import com.puretone.player.effect.AudioEffectController
import com.puretone.player.ui.EqViewModel

/** 均衡器页 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqScreen(
    navController: NavHostController,
    vm: EqViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("均衡器") },
            navigationIcon = {
                IconButton(onClick = navController::popBackStack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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
            // 预设
            Text("预设", style = MaterialTheme.typography.titleMedium)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                vm.presets.keys.forEach { preset ->
                    ChipEq(selected = state.preset.equals(preset, ignoreCase = true), label = presetLabel(preset)) {
                        vm.applyPreset(preset)
                    }
                }
            }

            // 频段
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AudioEffectController.CENTER_FREQS.forEachIndexed { index, freq ->
                        val level = state.bandLevels.getOrElse(index) { 0 }
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(freqLabel(freq), style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${level / 100} dB", style = MaterialTheme.typography.labelMedium)
                            }
                            Slider(
                                value = level.toFloat(),
                                onValueChange = { vm.setBandLevel(index, it.toInt()) },
                                valueRange = AudioEffectController.MIN_LEVEL_MB.toFloat()..AudioEffectController.MAX_LEVEL_MB.toFloat(),
                                steps = ((AudioEffectController.MAX_LEVEL_MB - AudioEffectController.MIN_LEVEL_MB) / 100) - 1,
                                enabled = state.enabled
                            )
                        }
                    }
                }
            }

            // 重低音 / 环绕
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("重低音 / 环绕声", style = MaterialTheme.typography.titleMedium)
                    LabeledSlider(
                        label = "重低音",
                        value = state.bassBoost,
                        onValueChange = vm::setBassBoost,
                        enabled = state.enabled
                    )
                    LabeledSlider(
                        label = "环绕声",
                        value = state.virtualizer,
                        onValueChange = vm::setVirtualizer,
                        enabled = state.enabled
                    )
                }
            }

            Text(
                "注：音效效果需设备支持，部分机型可能无变化。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun LabeledSlider(label: String, value: Int, enabled: Boolean, onValueChange: (Int) -> Unit) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text("${value / 10}%", style = MaterialTheme.typography.labelMedium)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
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
private fun presetLabel(key: String): String = when (key.uppercase()) {
    "FLAT" -> "平坦"
    "POP" -> "流行"
    "ROCK" -> "摇滚"
    "JAZZ" -> "爵士"
    "CLASSICAL" -> "古典"
    "VOCAL" -> "人声"
    "BASS" -> "低音"
    else -> key
}

private fun Modifier.clickableChip(onClick: () -> Unit): Modifier =
    this.clickable(onClick = onClick)
