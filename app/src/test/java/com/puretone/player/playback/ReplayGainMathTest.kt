package com.puretone.player.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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

    // ---------- onConfigure（激活/旁路，关乎默认无声回归） ----------

    private val pcm16 = AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)

    @Test
    fun `onConfigure 增益为 1 时返回 NOT_SET 旁路标准音频路径`() {
        // 默认关闭 ReplayGain 时增益恒为 1.0，必须旁路（不激活），否则会走自定义 PCM
        // 处理链——这正是 1.7.0 周期升级后默认无声的根因。
        val p = ReplayGainAudioProcessor()
        p.setGainLinear(1.0f)
        assertSame(AudioProcessor.AudioFormat.NOT_SET, p.onConfigure(pcm16))
    }

    @Test
    fun `onConfigure 开启但无标签增益为 1 仍旁路`() {
        // 开启 ReplayGain 但文件无标签且 preamp=0 → 增益 1.0 → 旁路（无标签本就不该生效）。
        val p = ReplayGainAudioProcessor()
        p.setGainLinear(ReplayGainAudioProcessor.computeLinearGain(true, null, 0f))
        assertSame(AudioProcessor.AudioFormat.NOT_SET, p.onConfigure(pcm16))
    }

    @Test
    fun `onConfigure 真实增益时 16bit 激活原样返回`() {
        val p = ReplayGainAudioProcessor()
        p.setGainLinear(ReplayGainAudioProcessor.computeLinearGain(true, -6.42f, 0f)) // ≈ 0.4776
        val out = p.onConfigure(pcm16)
        assertEquals(pcm16.sampleRate, out.sampleRate)
        assertEquals(pcm16.channelCount, out.channelCount)
        assertEquals(pcm16.encoding, out.encoding)
    }

    @Test
    fun `onConfigure 真实增益但不支持编码仍旁路`() {
        // 24/32bit 等本处理器不支持，无论增益多少都返回 NOT_SET（透传，不崩）。
        val p = ReplayGainAudioProcessor()
        p.setGainLinear(0.5f)
        assertSame(
            AudioProcessor.AudioFormat.NOT_SET,
            p.onConfigure(AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_24BIT))
        )
    }
}
