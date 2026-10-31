package com.puretone.player.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.puretone.player.data.settings.PureToneSettings
import com.puretone.player.data.settings.SettingsRepository
import com.puretone.player.playback.ApeTranscoder
import com.puretone.player.widget.WidgetPinner
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 设置视图模型：主题/播放/音效偏好读写。
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val apeTranscoder: ApeTranscoder,
    private val widgetPinner: WidgetPinner
) : ViewModel() {

    val state: StateFlow<PureToneSettings> = settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PureToneSettings())

    fun setThemeMode(mode: String) = viewModelScope.launch { settings.setThemeMode(mode) }
    fun setAmoledBlack(enabled: Boolean) = viewModelScope.launch { settings.setAmoledBlack(enabled) }
    fun setDynamicColor(enabled: Boolean) = viewModelScope.launch { settings.setDynamicColor(enabled) }
    fun setGapless(enabled: Boolean) = viewModelScope.launch { settings.setGapless(enabled) }
    fun setPlaybackSpeed(speed: Float) = viewModelScope.launch { settings.setPlaybackSpeed(speed) }
    fun setResumeOnStart(enabled: Boolean) = viewModelScope.launch { settings.setResumeOnStart(enabled) }
    /** 交叉淡化时长（毫秒），0 = 关闭 */
    fun setCrossfadeMs(ms: Int) = viewModelScope.launch { settings.setCrossfadeMs(ms) }
    /** 睡眠定时：minutes > 0 设倒计时，0 关闭（清除模式与到期时刻） */
    fun setSleepTimer(minutes: Int) = viewModelScope.launch {
        if (minutes > 0) settings.setSleepTimerMinutes(minutes) else settings.clearSleepTimer()
    }

    /** 睡眠定时：当前这首播完就停（不限时长） */
    fun setSleepTimerEndOfTrack() = viewModelScope.launch { settings.setSleepTimerEndOfTrack() }
    fun setEqEnabled(enabled: Boolean) = viewModelScope.launch { settings.setEqEnabled(enabled) }

    // ---------- ReplayGain（P1） ----------
    fun setReplayGainEnabled(enabled: Boolean) =
        viewModelScope.launch { settings.setReplayGainEnabled(enabled) }

    fun setReplayGainMode(mode: String) = viewModelScope.launch { settings.setReplayGainMode(mode) }

    fun setReplayGainPreamp(db: Float) = viewModelScope.launch { settings.setReplayGainPreampDb(db) }

    // ---------- 桌面小部件 ----------

    /** 桌面是否支持「固定到桌面」（不支持时设置页该开关置灰） */
    fun canPinWidget(): Boolean = widgetPinner.isSupported()

    /**
     * 桌面上是否真实存在本 App 的小部件。
     *
     * 这是「真实状态」而非偏好值：用户可能在系统确认弹窗里取消，也可能添加后又在
     * 桌面上长按删掉。开关只展示它，由 [refreshWidgetAdded] 按需重读。
     */
    private val _widgetAdded = MutableStateFlow(false)
    val widgetAdded: StateFlow<Boolean> = _widgetAdded.asStateFlow()

    /** 重新查询桌面上的小部件数量（进入设置页 / 从桌面返回时调用） */
    fun refreshWidgetAdded() {
        _widgetAdded.value = widgetPinner.isAdded()
    }

    /** 请求把播放控制小部件固定到桌面；返回 false = 当前环境不支持 */
    fun pinWidget(): Boolean = widgetPinner.requestPin()

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
