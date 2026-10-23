package com.puretone.player.effect

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 音效控制器：10 段均衡器 + 重低音 + 环绕声。
 *
 * 设计要点：
 * - 全部效果挂在 ExoPlayer 的 audioSessionId 上（非全局 0），避免影响其他应用；
 * - 播放器 session 变化（重建）时通过 [attachToSession] 重绑定并恢复设置；
 * - 单例同进程唯一，由播放服务挂接、UI 层经此调参；
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
        if (newSessionId == 0) return
        // 仅在确有音效需求时才创建原生 AudioEffect：
        // 部分机型对 Equalizer/BassBoost/Virtualizer 的实现有缺陷，
        // 无谓地持有实例会抬高 native 崩溃（SIGSEGV）风险。
        if (needsEffects()) ensureEffects()
    }

    /** 是否确实需要原生音效实例 */
    private fun needsEffects(): Boolean =
        enabled || bassStrength > 0 || virtualizerStrength > 0

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
    }

    fun detach() {
        releaseEffects()
        sessionId = 0
    }

    fun setEnabled(value: Boolean) {
        enabled = value
        if (value) ensureEffects() else applyEnabled()
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

    private fun applyEnabled() {
        runCatching { equalizer?.enabled = enabled }.onFailure { Log.w(TAG, "set eq enabled failed", it) }
    }

    private fun applyBands() {
        val eq = equalizer ?: return
        runCatching {
            bandLevels.forEachIndexed { band, level ->
                eq.setBandLevel(band.toShort(), level.toShort())
            }
        }.onFailure { Log.w(TAG, "set band level failed", it) }
    }

    private fun applyBass() {
        runCatching { bassBoost?.setStrength(bassStrength.toShort()) }.onFailure { Log.w(TAG, "bass failed", it) }
    }

    private fun applyVirtualizer() {
        runCatching { virtualizer?.setStrength(virtualizerStrength.toShort()) }.onFailure { Log.w(TAG, "virtualizer failed", it) }
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
