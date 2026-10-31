package com.puretone.player.playback.cast

import java.io.File

/**
 * 投屏前的「源文件准备」阶段（M2）。
 *
 * 职责单一：把一首歌解析成一个**DLNA 渲染器可以直接拉流的本地文件**。
 * - 白名单格式（见 [CastSourcePolicy]）→ 原文件直投；
 * - 其余格式（APE、ogg、dsf…）→ 调用注入的转码回调产出 PCM WAV，再投。
 *
 * 为什么把转码回调做成构造参数而不是直接依赖 `ApeTranscoder`：
 * - 本类因此不持有 Android Context、不依赖 ffmpeg-kit，可以在纯 JVM 单测里
 *   用假回调验证「分流 / 失败收敛 / 不重复转码」这些真正的契约；
 * - 转码实现（缓存、并发去重、LRU）继续由 `ApeTranscoder` 独占，这里不重复造。
 *
 * 失败一律收敛为 [Result.Failed] 并携带**面向用户**的原因文案：投屏是附加能力，
 * 任何异常都不许抛到调用方（否则会顺着 ViewModel 协程炸到 UI 层）。
 */
class CastFilePreparer(
    private val transcodeToWav: suspend (songId: Long, sourcePath: String) -> File?,
) {

    sealed interface Result {
        /** 可以投送的文件；[transcoded] 表示是否经过转码（用于 UI 提示/日志） */
        data class Ready(val file: File, val transcoded: Boolean) : Result

        /** 准备失败，[reason] 直接用于 UI 展示 */
        data class Failed(val reason: String) : Result
    }

    /**
     * 解析待投送文件。
     *
     * @param songId 曲目 id（转码缓存 key，复用本地播放已生成的 WAV）
     * @param path   源文件绝对路径
     */
    suspend fun prepare(songId: Long, path: String): Result {
        val source = File(path)
        // 先验源文件：转码层对不存在的源也返回 null，但那样错误文案会变成
        // 「转码失败」，误导用户以为是无损曲目解码问题。分开判更好排查。
        if (!source.isFile) return Result.Failed("源文件不存在，无法投屏")

        if (!CastSourcePolicy.requiresTranscode(path)) {
            return Result.Ready(source, transcoded = false)
        }

        // runCatching 兜住转码回调的任意异常（协程取消属于 CancellationException，
        // 会正常向上传播，不被吞掉——这是刻意的：取消不是失败）
        val wav = runCatching { transcodeToWav(songId, path) }.getOrNull()
            ?: return Result.Failed("该格式需转码后才能投屏，转码失败")

        // 转码层契约要求返回完整文件，这里再做一次存在性校验：宁可报错，
        // 也不要把不存在的路径写进 DLNA 的 DIDL-Lite（渲染器会静默黑屏）
        if (!wav.isFile || wav.length() <= 0L) {
            return Result.Failed("转码产物不可用，无法投屏")
        }
        return Result.Ready(wav, transcoded = true)
    }
}
