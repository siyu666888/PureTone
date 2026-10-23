package com.puretone.player.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.puretone.player.data.settings.PureToneSettings
import com.puretone.player.data.settings.SettingsRepository
import com.puretone.player.playback.ApeTranscoder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 设置视图模型：主题/播放/音效偏好读写。
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val apeTranscoder: ApeTranscoder
) : ViewModel() {

    val state: StateFlow<PureToneSettings> = settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PureToneSettings())

    fun setThemeMode(mode: String) = viewModelScope.launch { settings.setThemeMode(mode) }
    fun setAmoledBlack(enabled: Boolean) = viewModelScope.launch { settings.setAmoledBlack(enabled) }
    fun setDynamicColor(enabled: Boolean) = viewModelScope.launch { settings.setDynamicColor(enabled) }
    fun setGapless(enabled: Boolean) = viewModelScope.launch { settings.setGapless(enabled) }
    fun setPlaybackSpeed(speed: Float) = viewModelScope.launch { settings.setPlaybackSpeed(speed) }
    fun setResumeOnStart(enabled: Boolean) = viewModelScope.launch { settings.setResumeOnStart(enabled) }
    fun setSleepTimerMinutes(minutes: Int) = viewModelScope.launch { settings.setSleepTimerMinutes(minutes) }
    fun setEqEnabled(enabled: Boolean) = viewModelScope.launch { settings.setEqEnabled(enabled) }

    // ---------- APE 转码缓存（P0-5：缓存按字节上限 + 设置页可查可清） ----------

    private val _apeCacheSize = MutableStateFlow(-1L)
    val apeCacheSize: StateFlow<Long> = _apeCacheSize

    /** 读取当前缓存大小（进入设置页/清理后调用） */
    fun refreshApeCacheSize() = viewModelScope.launch {
        _apeCacheSize.value = apeTranscoder.cacheSize()
    }

    /** 清理全部 APE 转码缓存 */
    fun clearApeCache() = viewModelScope.launch {
        apeTranscoder.clearCache()
        _apeCacheSize.value = 0L
    }
}
