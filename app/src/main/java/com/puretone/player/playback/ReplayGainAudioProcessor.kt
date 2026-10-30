package com.puretone.player.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.tanh

/**
 * ReplayGain 音频处理器：对解码后的 PCM 施加线性增益（= 10^((rgGain + preamp)/20)），
 * 并以内置软限幅（tanh）防止增益大于 1 时削波。
 *
 * 设计：
 * - 仅处理 16-bit / float PCM；其它编码（24/32-bit 等）在 [onConfigure] 返回
 *   [AudioProcessor.AudioFormat.NOT_SET] 使本处理器「不激活」，框架直接透传、不调用
 *   [queueInput]，保证任何异常编码都不会崩；
 * - 增益以浮点存在，可在曲目切换时通过 [setGainLinear] 实时更新（逐缓冲读取，无爆音）；
 * - 软限幅仅在 |sample × gain| > 1 时介入，≤ 1 时完全透传，保证常规（衰减）场景零染色。
 *
 * 纯增益数学见 [computeLinearGain]，逐样本变换见 [applySample]，二者均可单测。
 */
@OptIn(UnstableApi::class)
class ReplayGainAudioProcessor : BaseAudioProcessor() {

    @Volatile
    private var linearGain = 1.0f

    /** 设置线性增益（>= 0）；可在播放中实时生效，逐缓冲读取，无爆音 */
    fun setGainLinear(gain: Float) {
        linearGain = gain.coerceAtLeast(0f)
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT -> inputAudioFormat
            else -> AudioProcessor.AudioFormat.NOT_SET
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val fmt = inputAudioFormat
        val size = inputBuffer.remaining()
        val out = replaceOutputBuffer(size)
        out.order(ByteOrder.nativeOrder())
        val gain = linearGain
        when (fmt.encoding) {
            C.ENCODING_PCM_16BIT -> {
                inputBuffer.order(ByteOrder.nativeOrder())
                while (inputBuffer.hasRemaining()) {
                    val sample = inputBuffer.short / 32768f
                    out.putShort(floatToShort(applySample(sample, gain)))
                }
            }
            C.ENCODING_PCM_FLOAT -> {
                while (inputBuffer.hasRemaining()) {
                    val sample = inputBuffer.float
                    out.putFloat(applySample(sample, gain))
                }
            }
            else -> {
                // 不应到达（onConfigure 已让非支持编码不激活），防御性按字节透传
                out.put(inputBuffer)
                if (inputBuffer !== AudioProcessor.EMPTY_BUFFER) inputBuffer.position(inputBuffer.limit())
            }
        }
        if (inputBuffer !== AudioProcessor.EMPTY_BUFFER) inputBuffer.clear()
        out.flip()
    }

    private fun floatToShort(v: Float): Short {
        val clamped = (v * 32767f).coerceIn(-32768f, 32767f)
        return clamped.toInt().toShort()
    }

    companion object {
        /**
         * 把 ReplayGain（dB）+ preamp（dB）换算为线性增益。
         * 关闭 ReplayGain 时返回 1.0（不做任何处理）；开启时取 (rgGain + preamp) 合计的 10^(dB/20)。
         */
        fun computeLinearGain(
            enabled: Boolean,
            rgGainDb: Float?,
            preampDb: Float
        ): Float {
            if (!enabled) return 1.0f
            val totalDb = (rgGainDb ?: 0f) + preampDb
            return (Math.pow(10.0, totalDb / 20.0)).toFloat().coerceAtLeast(0f)
        }

        /**
         * 逐样本施加增益 + 软限幅：|v| > 1 时用 tanh 收束到 (-1, 1)，杜绝削波；
         * |v| <= 1 时原值返回（衰减场景零染色）。[sample] 为归一化浮点样本（-1 ~ 1）。
         */
        fun applySample(sample: Float, gain: Float): Float {
            val v = sample * gain
            return if (v > 1f || v < -1f) tanh(v.toDouble()).toFloat() else v
        }
    }
}
