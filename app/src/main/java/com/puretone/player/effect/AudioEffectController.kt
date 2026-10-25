package com.puretone.player.effect

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import android.util.Log
import com.puretone.player.data.settings.PureToneSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** 音效可用性（审查报告 P2-1：设备不支持时 UI 必须给反馈，而不是无声滑动） */
enum class EffectAvailability {
    /** 无音频会话或效果器尚未创建，无法判定 */
    UNKNOWN,

    /** 效果器创建成功，调节会生效 */
    SUPPORTED,

    /** 设备/系统不支持音效（Equalizer 构造失败） */
    UNSUPPORTED
}

/** 音效状态快照（可用性 + 设备实际段数） */
data class EffectStatus(
    val availability: EffectAvailability = EffectAvailability.UNKNOWN,
    /** 设备 Equalizer 实际频段数（0 表示未知） */
    val deviceBandCount: Int = 0
)

/**
 * 音效控制器：10 段均衡器 + 重低音 + 环绕声。
 *
 * 设计要点：
 * - 全部效果挂在 ExoPlayer 的 audioSessionId 上（非全局 0），避免影响其他应用；
 * - 播放器 session 变化（重建）时通过 [attachToSession] 重绑定并恢复设置；
 * - 单例同进程唯一，由播放服务挂接、UI 层经此调参；
 * - **持久化接线**：[applySettings] 是 DataStore 设置 → 音效的唯一入口，
 *   PlaybackManager 订阅设置流后调用它，冷启动/设置变更都能真正生效；
 * - **段数自适应**：UI 固定 10 段，设备 [Equalizer.getNumberOfBands] 常见为 5。
 *   直接对 band 0..9 调 setBandLevel 会在第一个越界 band 抛异常，
 *   且 runCatching 包住整个循环导致后续频段全部静默丢失（历史 bug）。
 *   这里改为「每个设备段取中心频率最接近的 UI 段增益」再下发，天然防越界；
 * - **主开关语义统一**（P1-5）：重低音/环绕声从属于均衡器主开关——
 *   主开关关闭时三者一律不启用（旧实现 `applyBass` 只看强度不看主开关，
 *   关开关后重低音仍按厂商默认响，与 `setEnabled(false)` 路径行为相反）；
 * - 所有 setter 在调用线程执行，均为本地快操作。
 */
@Singleton
class AudioEffectController @Inject constructor() {

    companion object {
        private const val TAG = "AudioEffectController"

        /** 10 段中心频率（Hz），与预设矩阵一一对应 */
        val CENTER_FREQS = intArrayOf(31, 62, 125, 250, 500, 1_000, 2_000, 4_000, 8_000, 16_000)

        /** 单段增益范围 dB */
        const val MIN_LEVEL_MB = -1500
        const val MAX_LEVEL_MB = 1500

        /** 强度效果范围 */
        const val MIN_STRENGTH = 0
        const val MAX_STRENGTH = 1000

        /**
         * 强度类效果（重低音/环绕声）的启停判定（P1-5，纯函数便于单测锁死）：
         * 从属于主开关——主开关关闭或强度为 0 一律不启用。
         *
         * 历史 bug：`applyBass` 旧实现只看 `bassStrength > 0`，用户关掉主开关
         * （eqEnabled=false）但滑块停在 500 时，经 DataStore 流回
         * [applySettings] 会把 BassBoost 重新置 enabled=true，主开关关了
         * 重低音仍在响；而直接调 `setEnabled(false)` 走 [disableAllEffects]
         * 又全关——同一语义两个入口行为相反。现统一为：两者取与。
         */
        internal fun shouldEnableEffect(masterEnabled: Boolean, strength: Int): Boolean =
            masterEnabled && strength > 0

        /** 内置预设：10 段增益（dB） */
        val PRESETS: Map<String, List<Int>> = mapOf(
            "FLAT" to List(10) { 0 },
            "POP" to listOf(-1, -1, 0, 2, 4, 4, 2, 0, -1, -1),
            "ROCK" to listOf(4, 3, 2, 0, -1, 0, 2, 3, 4, 5),
            "JAZZ" to listOf(3, 2, 1, 2, -1, -1, 0, 1, 2, 3),
            "CLASSICAL" to listOf(4, 3, 2, 1, 0, 0, 0, 1, 2, 3),
            "VOCAL" to listOf(-2, -1, 0, 2, 4, 4, 3, 1, 0, -1),
            "BASS" to listOf(6, 5, 4, 2, 0, 0, 0, 0, 0, 0)
        )
    }

    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null

