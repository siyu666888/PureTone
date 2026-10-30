package com.puretone.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ReplayGain 纯函数单测（不依赖 Android / Robolectric，纯 JVM）。
 *
 * 覆盖三类可独立验证的数学：
 * 1. [ReplayGainExtractor.parseGainDb] —— 标签字符串 → dB 浮点；
 * 2. [ReplayGainAudioProcessor.computeLinearGain] —— (rgGain + preamp) dB → 线性增益；
 * 3. [ReplayGainAudioProcessor.applySample] —— 逐样本乘增益 + tanh 软限幅。
 */
class ReplayGainMathTest {

    // ---------- parseGainDb ----------

    @Test
    fun `parseGainDb 标准带单位`() {
        assertEquals(-6.42f, ReplayGainExtractor.parseGainDb("-6.42 dB")!!, 0.0001f)
    }

    @Test
    fun `parseGainDb 带正号无单位`() {
        assertEquals(3.0f, ReplayGainExtractor.parseGainDb("+3.0")!!, 0.0001f)
    }

    @Test
    fun `parseGainDb 零`() {
        assertEquals(0.0f, ReplayGainExtractor.parseGainDb("0")!!, 0.0001f)
    }

    @Test
    fun `parseGainDb 逗号小数点（部分工具写法）`() {
        assertEquals(-2.1f, ReplayGainExtractor.parseGainDb("  -2,1 dB")!!, 0.0001f)
    }

    @Test
    fun `parseGainDb 正增益带单位`() {
        assertEquals(12.0f, ReplayGainExtractor.parseGainDb("+12.0 dB")!!, 0.0001f)
    }

    @Test
    fun `parseGainDb 空串返回 null`() {
        assertNull(ReplayGainExtractor.parseGainDb(""))
    }

    @Test
    fun `parseGainDb null 返回 null`() {
        assertNull(ReplayGainExtractor.parseGainDb(null))
    }

    @Test
    fun `parseGainDb 非数字返回 null`() {
        assertNull(ReplayGainExtractor.parseGainDb("abc"))
        assertNull(ReplayGainExtractor.parseGainDb("gain"))
    }

    // ---------- computeLinearGain ----------

    @Test
    fun `computeLinearGain 关闭时恒为 1`() {
        assertEquals(1.0f, ReplayGainAudioProcessor.computeLinearGain(false, -6.42f, 0f), 0.0001f)
        assertEquals(1.0f, ReplayGainAudioProcessor.computeLinearGain(false, null, 5f), 0.0001f)
    }

    @Test
    fun `computeLinearGain 开启但无标签无 preamp 为 1`() {
        assertEquals(1.0f, ReplayGainAudioProcessor.computeLinearGain(true, null, 0f), 0.0001f)
    }

    @Test
    fun `computeLinearGain 典型衰减 负六点四二dB`() {
        // 10^(-6.42/20) = 10^(-0.321) ≈ 0.4776
        assertEquals(0.4776f, ReplayGainAudioProcessor.computeLinearGain(true, -6.42f, 0f), 0.001f)
    }

    @Test
    fun `computeLinearGain rg 与 preamp 抵消为 1`() {
        assertEquals(1.0f, ReplayGainAudioProcessor.computeLinearGain(true, -6.42f, 6.42f), 0.001f)
    }

    @Test
    fun `computeLinearGain preamp 提升 正三dB`() {
        // 10^(3/20) ≈ 1.4125（rg=0 时仅 preamp 生效）
        assertEquals(1.4125f, ReplayGainAudioProcessor.computeLinearGain(true, 0f, 3f), 0.001f)
    }

    // ---------- applySample ----------

    @Test
    fun `applySample 增益 1 原值返回`() {
        assertEquals(0.5f, ReplayGainAudioProcessor.applySample(0.5f, 1.0f), 0.0001f)
    }

    @Test
    fun `applySample 恰好 1 不触发限幅`() {
        assertEquals(1.0f, ReplayGainAudioProcessor.applySample(0.5f, 2.0f), 0.0001f)
    }

    @Test
    fun `applySample 衰减场景零染色`() {
        assertEquals(0.45f, ReplayGainAudioProcessor.applySample(0.3f, 1.5f), 0.0001f)
    }

    @Test
    fun `applySample 正溢出走 tanh 软限幅`() {
        // 0.9 * 2.0 = 1.8 → tanh(1.8) ≈ 0.9468
        assertEquals(0.9468f, ReplayGainAudioProcessor.applySample(0.9f, 2.0f), 0.001f)
    }

    @Test
    fun `applySample 负溢出走 tanh 软限幅`() {
        // -0.9 * 2.0 = -1.8 → tanh(-1.8) ≈ -0.9468
        assertEquals(-0.9468f, ReplayGainAudioProcessor.applySample(-0.9f, 2.0f), 0.001f)
    }
}
