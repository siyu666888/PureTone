package com.puretone.player.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.puretone.player.data.settings.PureToneSettings
import com.puretone.player.data.settings.SettingsRepository
import com.puretone.player.effect.AudioEffectController
import com.puretone.player.effect.EffectStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 音效（均衡器）视图模型：读写 EQ 预设/频段/重低音/环绕。
 *
 * 单一状态源设计（P0-2）：本 VM 只写 DataStore，不直调 [AudioEffectController]。
 * 播放侧（PlaybackManager.bind）订阅 settings Flow，每次变化都整包
 * [AudioEffectController.applySettings]——冷启动恢复、设置页开关、本页调节
 * 三条路径走同一套映射，不会再出现「UI 开着 EQ、实际没有 EQ」。
 */
@HiltViewModel
class EqViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val effects: AudioEffectController
) : ViewModel() {

    val presets: Map<String, List<Int>> = AudioEffectController.PRESETS

    /**
     * 音效可用性 + 设备段数（P2-1）：设备不支持时 UI 给出明确提示，
     * 而不是让用户无声拖动滑杆。
     */
    val status: StateFlow<EffectStatus> = effects.status

    /**
     * 设置热缓存：DataStore 原始流是冷流（无 .value），
     * 故以 Eagerly 缓存为 StateFlow，供写路径读取当前快照。
     */
    private val settingsState: StateFlow<PureToneSettings> = settings.settings.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        PureToneSettings()
    )

    val state: StateFlow<EqScreenState> = settingsState
        .map { s ->
            EqScreenState(
                enabled = s.eqEnabled,
                preset = s.eqPreset,
                bandLevels = s.eqBandLevels,
                bassBoost = s.bassBoostStrength,
                virtualizer = s.virtualizerStrength
            )
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            EqScreenState()
        )

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch { runCatching { settings.setEqEnabled(enabled) } }
    }

    fun setBandLevel(band: Int, levelMb: Int) {
        // P2-1：手动调节即脱离预设（预设切 CUSTOM），频段与预设一次写入。
        // runCatching：DataStore 写失败不应让协程异常崩溃（P1-5）
        viewModelScope.launch {
            runCatching { settings.setBandLevelCustom(band, levelMb) }
        }
    }

    fun applyPreset(name: String) {
        val bands = AudioEffectController.PRESETS[name] ?: return
        viewModelScope.launch { runCatching { settings.applyEqPreset(name, bands.map { it * 100 }) } }
    }

    fun setBassBoost(strength: Int) {
        viewModelScope.launch { runCatching { settings.setBassBoostStrength(strength) } }
    }

    fun setVirtualizer(strength: Int) {
        viewModelScope.launch { runCatching { settings.setVirtualizerStrength(strength) } }
    }
}

/** EQ 界面状态快照 */
data class EqScreenState(
    val enabled: Boolean = false,
    val preset: String = PureToneSettings.EQ_CUSTOM,
    val bandLevels: List<Int> = List(PureToneSettings.EQ_BAND_COUNT) { 0 },
    val bassBoost: Int = 0,
    val virtualizer: Int = 0
)