    private var sessionId: Int = 0
    private var enabled: Boolean = false
    private var bandLevels: List<Int> = List(10) { 0 }
    private var bassStrength: Int = 0
    private var virtualizerStrength: Int = 0

    private val _status = MutableStateFlow(EffectStatus())
    val status: StateFlow<EffectStatus> = _status.asStateFlow()

    /** 当前是否可用（设备不支持音效时为 false） */
    val isAvailable: Boolean
        get() = sessionId != 0 && equalizer != null

    /**
     * 挂接到播放器音频会话。
     * session 变化时先释放旧效果再按当前设置重建。
     */
    @Synchronized
    fun attachToSession(newSessionId: Int) {
        if (newSessionId == sessionId && (equalizer != null || !needsEffects())) return
        releaseEffects()
        sessionId = newSessionId
        if (newSessionId == 0) {
            _status.value = EffectStatus()
            return
        }
        // 仅在确有音效需求时才创建原生 AudioEffect：
        // 部分机型对 Equalizer/BassBoost/Virtualizer 的实现有缺陷，
        // 无谓地持有实例会抬高 native 崩溃（SIGSEGV）风险。
        // session 重建后这里会把当前设置重新灌一遍，保证「换会话不丢音效」。
        if (needsEffects()) ensureEffects() else {
            _status.value = EffectStatus()
            disableAllEffects()
        }
    }

    /**
     * 是否确实需要原生音效实例。
     *
     * P1-5：与主开关语义统一——重低音/环绕声从属于主开关，主开关关闭时
     * 没有任何效果需要生效，也就不必创建原生 AudioEffect 实例（部分机型
     * 对效果器的实现有缺陷，无谓持有会抬高 native 崩溃风险）。
     */
    private fun needsEffects(): Boolean = enabled

    /**
     * 按需创建音效实例（幂等）。创建后立即回放当前设置。
     * 构造函数本身也 runCatching，设备不支持时静默降级。
     */
    @Synchronized
    private fun ensureEffects() {
        if (sessionId == 0) return
        if (equalizer == null) {
            equalizer = runCatching { Equalizer(0, sessionId) }.getOrNull().also {
                if (it == null) Log.w(TAG, "Equalizer unsupported on this device")
            }
        }
        if (bassBoost == null) {
            bassBoost = runCatching { BassBoost(0, sessionId) }.getOrNull()
        }
        if (virtualizer == null) {
            virtualizer = runCatching { Virtualizer(0, sessionId) }.getOrNull()
        }
        // 恢复设置
        applyEnabled()
        applyBands()
        applyBass()
        applyVirtualizer()
        // P2-1：效果器创建成败即设备支持结论，推送给 UI 提示
        _status.value = EffectStatus(
            availability = if (equalizer != null) EffectAvailability.SUPPORTED
            else EffectAvailability.UNSUPPORTED,
            deviceBandCount = bandCount()
        )
    }

    fun detach() {
        releaseEffects()
        sessionId = 0
        _status.value = EffectStatus()
    }

    fun setEnabled(value: Boolean) {
        enabled = value
        if (value) ensureEffects() else disableAllEffects()
    }

    fun setBandLevel(band: Int, levelMb: Int) {
        if (band !in bandLevels.indices) return
        bandLevels = bandLevels.toMutableList().also { it[band] = levelMb.coerceIn(MIN_LEVEL_MB, MAX_LEVEL_MB) }
        applyBands()
    }

    fun setBandLevels(levels: List<Int>) {
        if (levels.size != CENTER_FREQS.size) return
        bandLevels = levels.map { it.coerceIn(MIN_LEVEL_MB, MAX_LEVEL_MB) }
        applyBands()
    }

    fun applyPreset(presetName: String) {
        val preset = PRESETS[presetName] ?: return
        setBandLevels(preset.map { it * 100 })
    }

    fun setBassBoostStrength(strength: Int) {
        bassStrength = strength.coerceIn(MIN_STRENGTH, MAX_STRENGTH)
        applyBass()
    }

    fun setVirtualizerStrength(strength: Int) {
        virtualizerStrength = strength.coerceIn(MIN_STRENGTH, MAX_STRENGTH)
        applyVirtualizer()
    }

    /** 设备支持的频段数（0 表示不可用） */
    fun bandCount(): Int = runCatching { equalizer?.numberOfBands?.toInt() ?: 0 }.getOrDefault(0)

    /** 当前 10 段增益（dB 的 100 倍，mB） */
    fun currentBandLevels(): List<Int> = bandLevels.toList()

