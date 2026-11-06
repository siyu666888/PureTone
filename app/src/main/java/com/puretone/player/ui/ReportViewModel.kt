package com.puretone.player.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.puretone.player.data.settings.SettingsRepository
import com.puretone.player.data.repository.StatsRepository
import com.puretone.player.data.settings.PureToneSettings
import com.puretone.player.domain.report.PeriodType
import com.puretone.player.domain.report.ReportData
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import javax.inject.Inject

/** 听歌报告 UI 状态 */
data class ReportUiState(
    val period: PeriodType = PeriodType.WEEK,
    val loading: Boolean = true,
    /** 报告数据；无数据/出错时为 null */
    val data: ReportData? = null,
    /** 听歌数据统计开关（隐私）：关闭时禁用生成/分享海报 */
    val statsEnabled: Boolean = true,
    /** 当前 App 主题模式（SYSTEM/LIGHT/DARK/BLACK），用于海报默认主题 */
    val themeMode: String = PureToneSettings.THEME_LIGHT
)

/**
 * 听歌报告视图模型（需求4 阶段2）。
 *
 * 报告是「查询结果」而非「事实记录」：每次取最近一个已结束的完整周期实时聚合，
 * 不落库、不缓存（方案 3.3）。[period] 改变或手动 [refresh] 时重新聚合。
 * 隐私开关来自 [SettingsRepository]，关闭时 [ReportUiState.statsEnabled] 为 false，
 * UI 据此禁用分享按钮并解释原因（方案 6.7）。
 */
@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class ReportViewModel @Inject constructor(
    private val repo: StatsRepository,
    private val settingsRepo: SettingsRepository
) : ViewModel() {

    private val period = MutableStateFlow(PeriodType.WEEK)
    private val refreshTick = MutableStateFlow(0L)

    /** 切换周期（阶段3：周报 / 月报 / 年报） */
    fun setPeriod(type: PeriodType) {
        if (period.value != type) period.value = type
    }

    /** 手动重新聚合（如跨周期边界后） */
    fun refresh() {
        refreshTick.value = refreshTick.value + 1
    }

    private val settings: StateFlow<PureToneSettings> =
        settingsRepo.settings.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            PureToneSettings()
        )

    private val statsEnabled: StateFlow<Boolean> = settings
        .map { it.listeningStatsEnabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val state: StateFlow<ReportUiState> = combine(period, refreshTick) { p, _ -> p }
        .flatMapLatest { p ->
            combine(
                flow = kotlinx.coroutines.flow.flow {
                    val data = runCatching {
                        repo.getReport(p, System.currentTimeMillis())
                    }.getOrNull()
                    emit(data)
                },
                settings
            ) { data, s ->
                ReportUiState(
                    period = p,
                    loading = false,
                    data = data,
                    statsEnabled = s.listeningStatsEnabled,
                    themeMode = s.themeMode
                )
            }
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            ReportUiState(loading = true)
        )
}
