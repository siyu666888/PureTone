package com.puretone.player.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.puretone.player.data.settings.PureToneSettings
import com.puretone.player.data.settings.SettingsRepository
import com.puretone.player.effect.AudioEffectController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 音效（均衡器）视图模型：读写 EQ 预设/频段/重低音/环绕，并即时生效于 [AudioEffectController]。
 */
@HiltViewModel
class EqViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val effect: AudioEffectController
) : ViewModel() {

    val presets: Map<String, List<Int>> = AudioEffectController.PRESETS

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
        effect.setEnabled(enabled)
        viewModelScope.launch { settings.setEqEnabled(enabled) }
        if (enabled) effect.setBandLevels(settingsState.value.eqBandLevels)
    }

    fun setBandLevel(band: Int, levelMb: Int) {
        effect.setBandLevel(band, levelMb)
        viewModelScope.launch {
            val next = settingsState.value.eqBandLevels.toMutableList().apply { set(band, levelMb) }
            settings.setEqBandLevels(next)
        }
    }

    fun applyPreset(name: String) {
        val bands = AudioEffectController.PRESETS[name] ?: return
        effect.applyPreset(name)
        viewModelScope.launch { settings.applyEqPreset(name, bands.map { it * 100 }) }
    }

    fun setBassBoost(strength: Int) {
        effect.setBassBoostStrength(strength)
        viewModelScope.launch { settings.setBassBoostStrength(strength) }
    }

    fun setVirtualizer(strength: Int) {
        effect.setVirtualizerStrength(strength)
        viewModelScope.launch { settings.setVirtualizerStrength(strength) }
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