    /**
     * 把持久化设置一次性灌入控制器（DataStore → 音效的唯一入口）。
     *
     * 由 PlaybackManager 订阅设置流后调用，覆盖冷启动恢复与用户改动两条路径；
     * [com.puretone.player.ui.EqViewModel] 的单项操作同样落库，最终也流回这里，
     * 保证「设置页显示的开关」与「实际听到的效果」永不脱节。
     */
    @Synchronized
    fun applySettings(s: PureToneSettings) {
        enabled = s.eqEnabled
        bandLevels = normalizeBands(s.eqBandLevels)
        bassStrength = s.bassBoostStrength.coerceIn(MIN_STRENGTH, MAX_STRENGTH)
        virtualizerStrength = s.virtualizerStrength.coerceIn(MIN_STRENGTH, MAX_STRENGTH)
        if (needsEffects()) ensureEffects() else disableAllEffects()
    }

    /** 把任意长度的频段列表归一为 10 段（长度不符时按比例取值/补零） */
    private fun normalizeBands(levels: List<Int>): List<Int> {
        if (levels.size == CENTER_FREQS.size) return levels
        return List(CENTER_FREQS.size) { i ->
            levels.getOrNull(i) ?: 0
        }.map { it.coerceIn(MIN_LEVEL_MB, MAX_LEVEL_MB) }
    }

    /**
     * 计算「设备段 → 增益」映射：每个设备段取中心频率最接近的 UI 段增益。
     * 返回 null 表示设备不可用（无 equalizer 或读不到段数/中心频率）。
     */
    private fun deviceBandLevels(): List<Int>? {
        val eq = equalizer ?: return null
        val count = runCatching { eq.numberOfBands.toInt() }.getOrDefault(0)
        if (count <= 0) return null
        val deviceFreqHz = (0 until count).mapNotNull { b ->
            // getCenterFreq 返回 milliHertz
            runCatching { eq.getCenterFreq(b.toShort()) / 1000 }.getOrNull()
        }
        if (deviceFreqHz.size != count) return null
        return deviceFreqHz.map { df ->
            CENTER_FREQS.indices.minBy { kotlin.math.abs(CENTER_FREQS[it] - df) }
                .let { bandLevels[it] }
        }
    }

    private fun applyEnabled() {
        runCatching { equalizer?.enabled = enabled }.onFailure { Log.w(TAG, "set eq enabled failed", it) }
    }

    private fun applyBands() {
        val eq = equalizer ?: return
        val levels = deviceBandLevels() ?: return
        val range = runCatching { eq.bandLevelRange }.getOrNull()
        runCatching {
            levels.forEachIndexed { band, level ->
                val clamped = if (range != null) {
                    level.coerceIn(range.first().toInt(), range.last().toInt())
                } else {
                    level
                }
                eq.setBandLevel(band.toShort(), clamped.toShort())
            }
        }.onFailure { Log.w(TAG, "set band level failed", it) }
    }

    private fun applyBass() {
        val bb = bassBoost ?: return
        runCatching {
            // P1-5：主开关关闭时一律不启用（旧实现只看强度，关了主开关
            // 重低音仍按厂商默认生效）
            val on = shouldEnableEffect(enabled, bassStrength)
            bb.enabled = on
            if (on) bb.setStrength(bassStrength.toShort())
        }.onFailure { Log.w(TAG, "bass failed", it) }
    }

    private fun applyVirtualizer() {
        val v = virtualizer ?: return
        runCatching {
            // P1-5：同 applyBass——从属于主开关
            val on = shouldEnableEffect(enabled, virtualizerStrength)
            v.enabled = on
            if (on) v.setStrength(virtualizerStrength.toShort())
        }.onFailure { Log.w(TAG, "virtualizer failed", it) }
    }

    /** 主开关关闭时统一关闭三个效果（历史 bug：只关 Equalizer，重低音/环绕仍按厂商默认生效） */
    private fun disableAllEffects() {
        runCatching { equalizer?.enabled = false }.onFailure { Log.w(TAG, "disable eq failed", it) }
        runCatching { bassBoost?.enabled = false }.onFailure { Log.w(TAG, "disable bass failed", it) }
        runCatching { virtualizer?.enabled = false }.onFailure { Log.w(TAG, "disable virtualizer failed", it) }
    }

    private fun releaseEffects() {
        runCatching { equalizer?.release() }
        runCatching { bassBoost?.release() }
        runCatching { virtualizer?.release() }
        equalizer = null
        bassBoost = null
        virtualizer = null
    }
}
