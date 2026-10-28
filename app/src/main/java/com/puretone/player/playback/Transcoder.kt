package com.puretone.player.playback

import java.io.File

/**
 * 一次转码请求的完整描述。
 *
 * 之所以把参数显式建模，而不是让实现自己拼命令行：
 * - 调用方（如 [ApeTranscoder]）表达「我要什么」，实现（如 ffmpeg）决定「怎么做」；
 * - 超时是**策略**，属于调用方而非转码引擎，因此放在请求里，便于不同场景（首播 /
 *   后台预热）给不同上限，也便于测试用极小值验证超时分支。
 */
data class TranscodeRequest(
    /** 源文件（如 .ape），调用方须保证已存在 */
    val source: File,
    /** 目标文件；实现应先写临时文件再原子改名，避免留下半截产物 */
    val destination: File,
    /** 目标音频编码（ffmpeg 的 `-c:a` 取值） */
    val audioCodec: String = DEFAULT_AUDIO_CODEC,
    /** 目标容器格式（ffmpeg 的 `-f` 取值） */
    val containerFormat: String = DEFAULT_CONTAINER_FORMAT,
    /** 墙钟上限，超时须主动终止底层进程并返回 false */
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    companion object {
        /** 16bit 有符号 PCM：ExoPlayer 可原生播放，且转码不做有损重编码 */
        const val DEFAULT_AUDIO_CODEC = "pcm_s16le"
        const val DEFAULT_CONTAINER_FORMAT = "wav"

        /**
         * 默认超时（原 P3-22 的取值）。
         *
         * WAV 是无损 PCM，转码不重编码，正常秒级到几十秒；5 分钟足够覆盖超长单曲
         * 与低端机，超出即判定异常——否则损坏/编码异常的源会让底层进程永不返回，
         * 调用方（每曲 Mutex）会被永久占住。
         */
        const val DEFAULT_TIMEOUT_MS = 5L * 60 * 1000
    }
}

/**
 * 音频转码抽象层。
 *
 * **存在意义（技术债务治理）**：下游只依赖本接口，ffmpeg-kit 被隔离在
 * [FfmpegKitTranscoder] 一个实现里。ffmpeg-kit 已归档停维护、Maven Central 下架
 * （当前靠仓库内的本地 AAR），且体积较大；一旦需要替换（自编译精简 ffmpeg、
 * 或改为调用系统解码器），只需新增实现类并改 `di/TranscoderModule` 一处绑定，
 * 播放层的缓存 / 并发 / LRU 逻辑一行不动。
 */
interface Transcoder {

    /**
     * 把 [TranscodeRequest.source] 转码到 [TranscodeRequest.destination]。
     *
     * 约定：
     * - 返回 true 时 [TranscodeRequest.destination] 必须是**完整可用**的文件；
     * - 失败 / 超时 / 取消一律返回 false，且不得留下目标文件（由调用方兜底删除也安全）；
     * - 不得抛出：任何异常都应收敛为 false，调用方不必 try-catch。
     */
    suspend fun transcode(request: TranscodeRequest): Boolean

    /**
     * 底层转码引擎是否可用（原生库已加载、可执行）。
     *
     * 用于优雅降级：若不可用，调用方可以跳过转码并给出提示，而不是让用户
     * 卡在「转码中」。实现应惰性探测并缓存结果，避免重复开销。
     */
    fun isAvailable(): Boolean
}
