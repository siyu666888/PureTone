package com.puretone.player.playback

import com.puretone.player.data.settings.PureToneSettings
import com.puretone.player.data.settings.SettingsRepository
import com.puretone.player.data.settings.SleepTimerMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 睡眠定时控制器（P1-1 收尾抽取）。
 *
 * 把「睡眠定时」这一簇状态机从 [PlaybackManager] 下沉到本类。它原本与 ReplayGain /
 * 音效 / 播放顺序挤在同一个 [PlaybackManager.applySettings] 里，且自带一个
 * `sleepTimerJob` 字段与五个分支方法，是 god class 里相对独立、耦合最浅的一块——
 * 只依赖 [SettingsRepository]、[PlaybackUiState] 流、[CoroutineScope] 与「暂停」动作，
 * 不碰播放器实例、不碰交叉淡化、不碰投屏，故优先抽出验证抽取范式。
 *
 * 三种模式（2026-10-30 细粒度定时）共用同一份持久化状态，由
 * [SettingsRepository.resolveSleepTimerMode] 统一判定，避免「UI 选的模式」与
 * 「播放器实际执行的模式」出现两套解释：
 * - [SleepTimerMode.MINUTES]：按「到期时刻」恢复倒计时；已过期则收尾并清零；
 * - [SleepTimerMode.END_OF_TRACK]：不排倒计时 job，结束时机由播放器回调决定；
 * - [SleepTimerMode.OFF]：取消 job 并清理残留。
 *
 * MINUTES 到期后要把定时四字段全部清零——否则进程重启后既会按完整时长重新倒计时
 * （旧「定时复活」bug），设置页时段也不会回到「关」。
 */
internal class SleepTimerController(
    private val settings: SettingsRepository,
    private val uiState: MutableStateFlow<PlaybackUiState>,
    private val scope: CoroutineScope,
    private val pause: () -> Unit,
) {
    /** 倒计时 job（MINUTES 模式才有；END_OF_TRACK 与 OFF 不持有） */
    private var sleepTimerJob: Job? = null

    /**
     * 当前已应用的设置快照（供 [refreshRemaining] 计算剩余时间）。
     * 与 [PlaybackManager] 的 `lastSettings` 在同一 [PlaybackManager.applySettings] 调用内
     * 同步赋值，二者时序一致——这里只保留睡眠定时关心的那份，避免把整份设置快照
     * 暴露给一个只管定时的类。
     */
    private var applied: PureToneSettings = PureToneSettings()

    /** 设置变化入口：按模式分派 */
    fun applySettings(s: PureToneSettings) {
        applied = s
        when (settings.resolveSleepTimerMode(s)) {
            SleepTimerMode.MINUTES -> applyMinutes(s, settings.sleepRemainingMs(s))
            SleepTimerMode.END_OF_TRACK -> applyEndOfTrack()
            SleepTimerMode.OFF -> cancel(s)
        }
    }

    /** ticker 调用：重算剩余时间并刷新 UI（仅变化时更新，避免每 500ms 无谓写流） */
    fun refreshRemaining() {
        val remaining = settings.sleepRemainingMs(applied)
        if (remaining != uiState.value.sleepRemainingMs) {
            uiState.update { it.copy(sleepRemainingMs = remaining) }
        }
    }

    /** 倒计时型定时：以「到期时刻」为准恢复/取消（进程重建后仍准确） */
    private fun applyMinutes(s: PureToneSettings, remaining: Long) {
        if (remaining <= 0L) {
            // 到期时刻已过（例如到期瞬间进程被杀）→ 收尾并把残留写回全关
            clearSleepTimerJob()
            if (settings.hasSleepTimer(s)) {
                scope.launch { settings.clearSleepTimer() }
            }
            uiState.update { it.copy(sleepTimerMode = SleepTimerMode.OFF, sleepRemainingMs = 0L) }
            return
        }
        clearSleepTimerJob()
        sleepTimerJob = scope.launch {
            delay(remaining)
            PlaybackEventLog.record("sleep-timer", "MINUTES 倒计时到点 → 暂停（剩余 $remaining ms 已走完）")
            pause()
            // 关键：触发后清零，防止重启后再次倒计时
            scope.launch { settings.clearSleepTimer() }
            uiState.update { it.copy(sleepTimerMode = SleepTimerMode.OFF, sleepRemainingMs = 0L) }
        }
        uiState.update { it.copy(sleepTimerMode = SleepTimerMode.MINUTES, sleepRemainingMs = remaining) }
    }

    /**
     * 「播完本曲停止」：没有时间上限，只登记模式，真正的暂停时机由
     * [finishEndOfTrack] 在自然切歌 / 末首播完时执行。
     */
    private fun applyEndOfTrack() {
        clearSleepTimerJob()
        uiState.update { it.copy(sleepTimerMode = SleepTimerMode.END_OF_TRACK, sleepRemainingMs = 0L) }
    }

    /** 关：取消倒计时并清理持久化残留 */
    private fun cancel(s: PureToneSettings) {
        clearSleepTimerJob()
        if (settings.hasSleepTimer(s) || s.sleepTimerMinutes > 0) {
            scope.launch { settings.clearSleepTimer() }
        }
        uiState.update { it.copy(sleepTimerMode = SleepTimerMode.OFF, sleepRemainingMs = 0L) }
    }

    private fun clearSleepTimerJob() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
    }

    /**
     * 「播完本曲停止」到点：暂停播放并把定时归零。
     *
     * 幂等：重复调用（自动切歌回调与 STATE_ENDED 回调可能同时到达）时，
     * 第二次 [PlaybackUiState.sleepTimerMode] 已是 OFF，直接返回。
     */
    fun finishEndOfTrack() {
        if (uiState.value.sleepTimerMode != SleepTimerMode.END_OF_TRACK) return
        PlaybackEventLog.record("sleep-timer", "END_OF_TRACK 到点 → 暂停")
        pause()
        uiState.update { it.copy(sleepTimerMode = SleepTimerMode.OFF, sleepRemainingMs = 0L) }
        scope.launch { settings.clearSleepTimer() }
    }
}
