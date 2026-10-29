package com.puretone.player.playback

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [Transcoder] 的 ffmpeg-kit 实现——**全项目唯一接触 ffmpeg-kit 的地方**。
 *
 * 由 `di/TranscoderModule` 绑定到 [Transcoder]，业务层不应直接引用本类。
 *
 * 可靠性设计（承接原 ApeTranscoder 的 P0-5 / P3-22）：
 * - `executeWithArgumentsAsync` 走数组参数：路径含引号/空格不会被截断或注入参数；
 * - 异步执行 + [withTimeoutOrNull]：损坏或编码异常的源会让 ffmpeg 长时间不返回，
 *   超时主动 cancel 会话，避免调用方的每曲 Mutex 被永久占住；
 * - 整个调用包 [runCatching]：原生库缺失等致命错误收敛为 false，不向上抛。
 */
@Singleton
class FfmpegKitTranscoder @Inject constructor() : Transcoder {

    /** 可用性探测结果缓存（null = 尚未探测） */
    @Volatile
    private var availability: Boolean? = null

    override fun isAvailable(): Boolean {
        availability?.let { return it }
        val ok = runCatching {
            // 轻量探测：只让 ffmpeg 打印版本，不产生文件 IO
            val session = FFmpegKit.executeWithArguments(arrayOf("-version"))
            ReturnCode.isSuccess(session.returnCode)
        }.getOrDefault(false)
        availability = ok
        return ok
    }

    override suspend fun transcode(request: TranscodeRequest): Boolean = withContext(Dispatchers.IO) {
        // 注意：runCatching 无法包裹 suspend 调用，故用 try/catch 兜住所有路径，
        // 兑现接口「任何异常收敛为 false」的约定（原生库缺失等致命错误也不例外）。
        try {
            if (!isAvailable()) return@withContext false

            val done = CompletableDeferred<Boolean>()
            val session = FFmpegKit.executeWithArgumentsAsync(
                arrayOf(
                    "-y",
                    "-i", request.source.absolutePath,
                    "-c:a", request.audioCodec,
                    "-f", request.containerFormat,
                    request.destination.absolutePath
                )
            ) { s -> done.complete(ReturnCode.isSuccess(s.returnCode)) }

            // P3-8：withTimeoutOrNull 仅在超时返回 null；协程被取消时它**重抛**
            // CancellationException（不会返回 null）。用 runCatching 接住取消，
            // 主动终止 ffmpeg 进程——否则切歌/清队列这类「超时之外的取消路径」
            // 会让孤儿进程继续写（已删除 inode 的）临时文件、空耗 CPU。
            val success = runCatching {
                withTimeoutOrNull(request.timeoutMs) { done.await() }
            }.getOrElse { e ->
                runCatching { FFmpegKit.cancel(session.sessionId) }
                if (e is CancellationException) throw e
                null
            }
            if (success == null) {
                // 超时：终止 ffmpeg，避免残留进程继续写这个目标文件
                runCatching { FFmpegKit.cancel(session.sessionId) }
                return@withContext false
            }
            success && request.destination.length() > 0
        } catch (t: Throwable) {
            false
        }
    }
}
